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
    java -cp $testClassPath com.classroom.api.buzz.BuzzAttemptServiceTest
    if ($LASTEXITCODE -ne 0) {
      throw "Buzz attempt service tests failed with exit code $LASTEXITCODE"
    }
    java -cp $testClassPath com.classroom.api.question.QuestionPackageControllerTest
    if ($LASTEXITCODE -ne 0) {
      throw "Question package answer-limit validation tests failed with exit code $LASTEXITCODE"
    }
    java -cp $testClassPath com.classroom.api.question.SheetMarkerScannerTest
    if ($LASTEXITCODE -ne 0) {
      throw "Sheet marker scanner tests failed with exit code $LASTEXITCODE"
    }
  }

  $apiProcess = Start-ApiProcess -ProjectRoot $projectRoot
  Wait-ForApi
  Wait-ForPort -HostName "127.0.0.1" -Port $script:ApiTestWebSocketPort

  $teacherToken = New-Token -DisplayName "Teacher QA" -Role "TEACHER" -UserId "teacher-qa"
  $studentOneToken = New-Token -DisplayName "Student One / 1" -Role "STUDENT" -UserId "student-one"
  $studentTwoToken = New-Token -DisplayName "Student Two / 2" -Role "STUDENT" -UserId "student-two"
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

  $studentAnswerLimitReset = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/answer-limits/reset" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
  }
  Assert-True ($studentAnswerLimitReset.StatusCode -eq 403) "Student reset all group answer limits"

  Set-GlobalGroupBuzzCountForValidation -RepoRoot $repoRoot -GroupNumber 91 -DateExpression "((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date)" -Count 1
  Set-GlobalGroupBuzzCountForValidation -RepoRoot $repoRoot -GroupNumber 92 -DateExpression "(((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date) - 1)" -Count 1
  $assistantAnswerLimitReset = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/answer-limits/reset" -Token $assistantToken -Body @{
    classroomCode = $classroomCode
  }
  Assert-True ($assistantAnswerLimitReset.StatusCode -eq 200) "Assistant could not reset answer limits"
  Assert-True ([string]$assistantAnswerLimitReset.Body.status -eq "ANSWER_LIMITS_RESET") "Answer-limit reset returned the wrong status"
  Assert-True ([int]$assistantAnswerLimitReset.Body.resetGroupCount -ge 1) "Answer-limit reset omitted the affected group count"
  Assert-True ((Query-GlobalGroupBuzzCountForValidation -RepoRoot $repoRoot -GroupNumber 91 -DateExpression "((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date)") -eq 0) "Current-day group count survived reset"
  Assert-True ((Query-GlobalGroupBuzzCountForValidation -RepoRoot $repoRoot -GroupNumber 92 -DateExpression "(((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date) - 1)") -eq 1) "Prior-day group count was changed"

  $emptyAnswerLimitReset = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/answer-limits/reset" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
  }
  Assert-True ($emptyAnswerLimitReset.StatusCode -eq 200 -and [int]$emptyAnswerLimitReset.Body.resetGroupCount -eq 0) "Empty answer-limit reset was not idempotent"

  $availableClassrooms = Invoke-ApiJson -Method "GET" -Path "/api/classrooms/available" -Token "" -Body $null
  Assert-True ($availableClassrooms.StatusCode -eq 200) "Unable to load classrooms available to students"
  $availableClassroom = @($availableClassrooms.Body.classrooms | Where-Object { [string]$_.code -eq $classroomCode })[0]
  Assert-True ($null -ne $availableClassroom) "Created classroom was not available to students"
  Assert-True ([string]$availableClassroom.name -eq "Validation Room") "Available classroom omitted its display name"
  Assert-True ($null -eq $availableClassroom.teacherId -and $null -eq $availableClassroom.participationSettings) "Available classroom exposed private fields"

  $ownedClassrooms = Invoke-ApiJson -Method "GET" -Path "/api/classrooms" -Token $teacherToken -Body $null
  Assert-True ($ownedClassrooms.StatusCode -eq 200) "Unable to list teacher-owned classrooms"
  Assert-True (@($ownedClassrooms.Body.classrooms | Where-Object { [string]$_.code -eq $classroomCode }).Count -eq 1) "Created classroom was not listed for its owner"

  $assistantClassrooms = Invoke-ApiJson -Method "GET" -Path "/api/classrooms" -Token $assistantToken -Body $null
  Assert-True ($assistantClassrooms.StatusCode -eq 200) "Unable to list assistant-owned classrooms"
  Assert-True (@($assistantClassrooms.Body.classrooms | Where-Object { [string]$_.code -eq $classroomCode }).Count -eq 0) "Another actor can list a classroom they do not own"

  $nonOwnerDelete = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/delete" -Token $assistantToken -Body @{
    classroomCode = $classroomCode
  }
  Assert-True ($nonOwnerDelete.StatusCode -eq 403) "A non-owner deleted a classroom"

  $disposableClassroom = Invoke-ApiJson -Method "POST" -Path "/api/classrooms" -Token $teacherToken -Body @{
    name = "Disposable Validation Room"
    participationSettings = '{}'
  }
  $disposableClassroomCode = [string]$disposableClassroom.Body.classroom.code
  $deleteClassroomResponse = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/delete" -Token $teacherToken -Body @{
    classroomCode = $disposableClassroomCode
  }
  Assert-True ($deleteClassroomResponse.StatusCode -eq 200) "Classroom owner could not delete a classroom"
  $classroomsAfterDelete = Invoke-ApiJson -Method "GET" -Path "/api/classrooms" -Token $teacherToken -Body $null
  Assert-True (@($classroomsAfterDelete.Body.classrooms | Where-Object { [string]$_.code -eq $disposableClassroomCode }).Count -eq 0) "Deleted classroom remained in the owner's list"

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

  $packageDescriptionUpdate = Invoke-ApiJson -Method "PUT" -Path "/api/question-packages" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    packageId = $packageId
    title = "Updated package title"
    items = @(
      @{ itemId = [string]$packageItems[0].id; prompt = "Updated part one" },
      @{ itemId = [string]$packageItems[1].id; prompt = "Updated part two" }
    )
  }
  Assert-True ($packageDescriptionUpdate.StatusCode -eq 200) "READY package descriptions could not be updated"
  Assert-True ([string]$packageDescriptionUpdate.Body.package.title -eq "Updated package title") "Package title update was not returned"
  Assert-True ([string]$packageDescriptionUpdate.Body.package.items[0].targetCell -eq "B1") "Description update changed the package binding"

  $studentPackageView = Invoke-ApiJson -Method "GET" -Path "/api/question-packages?classroomCode=$classroomCode" -Token $studentOneToken -Body $null
  $studentUpdatedPackage = @($studentPackageView.Body.packages | Where-Object { [string]$_.id -eq $packageId })[0]
  Assert-True ([string]$studentUpdatedPackage.title -eq "Updated package title") "Student did not receive updated descriptions"
  Assert-True ($null -eq $studentUpdatedPackage.sheetConfigId) "Student package response exposed a sheet configuration"
  Assert-True ($null -eq $studentUpdatedPackage.items[0].targetCell) "Student package response exposed a target cell"

  $packageTransition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $packageId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($packageTransition.StatusCode -eq 200) "Question package did not open for buzzing"

  $activePackageDescriptionUpdate = Invoke-ApiJson -Method "PUT" -Path "/api/question-packages" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    packageId = $packageId
    title = "Must not change"
    items = @(
      @{ itemId = [string]$packageItems[0].id; prompt = "Must not change one" },
      @{ itemId = [string]$packageItems[1].id; prompt = "Must not change two" }
    )
  }
  Assert-True ($activePackageDescriptionUpdate.StatusCode -eq 400) "Active package descriptions were editable"

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

  $laterPackageBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $packageId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($laterPackageBuzz.StatusCode -eq 200 -and $laterPackageBuzz.Body.accepted) "Later package buzz was not recorded (status=$($laterPackageBuzz.StatusCode), outcome=$($laterPackageBuzz.Body.outcomeStatus))"

  $nonWinnerPackageAnswer = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    packageId = $packageId
    answers = @(
      @{ itemId = [string]$packageItems[0].id; answer = "Not winner one" },
      @{ itemId = [string]$packageItems[1].id; answer = "Not winner two" }
    )
  }
  Assert-True ($nonWinnerPackageAnswer.StatusCode -eq 200 -and $nonWinnerPackageAnswer.Body.accepted) "Slower package submission was not saved for review"
  Assert-True ([string]$nonWinnerPackageAnswer.Body.outcomeStatus -eq "PENDING_REVIEW") "Submission revealed the result before review"

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
  Assert-True ([string]$packageSubmission.Body.syncStatus -eq "PENDING_REVIEW") "Package answer synchronized before review"

  $packageLock = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode; questionId = $packageId; targetState = "LOCKED"
  }
  Assert-True ($packageLock.StatusCode -eq 200) "Package could not be stopped before review"
  $packageFinalize = Invoke-ApiJson -Method "POST" -Path "/api/submissions/finalize-buzz" -Token $teacherToken -Body @{
    classroomCode = $classroomCode; questionId = $packageId
  }
  Assert-True ($packageFinalize.StatusCode -eq 200) "Package review finalization failed"
  Assert-True ([string]$packageFinalize.Body.winnerSubmissionId -eq [string]$packageSubmission.Body.submissionId) "Fastest submitted package did not win at review"
  Assert-True (@("SYNCED", "RETRYABLE", "NOT_REQUIRED") -contains [string]$packageFinalize.Body.syncStatus) "Finalized package sync status was not reported"

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
  Assert-True ([string]$updatedAnswer.displayName -eq "Student One / 1") "Editing the answer group changed student identity"
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

  Reset-GlobalGroupBuzzCounts -RepoRoot $repoRoot
  $buzzTokens = @($studentOneToken, $studentTwoToken)
  for ($index = 3; $index -le 10; $index++) {
    $token = New-Token -DisplayName "Buzz $index / $index" -Role "STUDENT" -UserId "buzz-$index"
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
  $recordedCount = @($buzzResults | Where-Object { $_.accepted -eq $true -and $_.outcomeStatus -eq "RECORDED" }).Count
  Assert-True ($recordedCount -eq $buzzTokens.Count) "Expected every concurrent buzz attempt to be recorded, got $recordedCount"

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

  Reset-GlobalGroupBuzzCounts -RepoRoot $repoRoot
  $firstBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($firstBuzz.StatusCode -eq 200 -and $firstBuzz.Body.accepted) "First round winner was not accepted"
  $secondOpenBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($secondOpenBuzz.StatusCode -eq 200 -and $secondOpenBuzz.Body.accepted) "Second student's open buzz was not recorded"
  $nonWinnerCancel = Invoke-ApiJson -Method "POST" -Path "/api/buzz/cancel" -Token $studentTwoToken -Body @{
    classroomCode = $classroomCode
    questionId = $roundQuestionId
  }
  Assert-True ($nonWinnerCancel.StatusCode -eq 200) "Student could not cancel their own later attempt"
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
  Reset-GlobalGroupBuzzCounts -RepoRoot $repoRoot
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
  Assert-True ([string]::IsNullOrWhiteSpace([string]$roundQuestionView.winnerDisplayName)) "Pending submission exposed a winner before review"

  $noDeadlineQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "No deadline legacy route"
    type = "BUZZ"
    score = 0
    answerKey = "eventual"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "T1" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 1
  }
  Assert-True ($noDeadlineQuestion.StatusCode -eq 201) "No-deadline legacy question creation failed"
  $noDeadlineQuestionId = [string]$noDeadlineQuestion.Body.question.id
  $noDeadlineOpen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $noDeadlineQuestionId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($noDeadlineOpen.StatusCode -eq 200) "No-deadline legacy question did not open"
  Reset-GlobalGroupBuzzCounts -RepoRoot $repoRoot
  $noDeadlineBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $noDeadlineQuestionId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($noDeadlineBuzz.StatusCode -eq 200 -and $noDeadlineBuzz.Body.accepted) "No-deadline buzz was not recorded"
  $noDeadlineBuzzProperties = @($noDeadlineBuzz.Body.PSObject.Properties.Name)
  Assert-True ($noDeadlineBuzzProperties -notcontains "answerDeadlineAt") "Buzz response unexpectedly included an individual deadline (properties=$($noDeadlineBuzzProperties -join ','), raw=$($noDeadlineBuzz.Raw))"
  Start-Sleep -Milliseconds 1500
  $eventualLegacySubmission = Invoke-ApiJson -Method "POST" -Path "/api/submissions" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $noDeadlineQuestionId
    answer = "eventual"
  }
  Assert-True ($eventualLegacySubmission.StatusCode -eq 200 -and $eventualLegacySubmission.Body.accepted) "Legacy answer was incorrectly expired"

  $noDeadlinePackage = Invoke-ApiJson -Method "POST" -Path "/api/question-packages" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    title = "No deadline package route"
    sheetConfigId = $sheetConfigId
    items = @(
      @{ prompt = "Timed package one"; targetCell = "T2" },
      @{ prompt = "Timed package two"; targetCell = "T3" }
    )
    score = 0
    answerTimeLimitSeconds = 1
  }
  Assert-True ($noDeadlinePackage.StatusCode -eq 201) "No-deadline package creation failed"
  $noDeadlinePackageId = [string]$noDeadlinePackage.Body.package.packageId
  $noDeadlinePackageItems = @($noDeadlinePackage.Body.package.items)
  $noDeadlinePackageOpen = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $noDeadlinePackageId
    targetState = "BUZZ_OPEN"
  }
  Assert-True ($noDeadlinePackageOpen.StatusCode -eq 200) "No-deadline package did not open"
  Reset-GlobalGroupBuzzCounts -RepoRoot $repoRoot
  $noDeadlinePackageBuzz = Invoke-ApiJson -Method "POST" -Path "/api/buzz" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    questionId = $noDeadlinePackageId
    requestId = [guid]::NewGuid().ToString()
  }
  Assert-True ($noDeadlinePackageBuzz.StatusCode -eq 200 -and $noDeadlinePackageBuzz.Body.accepted) "No-deadline package buzz was not recorded"
  Start-Sleep -Milliseconds 1500
  $eventualPackageSubmission = Invoke-ApiJson -Method "POST" -Path "/api/question-packages/answers" -Token $studentOneToken -Body @{
    classroomCode = $classroomCode
    packageId = $noDeadlinePackageId
    answers = @(
      @{ itemId = [string]$noDeadlinePackageItems[0].id; answer = "eventual one" },
      @{ itemId = [string]$noDeadlinePackageItems[1].id; answer = "eventual two" }
    )
  }
  Assert-True ($eventualPackageSubmission.StatusCode -eq 200 -and $eventualPackageSubmission.Body.accepted) "Package answer was incorrectly expired"

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

  $answersBeforeReset = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$roundQuestionId" -Token $teacherToken -Body $null
  $buzzHistoryBeforeReset = Invoke-ApiJson -Method "GET" -Path "/api/buzz/history?classroomCode=$classroomCode&questionId=$roundQuestionId" -Token $teacherToken -Body $null
  Assert-True (@($answersBeforeReset.Body.answers).Count -ge 1) "RESET_ALL fixture did not contain an answer record"
  Assert-True (@($buzzHistoryBeforeReset.Body.history).Count -ge 1) "RESET_ALL fixture did not contain a buzz record"

  $resetAll = Invoke-ApiJson -Method "POST" -Path "/api/questions/batch" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    action = "RESET_ALL"
  }
  Assert-True ($resetAll.StatusCode -eq 200) "RESET_ALL failed"
  $answersAfterReset = Invoke-ApiJson -Method "GET" -Path "/api/submissions?classroomCode=$classroomCode&questionId=$roundQuestionId" -Token $teacherToken -Body $null
  $buzzHistoryAfterReset = Invoke-ApiJson -Method "GET" -Path "/api/buzz/history?classroomCode=$classroomCode&questionId=$roundQuestionId" -Token $teacherToken -Body $null
  Assert-True (@($answersAfterReset.Body.answers).Count -eq 0) "RESET_ALL did not delete answer records"
  Assert-True (@($buzzHistoryAfterReset.Body.history).Count -eq 0) "RESET_ALL did not delete buzz records"
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

  $deleteSheetConfigResponse = Invoke-ApiJson -Method "POST" -Path "/api/sheet-configs/delete" -Token $teacherToken -Body @{
    configId = $sheetConfigId
  }
  Assert-True ($deleteSheetConfigResponse.StatusCode -eq 200) "Referenced Sheet configuration could not be deleted"
  Assert-True ([int]$deleteSheetConfigResponse.Body.deletedPackageCount -ge 1) "Sheet configuration deletion did not report dependent packages"
  $sheetConfigsAfterDelete = Invoke-ApiJson -Method "GET" -Path "/api/sheet-configs" -Token $teacherToken -Body $null
  Assert-True (@($sheetConfigsAfterDelete.Body.configs | Where-Object { [string]$_.id -eq $sheetConfigId }).Count -eq 0) "Deleted Sheet configuration remained listed"
  $packagesAfterSheetDelete = Invoke-ApiJson -Method "GET" -Path "/api/question-packages?classroomCode=$classroomCode" -Token $teacherToken -Body $null
  Assert-True (@($packagesAfterSheetDelete.Body.packages | Where-Object { [string]$_.id -eq $packageId }).Count -eq 0) "Package depending on deleted Sheet configuration remained listed"

  Write-Output "Validation suite passed."
}
finally {
  Stop-ApiProcess -Process $apiProcess
  Stop-ComposeInfra -RepoRoot $repoRoot
}
