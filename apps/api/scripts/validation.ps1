$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "test-common.ps1")

$projectRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $projectRoot
$apiProcess = $null

function Get-QuestionView {
  param(
    [string]$Token,
    [string]$ClassroomCode,
    [string]$QuestionId
  )

  $response = Invoke-ApiJson -Method "GET" -Path "/api/questions?classroomCode=$ClassroomCode" -Token $Token -Body $null
  Assert-True ($response.StatusCode -eq 200) "Unable to load question $QuestionId"
  return @($response.Body.questions | Where-Object { [string]$_.id -eq $QuestionId })[0]
}

function Wait-ForQuestionBuzzStatus {
  param(
    [string]$Token,
    [string]$ClassroomCode,
    [string]$QuestionId,
    [string]$ExpectedStatus,
    [int]$TimeoutSeconds = 10
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $question = Get-QuestionView -Token $Token -ClassroomCode $ClassroomCode -QuestionId $QuestionId
    if ([string]$question.buzzStatus -eq $ExpectedStatus) {
      return $question
    }
    Start-Sleep -Milliseconds 100
  } while ((Get-Date) -lt $deadline)

  throw "Question $QuestionId did not reach buzz status $ExpectedStatus within $TimeoutSeconds seconds"
}

function Get-TimeoutEvents {
  param(
    [string]$Token,
    [string]$ClassroomCode,
    [string]$QuestionId
  )

  $response = Invoke-ApiJson -Method "GET" -Path "/api/realtime/events?classroomCode=$ClassroomCode&since=0" -Token $Token -Body $null
  Assert-True ($response.StatusCode -eq 200) "Unable to load realtime events for $QuestionId"
  return @($response.Body.events | Where-Object {
    [string]$_.type -eq "BUZZ_CANCELLED" `
      -and [string]$_.payload.reason -eq "TIMEOUT" `
      -and ([string]$_.payload.packageId -eq $QuestionId -or [string]$_.payload.questionId -eq $QuestionId)
  })
}

function Wait-ForSingleTimeoutEvent {
  param(
    [string]$Token,
    [string]$ClassroomCode,
    [string]$QuestionId,
    [int]$TimeoutSeconds = 10
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $events = @(Get-TimeoutEvents -Token $Token -ClassroomCode $ClassroomCode -QuestionId $QuestionId)
    if ($events.Count -eq 1) {
      return $events[0]
    }
    if ($events.Count -gt 1) {
      throw "Question $QuestionId published duplicate timeout events"
    }
    Start-Sleep -Milliseconds 100
  } while ((Get-Date) -lt $deadline)

  throw "Question $QuestionId did not publish a timeout event within $TimeoutSeconds seconds"
}

