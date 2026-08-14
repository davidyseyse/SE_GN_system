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
  Wait-ForPort -HostName "127.0.0.1" -Port $script:ApiTestWebSocketPort

  $teacherToken = New-Token -DisplayName "Load Teacher" -Role "TEACHER" -UserId "load-teacher"
  $classroomResponse = Invoke-ApiJson -Method "POST" -Path "/api/classrooms" -Token $teacherToken -Body @{
    name = "Load Room"
    participationSettings = '{"buzzBonus":5,"wrongAnswerPenalty":1}'
  }
  $classroomCode = [string]$classroomResponse.Body.classroom.code
  $sheetConfig = Invoke-ApiJson -Method "POST" -Path "/api/sheet-configs" -Token $teacherToken -Body @{
    name = "Load Sheet"
    sheetId = "load-sheet-$([guid]::NewGuid())"
  }
  $sheetConfigId = [string]$sheetConfig.Body.config.id

  $studentTokens = @()
  for ($index = 1; $index -le 100; $index++) {
    $studentTokens += New-Token -DisplayName "Student $index / $index" -Role "STUDENT" -UserId "load-student-$index"
  }

  $joinJobs = foreach ($token in $studentTokens) {
    Start-Job -ScriptBlock {
      param($activeToken, $activeClassroomCode, $apiPort)
      $headers = @{ Authorization = "Bearer $activeToken" }
      $body = @{ classroomCode = $activeClassroomCode } | ConvertTo-Json -Compress
      try {
        $response = Invoke-WebRequest -Uri "http://localhost:$apiPort/api/classrooms/join" -Method POST -Headers $headers -Body $body -ContentType "application/json" -UseBasicParsing
        return [pscustomobject]@{ ok = ($response.StatusCode -eq 200) }
      } catch {
        return [pscustomobject]@{ ok = $false }
      }
    } -ArgumentList $token, $classroomCode, $script:ApiTestPort
  }
  $joinResults = $joinJobs | Receive-Job -Wait -AutoRemoveJob
  Assert-True (@($joinResults | Where-Object { $_.ok }).Count -eq 100) "Expected 100 successful joins"

  $members = Invoke-ApiJson -Method "GET" -Path "/api/classrooms/members?classroomCode=$classroomCode" -Token $teacherToken -Body $null
  Assert-True (@($members.Body.members).Count -ge 100) "Member snapshot did not include 100 students"

  $buzzQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Concurrent buzz"
    type = "BUZZ"
    score = 0
    answerKey = "buzz"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "A1" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 10
  }
  $buzzQuestionId = [string]$buzzQuestion.Body.question.id
  foreach ($targetState in @("BUZZ_OPEN")) {
    $transition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
      classroomCode = $classroomCode
      questionId = $buzzQuestionId
      targetState = $targetState
    }
    Assert-True ($transition.StatusCode -eq 200) "Failed to move buzz question to $targetState"
  }

  $buzzJobs = foreach ($token in $studentTokens[0..49]) {
    Start-Job -ScriptBlock {
      param($activeToken, $activeClassroomCode, $activeQuestionId, $apiPort)
      $headers = @{ Authorization = "Bearer $activeToken" }
      $body = @{ classroomCode = $activeClassroomCode; questionId = $activeQuestionId; requestId = [guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
      try {
        $response = Invoke-WebRequest -Uri "http://localhost:$apiPort/api/buzz" -Method POST -Headers $headers -Body $body -ContentType "application/json" -UseBasicParsing
        return $response.Content | ConvertFrom-Json
      } catch {
        return [pscustomobject]@{ accepted = $false }
      }
    } -ArgumentList $token, $classroomCode, $buzzQuestionId, $script:ApiTestPort
  }
  $buzzResults = $buzzJobs | Receive-Job -Wait -AutoRemoveJob
  Assert-True (@($buzzResults | Where-Object { $_.accepted -eq $true }).Count -eq 50) "Expected all 50 concurrent buzz attempts to be recorded"
  Assert-True (@($buzzResults | Where-Object { $_.outcomeStatus -eq "RECORDED" }).Count -eq 50) "Concurrent buzz attempts did not report RECORDED"

$sessionResponse = Invoke-ApiJson -Method "POST" -Path "/api/realtime/session" -Token $studentTokens[0] -Body @{
    classroomCode = $classroomCode
  }
  $sessionId = [string]$sessionResponse.Body.sessionId
  $socket = Open-RawWebSocket -Token $studentTokens[0] -ClassroomCode $classroomCode -SessionId $sessionId
  $firstSync = Read-RawWebSocketTextFrame -Connection $socket
  Assert-True ($firstSync.Contains("session.resynced")) "Initial WebSocket sync failed"
  Close-RawWebSocket -Connection $socket

  $answerQuestion = Invoke-ApiJson -Method "POST" -Path "/api/questions" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    prompt = "Reconnect check"
    type = "SHORT_ANSWER"
    score = 5
    answerKey = "ok"
    publicationSettings = (@{ showAnswer = $false; sheetConfigId = $sheetConfigId; targetCell = "A2" } | ConvertTo-Json -Compress)
    answerTimeLimitSeconds = 20
  }
  $answerQuestionId = [string]$answerQuestion.Body.question.id
  $readyTransition = Invoke-ApiJson -Method "POST" -Path "/api/questions/state" -Token $teacherToken -Body @{
    classroomCode = $classroomCode
    questionId = $answerQuestionId
    targetState = "ANSWER_OPEN"
  }
  Assert-True ($readyTransition.StatusCode -eq 200) "Failed to publish reconnect event"

  $socket = Open-RawWebSocket -Token $studentTokens[0] -ClassroomCode $classroomCode -SessionId $sessionId
  $resync = Read-RawWebSocketTextFrame -Connection $socket
  Assert-True ($resync.Contains("session.resynced")) "Reconnect did not resync state"
  Close-RawWebSocket -Connection $socket

  Write-Output "Load suite passed."
}
finally {
  if ($socket) {
    Close-RawWebSocket -Connection $socket
  }
  Stop-ApiProcess -Process $apiProcess
  Stop-ComposeInfra -RepoRoot $repoRoot
}
