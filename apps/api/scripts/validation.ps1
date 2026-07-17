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

  $unauthorized = Invoke-ApiJson -Method "POST" -Path "/api/classrooms" -Token $studentOneToken -Body @{
    name = "Forbidden Room"
    leaderboardVisibility = "TOP_10"
    participationSettings = '{"buzzBonus":5,"wrongAnswerPenalty":2}'
  }
  Assert-True ($unauthorized.StatusCode -eq 403) "Authorization test failed"

  $classroomResponse = Invoke-ApiJson -Method "POST" -Path "/api/classrooms" -Token $teacherToken -Body @{
    name = "Validation Room"
    leaderboardVisibility = "TOP_10"
    participationSettings = '{"buzzBonus":5,"wrongAnswerPenalty":2}'
  }
  Assert-True ($classroomResponse.StatusCode -eq 201) "Classroom creation failed"
  $classroomCode = [string]$classroomResponse.Body.classroom.code

  $questionResponse = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Choose A"
    type = "SINGLE_CHOICE"
    score = 10
    answerKey = "A"
    publicationSettings = '{"showAnswer":false}'
    answerTimeLimitSeconds = 30
  }
  Assert-True ($questionResponse.StatusCode -eq 201) "Question creation failed"
  $questionId = [string]$questionResponse.Body.question.id

  $invalidTransition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $questionId
    targetState = "PUBLISHED"
  }
  Assert-True ($invalidTransition.StatusCode -eq 400) "Invalid transition should fail"

  foreach ($targetState in @("READY", "ANSWER_OPEN")) {
    $transition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      questionId = $questionId
      targetState = $targetState
    }
    Assert-True ($transition.StatusCode -eq 200) "Transition to $targetState failed"
  }

  foreach ($token in @($studentOneToken, $studentTwoToken)) {
    $joinResponse = Invoke-ApiJson -Method "POST" -Path "/api/classrooms/join" -Token $token -Body @{
      classroomCode = $classroomCode
    }
    Assert-True ($joinResponse.StatusCode -eq 200) "Classroom join failed"
  }

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

  $buzzQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Buzz now"
    type = "BUZZ"
    score = 0
    answerKey = "buzz"
    publicationSettings = '{"showAnswer":false}'
    answerTimeLimitSeconds = 10
  }
  $buzzQuestionId = [string]$buzzQuestion.Body.question.id

  foreach ($targetState in @("READY", "BUZZ_OPEN")) {
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
        $response = Invoke-WebRequest -Uri "http://localhost:8080/api/buzz" -Method POST -Headers $headers -Body $body -ContentType "application/json" -UseBasicParsing
        return $response.Content | ConvertFrom-Json
      } catch {
        return [pscustomobject]@{ accepted = $false; outcomeStatus = "ERROR" }
      }
    } -ArgumentList $token, $classroomCode, $buzzQuestionId
  }

  $buzzResults = $buzzJobs | Receive-Job -Wait -AutoRemoveJob
  $winnerCount = @($buzzResults | Where-Object { $_.accepted -eq $true }).Count
  Assert-True ($winnerCount -eq 1) "Expected exactly one buzz winner, got $winnerCount"

  $leaderboard = Invoke-ApiJson -Method "GET" -Path "/api/leaderboard?classroomCode=$classroomCode" -Token $teacherToken -Body $null
  Assert-True ($leaderboard.StatusCode -eq 200) "Leaderboard request failed"
  Assert-True (@($leaderboard.Body.leaderboard).Count -ge 2) "Leaderboard did not return enough entries"

  $exportResponse = Invoke-ApiJson -Method "GET" -Path "/api/reports/export?classroomCode=$classroomCode&type=scores" -Token $teacherToken -Body $null
  Assert-True ($exportResponse.StatusCode -eq 200) "Export request failed"
  Assert-True ([string]$exportResponse.Body.content).StartsWith("userId,displayName,sourceType") "Export content is not CSV"

  $auditCount = Query-AuditCount -RepoRoot $repoRoot
  Assert-True ($auditCount -ge 1) "Audit log was not recorded for export"

  Write-Output "Validation suite passed."
}
finally {
  Stop-ApiProcess -Process $apiProcess
  Stop-ComposeInfra -RepoRoot $repoRoot
}
