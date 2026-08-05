$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "test-common.ps1")

$projectRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $projectRoot
$apiProcess = $null

try {
  & (Join-Path $PSScriptRoot "build.ps1")
  Start-ComposeInfra -RepoRoot $repoRoot
  Wait-ForInfra -RepoRoot $repoRoot
  $apiProcess = Start-ApiProcess -ProjectRoot $projectRoot
  Wait-ForApi
  Wait-ForPort -HostName "127.0.0.1" -Port 8081

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
      param($activeToken, $activeClassroomCode, $activeQuestionId)
      $headers = @{ Authorization = "Bearer $activeToken" }
      $body = @{ classroomCode = $activeClassroomCode; questionId = $activeQuestionId; requestId = [guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
      try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/api/buzz" -Method POST -Headers $headers -Body $body -ContentType "application/json" -UseBasicParsing -TimeoutSec 10
        return $response.Content | ConvertFrom-Json
      } catch {
        return [pscustomobject]@{ accepted = $false; outcomeStatus = "ERROR" }
      }
    } -ArgumentList $token, $classroomCode, $buzzQuestionId
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