try {
  & (Join-Path $PSScriptRoot "build.ps1")
  Start-ComposeInfra -RepoRoot $repoRoot
  Wait-ForInfra -RepoRoot $repoRoot

  $testSourceRoot = Join-Path $projectRoot "src\test\java"
  $testSources = @(Get-ChildItem -Path $testSourceRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName })
  if ($testSources.Count -gt 0 -and $env:SKIP_API_UNIT_TESTS -ne "1") {
    $mainSourceRoot = Join-Path $projectRoot "src\main\java"
    $mainSources = @(Get-ChildItem -Path $mainSourceRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $testClassRoot = Join-Path $env:TEMP "classroom-api\test-classes"
    if (Test-Path -LiteralPath $testClassRoot) {
      Remove-Item -Recurse -Force -LiteralPath $testClassRoot
    }
    New-Item -ItemType Directory -Path $testClassRoot -Force | Out-Null
    $allTestSources = @($mainSources + $testSources)
    javac -encoding UTF-8 -d $testClassRoot $allTestSources
    if ($LASTEXITCODE -ne 0) {
      throw "API test compilation failed with exit code $LASTEXITCODE"
    }
    $testClassPath = "$testClassRoot;$(Get-DriverJar)"
    java -cp $testClassPath com.classroom.api.buzz.BuzzDeadlineServiceTest
    if ($LASTEXITCODE -ne 0) {
      throw "Buzz deadline service tests failed with exit code $LASTEXITCODE"
    }
    java -cp $testClassPath com.classroom.api.question.QuestionPackageControllerTest
    if ($LASTEXITCODE -ne 0) {
      throw "Question package answer-limit validation tests failed with exit code $LASTEXITCODE"
    }
  }

  $apiProcess = Start-ApiProcess -ProjectRoot $projectRoot
  Wait-ForApi
  Wait-ForPort -HostName "127.0.0.1" -Port $script:ApiTestWebSocketPort

  $teacherToken = New-Token -DisplayName "Teacher QA" -Role "TEACHER" -UserId "teacher-qa"
  $studentOneToken = New-Token -DisplayName "Student One" -Role "STUDENT" -UserId "student-one"
  $studentTwoToken = New-Token -DisplayName "Student Two" -Role "STUDENT" -UserId "student-two"
  $assistantToken = New-Token -DisplayName "Assistant QA" -Role "ASSISTANT" -UserId "assistant-qa"
  $nonMemberToken = New-Token -DisplayName "Outside Student" -Role "STUDENT" -UserId "outside-student"

  $unauthorized = Invoke-ApiJson -Method "POST" -Path "/api/classrooms" -Token $studentOneToken -Body @{
    name = "Forbidden Room"
    participationSettings = '{"buzzBonus":5,"wrongAnswerPenalty":2}'
  }
  Assert-True ($unauthorized.StatusCode -eq 403) "Authorization test failed"

  $classroomResponse = Invoke-ApiJson -Method "POST" -Path "/api/classrooms" -Token $teacherToken -Body @{
    name = "Validation Room"
    participationSettings = '{"buzzBonus":5,"wrongAnswerPenalty":2}'
  }
  Assert-True ($classroomResponse.StatusCode -eq 201) "Classroom creation failed"
  $classroomCode = [string]$classroomResponse.Body.classroom.code

  $sheetConfigResponse = Invoke-ApiJson -Method "POST" -Path "/api/sheet-configs" -Token $teacherToken -Body @{
    name = "Validation Sheet"
    sheetId = "validation-sheet-$([guid]::NewGuid())"
  }
  Assert-True ($sheetConfigResponse.StatusCode -eq 201) "Sheet configuration creation failed"
  $sheetConfigId = [string]$sheetConfigResponse.Body.config.id

  $invalidAnswerTimeLimits = @(
    [pscustomobject]@{ Name = "zero"; Value = 0 },
    [pscustomobject]@{ Name = "negative"; Value = -1 },
    [pscustomobject]@{ Name = "fractional"; Value = 1.5 },
    [pscustomobject]@{ Name = "malformed"; Value = "abc" },
    [pscustomobject]@{ Name = "blank"; Value = "" },
    [pscustomobject]@{ Name = "null"; Value = $null }
  )
  foreach ($invalidLimit in $invalidAnswerTimeLimits) {
    $invalidQuestionLimit = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      prompt = "Invalid question limit $($invalidLimit.Name)"
      type = "BUZZ"
      score = 0
      answerKey = "buzz"
      publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "Z1" } | ConvertTo-Json -Compress)
      answerTimeLimitSeconds = $invalidLimit.Value
    }
    Assert-True ($invalidQuestionLimit.StatusCode -eq 400) "Question API accepted $($invalidLimit.Name) answerTimeLimitSeconds"

    $invalidPackageLimit = Invoke-ApiJson -Method "POST" -Path "/api/question-packages" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      title = "Invalid package limit $($invalidLimit.Name)"
      sheetConfigId = $sheetConfigId
      items = @(@{ prompt = "Invalid limit"; targetCell = "Z2" })
      score = 0
      answerTimeLimitSeconds = $invalidLimit.Value
    }
    Assert-True ($invalidPackageLimit.StatusCode -eq 400) "Package API accepted $($invalidLimit.Name) answerTimeLimitSeconds"
  }
  $questionsAfterInvalidLimits = Invoke-ApiJson -Method "GET" -Path "/api/questions?classroomCode=$classroomCode" -Token $teacherToken -Body $null
  Assert-True ($questionsAfterInvalidLimits.StatusCode -eq 200) "Question list failed after invalid limit validation"
  Assert-True (@($questionsAfterInvalidLimits.Body.questions).Count -eq 0) "Invalid answer time limit persisted a question or package"

  $questionResponse = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Choose A"
    type = "SINGLE_CHOICE"
    score = 10
    answerKey = "A"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "A1" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 30
  }
  Assert-True ($questionResponse.StatusCode -eq 201) "Question creation failed: status=$($questionResponse.StatusCode) body=$($questionResponse.Body | ConvertTo-Json -Compress)"
  Assert-True ([string]$questionResponse.Body.question.state -eq "READY") "New question was not created in READY"
  $questionId = [string]$questionResponse.Body.question.id

  foreach ($invalidLimit in $invalidAnswerTimeLimits) {
    $invalidLimitUpdate = Invoke-ApiJson -Method "PUT" -Path "/api/questions" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      questionId = $questionId
      answerTimeLimitSeconds = $invalidLimit.Value
    }
    Assert-True ($invalidLimitUpdate.StatusCode -eq 400) "Question update accepted $($invalidLimit.Name) answerTimeLimitSeconds"
  }
  $questionAfterInvalidLimits = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $questionId
  Assert-True ([string]$questionAfterInvalidLimits.answerTimeLimitSeconds -eq "30") "Invalid limit update changed the stored answer time limit"

  Push-Location $repoRoot
  try {
    Invoke-TestCompose -ComposeArguments @(
      "exec", "-T", "postgres", "psql", "-U", "classroom", "-d", "classroom",
      "-v", "ON_ERROR_STOP=1", "-c",
      "UPDATE questions SET answer_time_limit_seconds = 0 WHERE id = '$questionId'::uuid;"
    ) | Out-Null
  }
  finally {
    Pop-Location
  }
  $legacyLimitQuestion = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $questionId
  Assert-True ([string]$legacyLimitQuestion.answerTimeLimitSeconds -eq "0") "Legacy non-positive answer limit was not readable"
  $legacyPreservingUpdate = Invoke-ApiJson -Method "PUT" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $questionId
    score = 10
  }
  Assert-True ($legacyPreservingUpdate.StatusCode -eq 200) "Update without an answer limit rejected a readable legacy value"
  Assert-True ([string]$legacyPreservingUpdate.Body.question.answerTimeLimitSeconds -eq "0") "Update without an answer limit changed a legacy value"
  $restorePositiveLimit = Invoke-ApiJson -Method "PUT" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $questionId
    answerTimeLimitSeconds = 30
  }
  Assert-True ($restorePositiveLimit.StatusCode -eq 200) "Positive answer time limit update failed"

  $invalidTransition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $questionId
    targetState = "PUBLISHED"
  }
  Assert-True ($invalidTransition.StatusCode -eq 400) "Invalid transition should fail"

  foreach ($targetState in @("ANSWER_OPEN")) {
    $transition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      questionId = $questionId
      targetState = $targetState
    }
    Assert-True ($transition.StatusCode -eq 200) "Transition to $targetState failed"
  }

  foreach ($token in @($studentOneToken, $studentTwoToken, $assistantToken)) {
    $joinResponse = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/join" -Token $token -Body @{
      classroomCode = $classroomCode
    }
    Assert-True ($joinResponse.StatusCode -eq 200) "Classroom join failed"
  }

  $invalidPackage = Invoke-ApiJson -Method "POST" -Path "/api/question-packages" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    title = "Invalid duplicate cells"
    sheetConfigId = $sheetConfigId
    items = @(
      @{ prompt = "One"; targetCell = "B1" },
      @{ prompt = "Two"; targetCell = "b1" }
    )
  }
  Assert-True ($invalidPackage.StatusCode -eq 400) "Duplicate normalized cells should be rejected"

  $packageResponse = Invoke-ApiJson -Method "POST" -Path "/api/question-packages" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    title = "Two-part package"
    sheetConfigId = $sheetConfigId
    items = @(
      @{ prompt = "Part one"; targetCell = "B1" },
      @{ prompt = "Part two"; targetCell = "B2" }
    )
    score = 10
    answerTimeLimitSeconds = 30
  }
  Assert-True ($packageResponse.StatusCode -eq 201) "Question package creation failed"
  $packageId = [string]$packageResponse.Body.package.packageId
  $packageItems = @($packageResponse.Body.package.items)
  Assert-True ($packageItems.Count -eq 2) "Question package did not preserve both items"

  $packageTransition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $packageId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($packageTransition.StatusCode -eq 200) "Question package did not open for buzzing"

  $itemBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = [string]$packageItems[0].id
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($itemBuzz.StatusCode -eq 400) "Subquestion was incorrectly accepted as a buzz target"

  $packageBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $packageId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($packageBuzz.StatusCode -eq 200 -and $packageBuzz.Body.accepted) "Package winner was not accepted"

  $nonWinnerPackageAnswer = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    packageId = $packageId
    answers = @(
      @{ itemId = [string]$packageItems[0].id; answer = "Not winner one" },
      @{ itemId = [string]$packageItems[1].id; answer = "Not winner two" }
    )
  }
  Assert-True ($nonWinnerPackageAnswer.StatusCode -eq 403) "Non-winner package submission should be rejected"

  $missingPackageAnswer = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    packageId = $packageId
    answers = @(@{ itemId = [string]$packageItems[0].id; answer = "Only one" })
  }
  Assert-True ($missingPackageAnswer.StatusCode -eq 400) "Incomplete package answer should be rejected"

  $packageSubmission = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    packageId = $packageId
    answers = @(
      @{ itemId = [string]$packageItems[0].id; answer = "First answer" },
      @{ itemId = [string]$packageItems[1].id; answer = "Second answer" }
    )
  }
  Assert-True ($packageSubmission.StatusCode -eq 200) "Complete package submission failed"
  Assert-True (@("SYNCED", "RETRYABLE", "NOT_REQUIRED") -contains [string]$packageSubmission.Body.syncStatus) "Package sync status was not reported"

  $packageReset = Invoke-ApiJson -Method "POST" -Path "/api/buzz/reset" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $packageId
  }
  Assert-True ($packageReset.StatusCode -eq 200) "Submitted package reset failed"
  $packageReopen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $packageId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($packageReopen.StatusCode -eq 200) "Reset package could not reopen"
  $secondPackageBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $packageId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($secondPackageBuzz.StatusCode -eq 200 -and $secondPackageBuzz.Body.accepted) "Second package round winner was not accepted"
  $secondPackageSubmission = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    packageId = $packageId
    answers = @(
      @{ itemId = [string]$packageItems[0].id; answer = "Second round one" },
      @{ itemId = [string]$packageItems[1].id; answer = "Second round two" }
    )
  }
  Assert-True ($secondPackageSubmission.StatusCode -eq 200) "Second package round submission failed"
  $packageAnswerObject = @{}
  $packageAnswerObject[[string]$packageItems[0].id] = "Updated one"
  $packageAnswerObject[[string]$packageItems[1].id] = "Updated two"
  $packageTeacherUpdate = Invoke-ApiJson -Method "POST" -Path "/api/submissions/answer" -Token $teacherToken -Body @{
    submissionId = [string]$secondPackageSubmission.Body.submissionId
    answer = ($packageAnswerObject | ConvertTo-Json -Compress)
    groupNumber = 6
  }
  Assert-True ($packageTeacherUpdate.StatusCode -eq 200) "Teacher package answer update failed"
  Assert-True (@("SYNCED", "NOT_REQUIRED", "RETRYABLE") -contains [string]$packageTeacherUpdate.Body.syncStatus) "Package update omitted Sheets sync status"

  $firstSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $questionId
    answer = "A"
  }
  Assert-True ($firstSubmission.StatusCode -eq 200) "Correct submission failed"

  $duplicateSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $questionId
    answer = "A"
  }
  Assert-True ($duplicateSubmission.StatusCode -eq 409 -or $duplicateSubmission.StatusCode -eq 400) "Duplicate submission should be rejected"

  $wrongSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $questionId
    answer = "B"
  }
  Assert-True ($wrongSubmission.StatusCode -eq 200) "Wrong submission request failed"

  foreach ($token in @($teacherToken, $assistantToken, $studentOneToken, $studentTwoToken)) {
    $answersResponse = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$questionId" -Token $token -Body $null
    Assert-True ($answersResponse.StatusCode -eq 200) "Classroom member could not read current answers"
    Assert-True (@($answersResponse.Body.answers).Count -eq 2) "Current answers did not include both submissions"
    Assert-True ($null -eq $answersResponse.Body.answers[0].submittedAt) "Current answers exposed submittedAt"
    Assert-True ($null -eq $answersResponse.Body.answers[0].roundId) "Current answers exposed roundId"
    $ownedAnswerCount = @($answersResponse.Body.answers | Where-Object { $_.isOwnSubmission -eq $true }).Count
    $expectedOwnedAnswerCount = if ($token -eq $studentOneToken -or $token -eq $studentTwoToken) { 1 } else { 0 }
    Assert-True ($ownedAnswerCount -eq $expectedOwnedAnswerCount) "Current answers did not identify requester ownership correctly"
  }
  $invalidGroupUpdate = Invoke-ApiJson -Method "POST" -Path "/api/submissions/answer" -Token $teacherToken -Body @{
    submissionId = [string]$firstSubmission.Body.submissionId
    answer = "Teacher corrected"
    groupNumber = 0
  }
  Assert-True ($invalidGroupUpdate.StatusCode -eq 400) "Zero group number should be rejected"
  $validGroupUpdate = Invoke-ApiJson -Method "POST" -Path "/api/submissions/answer" -Token $teacherToken -Body @{
    submissionId = [string]$firstSubmission.Body.submissionId
    answer = "Teacher corrected"
    groupNumber = 7
  }
  Assert-True ($validGroupUpdate.StatusCode -eq 200) "Teacher answer/group update failed"
  Assert-True (@("SYNCED", "NOT_REQUIRED", "RETRYABLE") -contains [string]$validGroupUpdate.Body.syncStatus) "Answer update omitted Sheets sync status"
  if ([string]$validGroupUpdate.Body.syncStatus -eq "RETRYABLE") {
    $retryAnswerSync = Invoke-ApiJson -Method "POST" -Path "/api/submissions/answer/retry" -Token $teacherToken -Body @{
      submissionId = [string]$firstSubmission.Body.submissionId
    }
    Assert-True ($retryAnswerSync.StatusCode -eq 200) "Retrying persisted answer sync failed"
    Assert-True (@("SYNCED", "NOT_REQUIRED", "RETRYABLE") -contains [string]$retryAnswerSync.Body.syncStatus) "Retry omitted Sheets sync status"
  }
  $updatedAnswers = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$questionId" -Token $teacherToken -Body $null
  $updatedAnswer = @($updatedAnswers.Body.answers | Where-Object { $_.submissionId -eq [string]$firstSubmission.Body.submissionId })[0]
  Assert-True ([int]$updatedAnswer.groupNumber -eq 7) "Answer group snapshot was not updated"
  Assert-True ([string]$updatedAnswer.displayName -eq "Student One") "Editing the answer group changed student identity"
  $otherClassroom = Invoke-ApiJson -Method "POST" -Path "/api/classrooms" -Token $teacherToken -Body @{
    name = "Other Validation Room"
    participationSettings = '{"buzzBonus":5,"wrongAnswerPenalty":2}'
  }
  $nonMemberJoin = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/join" -Token $nonMemberToken -Body @{
    classroomCode = [string]$otherClassroom.Body.classroom.code
  }
  Assert-True ($nonMemberJoin.StatusCode -eq 200) "Non-member setup failed"
  $nonMemberAnswers = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$questionId" -Token $nonMemberToken -Body $null
  Assert-True ($nonMemberAnswers.StatusCode -eq 403) "Authenticated non-member should not read classroom answers"

  $crossClassroomAnswers = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$($otherClassroom.Body.classroom.code)&questionId=$questionId" -Token $teacherToken -Body $null
  Assert-True ($crossClassroomAnswers.StatusCode -eq 400) "Cross-classroom question lookup should be rejected"

  $manualQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Manual answer validation"
    type = "BUZZ"
    score = 0
    answerKey = ""
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "D1" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 30
  }
  Assert-True ($manualQuestion.StatusCode -eq 201) "Manual-answer question setup failed"
  $manualQuestionId = [string]$manualQuestion.Body.question.id
  $manualWhileOpen = Invoke-ApiJson -Method "POST" -Path "/api/submissions/manual" -Token $teacherToken -Body @{
    classroomCode = $classroomCode; questionId = $manualQuestionId; answer = "manual"; groupNumber = 3
  }
  Assert-True ($manualWhileOpen.StatusCode -eq 400) "Manual answer should be rejected before the question is stopped"
  foreach ($targetState in @("BUZZ_OPEN", "LOCKED")) {
    $manualTransition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
      classroomCode = $classroomCode; questionId = $manualQuestionId; targetState = $targetState
    }
    Assert-True ($manualTransition.StatusCode -eq 200) "Manual-answer question transition failed"
  }
  $manualCreated = Invoke-ApiJson -Method "POST" -Path "/api/submissions/manual" -Token $teacherToken -Body @{
    classroomCode = $classroomCode; questionId = $manualQuestionId; answer = "manual"; groupNumber = 3
  }
  Assert-True ($manualCreated.StatusCode -eq 200) "Stopped-round manual answer could not be created"
  $manualAnswers = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$manualQuestionId" -Token $teacherToken -Body $null
  Assert-True (@($manualAnswers.Body.answers).Count -eq 1) "Manual answer was not current-round visible"
  Assert-True ($manualAnswers.Body.answers[0].enteredByTeacher -eq $true) "Manual answer provenance was not retained"
  Assert-True ([int]$manualAnswers.Body.answers[0].groupNumber -eq 3) "Manual answer group was not retained"

  $buzzQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Buzz now"
    type = "BUZZ"
    score = 0
    answerKey = "buzz"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "A2" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 10
  }
  $buzzQuestionId = [string]$buzzQuestion.Body.question.id

  foreach ($targetState in @("BUZZ_OPEN")) {
    $transition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      questionId = $buzzQuestionId
      targetState = $targetState
    }
    Assert-True ($transition.StatusCode -eq 200) "Buzz question transition to $targetState failed"
  }

  $buzzTokens = @($studentOneToken, $studentTwoToken)
  for ($index = 3; $index -le 10; $index++) {
    $token = New-Token -DisplayName "Buzz $index" -Role "STUDENT" -UserId "buzz-$index"
    $joinResponse = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/join" -Token $token -Body @{ classroomCode = $classroomCode }
    Assert-True ($joinResponse.StatusCode -eq 200) "Buzz student join failed"
    $buzzTokens += $token
  }

  $buzzJobs = foreach ($token in $buzzTokens) {
    Start-Job -ScriptBlock {
      param($activeToken, $activeClassroomCode, $activeQuestionId, $activeApiPort)
      $headers = @{ Authorization = "Bearer $activeToken" }
      $body = @{ classroomCode = $activeClassroomCode; questionId = $activeQuestionId; requestId = [guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
      try {
        $response = Invoke-WebRequest -Uri "http://localhost:$activeApiPort/api/buzz" -Method POST -Headers $headers -Body $body -ContentType "application/json" -UseBasicParsing -TimeoutSec 10
        return $response.Content | ConvertFrom-Json
      } catch {
        return [pscustomobject]@{ accepted = $false; outcomeStatus = "ERROR" }
      }
    } -ArgumentList $token, $classroomCode, $buzzQuestionId, $script:ApiTestPort
  }

  $completedBuzzJobs = @($buzzJobs | Wait-Job -Timeout 20)
  if ($completedBuzzJobs.Count -ne $buzzJobs.Count) {
    $buzzJobs | Where-Object { $_.State -eq "Running" } | Stop-Job
    $buzzJobs | Remove-Job -Force
    throw "Concurrent buzz jobs did not finish within 20 seconds"
  }
  $buzzResults = $buzzJobs | Receive-Job
  $buzzJobs | Remove-Job
  $winnerCount = @($buzzResults | Where-Object { $_.accepted -eq $true }).Count
  Assert-True ($winnerCount -eq 1) "Expected exactly one buzz winner, got $winnerCount"

  $roundQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Round lifecycle"
    type = "BUZZ"
    score = 0
    answerKey = "round"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "A3" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 10
  }
  $roundQuestionId = [string]$roundQuestion.Body.question.id
  foreach ($targetState in @("BUZZ_OPEN")) {
    $transition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      questionId = $roundQuestionId
      targetState = $targetState
    }
    Assert-True ($transition.StatusCode -eq 200) "Round question transition to $targetState failed"
  }

  $firstBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($firstBuzz.StatusCode -eq 200 -and $firstBuzz.Body.accepted) "First round winner was not accepted"
  $nonWinnerCancel = Invoke-ApiJson -Method "POST" -Path "/api/buzz/cancel" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
  }
  Assert-True ($nonWinnerCancel.StatusCode -eq 403) "Non-winner cancellation should be rejected"
  $cancel = Invoke-ApiJson -Method "POST" -Path "/api/buzz/cancel" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
  }
  Assert-True ($cancel.StatusCode -eq 200) "Unsubmitted winner could not cancel"

  $replacementBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($replacementBuzz.StatusCode -eq 200 -and $replacementBuzz.Body.accepted) "Replacement winner was not accepted: status=$($replacementBuzz.StatusCode) body=$($replacementBuzz.Body | ConvertTo-Json -Compress)"
  $replacementSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    answer = "replacement"
  }
  Assert-True ($replacementSubmission.StatusCode -eq 200) "Replacement winner could not submit"
  $cancelAfterSubmit = Invoke-ApiJson -Method "POST" -Path "/api/buzz/cancel" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
  }
  Assert-True ($cancelAfterSubmit.StatusCode -eq 400 -or $cancelAfterSubmit.StatusCode -eq 409) "Submitted winner cancellation should be rejected"

  $reset = Invoke-ApiJson -Method "POST" -Path "/api/buzz/reset" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
  }
  Assert-True ($reset.StatusCode -eq 200) "Teacher reset failed"
  $reopen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($reopen.StatusCode -eq 200) "Reset question could not reopen"
  $secondRoundBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($secondRoundBuzz.StatusCode -eq 200 -and $secondRoundBuzz.Body.accepted) "Previous winner could not win a new round"
  $secondRoundSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    answer = "second round"
  }
  Assert-True ($secondRoundSubmission.StatusCode -eq 200) "Previous submitter could not submit in a new round"

  $teacherQuestions = Invoke-ApiJson -Method "GET" -Path "/api/questions?classroomCode=$classroomCode" -Token $teacherToken -Body $null
  $roundQuestionView = @($teacherQuestions.Body.questions | Where-Object { [string]$_.id -eq $roundQuestionId })[0]
  Assert-True ([string]$roundQuestionView.buzzStatus -eq "SUBMITTED_PENDING_REVIEW") "Teacher current-round status did not recover submitted state"
  Assert-True (-not [string]::IsNullOrWhiteSpace([string]$roundQuestionView.winnerDisplayName)) "Teacher current-round status omitted winner display name"

  $timedLegacyQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Timed legacy route"
    type = "BUZZ"
    score = 0
    answerKey = "timed"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "T1" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 1
  }
  Assert-True ($timedLegacyQuestion.StatusCode -eq 201) "Timed legacy question creation failed"
  $timedLegacyQuestionId = [string]$timedLegacyQuestion.Body.question.id
  $timedLegacyOpen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedLegacyQuestionId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($timedLegacyOpen.StatusCode -eq 200) "Timed legacy question did not open"
  $timedLegacyBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedLegacyQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($timedLegacyBuzz.StatusCode -eq 200 -and $timedLegacyBuzz.Body.accepted) "Timed legacy winner was not accepted"
  $timedLegacyWinnerView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedLegacyQuestionId
  $timedLegacyRound = [string]$timedLegacyWinnerView.roundId
  Assert-True ([string]$timedLegacyWinnerView.buzzStatus -eq "WINNER_ANSWERING") "Timed legacy winner was not visible"

  $timedLegacyExpiredView = Wait-ForQuestionBuzzStatus -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedLegacyQuestionId -ExpectedStatus "WAITING_FOR_BUZZ"
  Assert-True ([string]$timedLegacyExpiredView.state -eq "BUZZ_OPEN") "Timeout changed the legacy question lifecycle state"
  Assert-True ([string]$timedLegacyExpiredView.roundId -eq $timedLegacyRound) "Timeout advanced the legacy question round"
  $timedLegacyTimeoutEvent = Wait-ForSingleTimeoutEvent -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedLegacyQuestionId
  Assert-True ([string]$timedLegacyTimeoutEvent.payload.roundId -eq $timedLegacyRound) "Legacy timeout event used the wrong round"
  Assert-True (-not [string]::IsNullOrWhiteSpace([string]$timedLegacyTimeoutEvent.payload.answerDeadlineAt)) "Legacy timeout event omitted its deadline"
  $timedLegacyHistory = Invoke-ApiJson -Method "GET" -Path "/api/buzz/history?classroomCode=$classroomCode&questionId=$timedLegacyQuestionId" -Token $teacherToken -Body $null
  Assert-True ($timedLegacyHistory.StatusCode -eq 200) "Timed legacy buzz history could not be loaded"
  Assert-True (@($timedLegacyHistory.Body.history | Where-Object { [string]$_.outcomeStatus -eq "TIMED_OUT" }).Count -eq 1) "Legacy timeout history was not recorded exactly once"

  $lateLegacySubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedLegacyQuestionId
    answer = "too late"
  }
  Assert-True ($lateLegacySubmission.StatusCode -eq 400) "Late legacy submission was not rejected as expired"
  $lateLegacyAnswers = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$timedLegacyQuestionId" -Token $teacherToken -Body $null
  Assert-True ($lateLegacyAnswers.StatusCode -eq 200 -and @($lateLegacyAnswers.Body.answers).Count -eq 0) "Late legacy answer was persisted"
  Start-Sleep -Milliseconds 1200
  Assert-True (@(Get-TimeoutEvents -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedLegacyQuestionId).Count -eq 1) "Repeated sweeps duplicated the legacy timeout event"

  $timedLegacyReplacementBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedLegacyQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($timedLegacyReplacementBuzz.StatusCode -eq 200 -and $timedLegacyReplacementBuzz.Body.accepted) "Another student could not rebuzz the timed-out legacy question"
  $timedLegacyReplacementView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedLegacyQuestionId
  Assert-True ([string]$timedLegacyReplacementView.roundId -eq $timedLegacyRound) "Replacement legacy winner was not in the same round"
  $timedLegacyOnTimeSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedLegacyQuestionId
    answer = "on time"
  }
  Assert-True ($timedLegacyOnTimeSubmission.StatusCode -eq 200) "On-time replacement legacy submission failed"
  Start-Sleep -Milliseconds 1500
  $timedLegacySubmittedView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedLegacyQuestionId
  Assert-True ([string]$timedLegacySubmittedView.buzzStatus -eq "SUBMITTED_PENDING_REVIEW") "On-time legacy submission was expired later"
  Assert-True (@(Get-TimeoutEvents -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedLegacyQuestionId).Count -eq 1) "On-time replacement created an extra timeout event"

  $timedPackage = Invoke-ApiJson -Method "POST" -Path "/api/question-packages" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    title = "Timed package route"
    sheetConfigId = $sheetConfigId
    items = @(
      @{ prompt = "Timed package one"; targetCell = "T2" },
      @{ prompt = "Timed package two"; targetCell = "T3" }
    )
    score = 0
    answerTimeLimitSeconds = 1
  }
  Assert-True ($timedPackage.StatusCode -eq 201) "Timed package creation failed"
  $timedPackageId = [string]$timedPackage.Body.package.packageId
  $timedPackageItems = @($timedPackage.Body.package.items)
  $timedPackageOpen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedPackageId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($timedPackageOpen.StatusCode -eq 200) "Timed package did not open"
  $timedPackageBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedPackageId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($timedPackageBuzz.StatusCode -eq 200 -and $timedPackageBuzz.Body.accepted) "Timed package winner was not accepted"
  $timedPackageWinnerView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedPackageId
  $timedPackageRound = [string]$timedPackageWinnerView.roundId
  $timedPackageExpiredView = Wait-ForQuestionBuzzStatus -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedPackageId -ExpectedStatus "WAITING_FOR_BUZZ"
  Assert-True ([string]$timedPackageExpiredView.state -eq "BUZZ_OPEN") "Timeout changed the package lifecycle state"
  Assert-True ([string]$timedPackageExpiredView.roundId -eq $timedPackageRound) "Timeout advanced the package round"
  [void](Wait-ForSingleTimeoutEvent -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedPackageId)

  $latePackageSubmission = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    packageId = $timedPackageId
    answers = @(
      @{ itemId = [string]$timedPackageItems[0].id; answer = "too late one" },
      @{ itemId = [string]$timedPackageItems[1].id; answer = "too late two" }
    )
  }
  Assert-True ($latePackageSubmission.StatusCode -eq 400) "Late package submission was not rejected as expired"
  $latePackageAnswers = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$timedPackageId" -Token $teacherToken -Body $null
  Assert-True ($latePackageAnswers.StatusCode -eq 200 -and @($latePackageAnswers.Body.answers).Count -eq 0) "Late package answer was persisted"
  $timedPackageReplacementBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $timedPackageId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($timedPackageReplacementBuzz.StatusCode -eq 200 -and $timedPackageReplacementBuzz.Body.accepted) "Another student could not rebuzz the timed-out package"
  $timedPackageReplacementView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedPackageId
  Assert-True ([string]$timedPackageReplacementView.roundId -eq $timedPackageRound) "Replacement package winner was not in the same round"
  $timedPackageOnTimeSubmission = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    packageId = $timedPackageId
    answers = @(
      @{ itemId = [string]$timedPackageItems[0].id; answer = "on time one" },
      @{ itemId = [string]$timedPackageItems[1].id; answer = "on time two" }
    )
  }
  Assert-True ($timedPackageOnTimeSubmission.StatusCode -eq 200) "On-time replacement package submission failed"
  Start-Sleep -Milliseconds 1500
  $timedPackageSubmittedView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedPackageId
  Assert-True ([string]$timedPackageSubmittedView.buzzStatus -eq "SUBMITTED_PENDING_REVIEW") "On-time package submission was expired later"
  Assert-True (@(Get-TimeoutEvents -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $timedPackageId).Count -eq 1) "On-time package replacement created an extra timeout event"

  $boundaryQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Submit timeout boundary"
    type = "BUZZ"
    score = 0
    answerKey = "boundary"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "T4" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 1
  }
  Assert-True ($boundaryQuestion.StatusCode -eq 201) "Boundary question creation failed"
  $boundaryQuestionId = [string]$boundaryQuestion.Body.question.id
  $boundaryOpen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $boundaryQuestionId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($boundaryOpen.StatusCode -eq 200) "Boundary question did not open"
  $boundaryBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $boundaryQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($boundaryBuzz.StatusCode -eq 200 -and $boundaryBuzz.Body.accepted) "Boundary winner was not accepted"
  $boundarySubmitJob = Start-Job -ScriptBlock {
    param($Token, $ClassroomCode, $QuestionId, $ApiPort)
    Start-Sleep -Milliseconds 900
    $headers = @{ Authorization = "Bearer $Token" }
    $body = @{ classroomCode = $ClassroomCode; questionId = $QuestionId; answer = "boundary" } | ConvertTo-Json -Compress
    try {
      $response = Invoke-WebRequest -Uri "http://localhost:$ApiPort/api/submissions" -Method POST -Headers $headers -Body $body -ContentType "application/json" -UseBasicParsing -TimeoutSec 10
      return [pscustomobject]@{ StatusCode = [int]$response.StatusCode }
    }
    catch {
      return [pscustomobject]@{ StatusCode = [int]$_.Exception.Response.StatusCode }
    }
  } -ArgumentList $studentOneToken, $classroomCode, $boundaryQuestionId, $script:ApiTestPort
  $boundarySubmitResult = $boundarySubmitJob | Receive-Job -Wait -AutoRemoveJob
  Assert-True (@(200, 400) -contains [int]$boundarySubmitResult.StatusCode) "Boundary submission produced an invalid race outcome"
  Start-Sleep -Milliseconds 1500
  $boundaryView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $boundaryQuestionId
  $boundaryTimeoutEvents = @(Get-TimeoutEvents -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $boundaryQuestionId)
  if ([int]$boundarySubmitResult.StatusCode -eq 200) {
    Assert-True ([string]$boundaryView.buzzStatus -eq "SUBMITTED_PENDING_REVIEW") "Accepted boundary submission was later expired"
    Assert-True ($boundaryTimeoutEvents.Count -eq 0) "Accepted boundary submission also committed a timeout"
  }
  else {
    Assert-True ([string]$boundaryView.buzzStatus -eq "WAITING_FOR_BUZZ") "Expired boundary winner remained active"
    Assert-True ($boundaryTimeoutEvents.Count -eq 1) "Expired boundary winner did not publish exactly one timeout"
    $boundaryReplacementBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
      classroomCode = $classroomCode
      questionId = $boundaryQuestionId
      requestId = [guid]::NewGuid().ToString()
    }
    Assert-True ($boundaryReplacementBuzz.StatusCode -eq 200 -and $boundaryReplacementBuzz.Body.accepted) "Boundary timeout left the question stuck"
    $boundaryReplacementSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentTwoToken -Body @{
      classroomCode = $classroomCode
      questionId = $boundaryQuestionId
      answer = "replacement"
    }
    Assert-True ($boundaryReplacementSubmission.StatusCode -eq 200) "Boundary replacement could not submit"
  }

  $restartQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Restart timeout recovery"
    type = "BUZZ"
    score = 0
    answerKey = "restart"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "T5" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 1
  }
  Assert-True ($restartQuestion.StatusCode -eq 201) "Restart recovery question creation failed"
  $restartQuestionId = [string]$restartQuestion.Body.question.id
  $restartOpen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $restartQuestionId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($restartOpen.StatusCode -eq 200) "Restart recovery question did not open"
  $restartBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $restartQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($restartBuzz.StatusCode -eq 200 -and $restartBuzz.Body.accepted) "Restart recovery winner was not accepted"
  $restartRound = [string](Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $restartQuestionId).roundId
  Stop-ApiProcess -Process $apiProcess
  $apiProcess = $null
  Start-Sleep -Milliseconds 1500
  $apiProcess = Start-ApiProcess -ProjectRoot $projectRoot
  Wait-ForApi
  Wait-ForPort -HostName "127.0.0.1" -Port $script:ApiTestWebSocketPort
  $restartExpiredView = Get-QuestionView -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $restartQuestionId
  Assert-True ([string]$restartExpiredView.buzzStatus -eq "WAITING_FOR_BUZZ") "Startup recovery did not expire the overdue winner before traffic"
  Assert-True ([string]$restartExpiredView.state -eq "BUZZ_OPEN") "Startup recovery changed the question lifecycle state"
  Assert-True ([string]$restartExpiredView.roundId -eq $restartRound) "Startup recovery advanced the question round"
  [void](Wait-ForSingleTimeoutEvent -Token $teacherToken -ClassroomCode $classroomCode -QuestionId $restartQuestionId)
  $restartReplacementBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $restartQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($restartReplacementBuzz.StatusCode -eq 200 -and $restartReplacementBuzz.Body.accepted) "Startup-recovered question did not accept a replacement winner"
  $restartReplacementSubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $restartQuestionId
    answer = "recovered"
  }
  Assert-True ($restartReplacementSubmission.StatusCode -eq 200) "Startup-recovered replacement could not submit"

  $unauthorizedBatch = Invoke-ApiJson -Method "POST" -Path "/api/questions/batch" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    action = "STOP_ALL"
  }
  Assert-True ($unauthorizedBatch.StatusCode -eq 403) "Student should not be authorized for batch controls"

  $stopAll = Invoke-ApiJson -Method "POST" -Path "/api/questions/batch" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    action = "STOP_ALL"
  }
  Assert-True ($stopAll.StatusCode -eq 200) "STOP_ALL failed"
  Assert-True (@($stopAll.Body.results | Where-Object { $_.result -eq "CHANGED" }).Count -ge 1) "STOP_ALL did not change an active question"
  $buzzAfterStop = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($buzzAfterStop.StatusCode -eq 400) "Stopped question still accepted buzz requests"

  $resetAll = Invoke-ApiJson -Method "POST" -Path "/api/questions/batch" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    action = "RESET_ALL"
  }
  Assert-True ($resetAll.StatusCode -eq 200) "RESET_ALL failed"
  $openAll = Invoke-ApiJson -Method "POST" -Path "/api/questions/batch" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    action = "OPEN_ALL"
  }
  Assert-True ($openAll.StatusCode -eq 200) "OPEN_ALL failed"
  Assert-True (@($openAll.Body.results | Where-Object { $_.result -eq "CHANGED" }).Count -ge 1) "OPEN_ALL did not open any ready questions"
  $openAllRetry = Invoke-ApiJson -Method "POST" -Path "/api/questions/batch" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    action = "OPEN_ALL"
  }
  Assert-True (@($openAllRetry.Body.results | Where-Object { $_.result -eq "CHANGED" }).Count -eq 0) "OPEN_ALL retry incorrectly advanced rounds"

  $exportResponse = Invoke-ApiJson -Method "GET" -Path "/api/reports/export?classroomCode=$classroomCode&type=scores" -Token $teacherToken -Body $null
  Assert-True ($exportResponse.StatusCode -eq 200) "Export request failed"
  Assert-True ([string]$exportResponse.Body.content).StartsWith("userId,displayName,sourceType") "Export content is not CSV"

  $auditCount = Query-AuditCount -RepoRoot $repoRoot
  Assert-True ($auditCount -ge 1) "Audit log was not recorded for export"
  $draftCount = Query-QuestionCountByState -RepoRoot $repoRoot -State "DRAFT"
  Assert-True ($draftCount -eq 0) "Legacy DRAFT migration or READY creation left unexpected DRAFT questions"

  Write-Output "Validation suite passed."
}
finally {
  Stop-ApiProcess -Process $apiProcess
  Stop-ComposeInfra -RepoRoot $repoRoot
}
