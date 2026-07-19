$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $env:TEMP "classroom-dev"
$pidFile = Join-Path $stateDir "processes.json"
$apiLog = Join-Path $stateDir "api.log"
$apiErrorLog = Join-Path $stateDir "api.err.log"
$webLog = Join-Path $stateDir "web.log"
$webErrorLog = Join-Path $stateDir "web.err.log"

New-Item -ItemType Directory -Force -Path $stateDir | Out-Null

if (Test-Path -LiteralPath $pidFile) {
  throw "Dev stack appears to be running already. Use 'npm.cmd run stop:dev' first."
}

Push-Location $repoRoot
try {
  docker compose up -d postgres redis | Out-Null

  $apiProcess = Start-Process -FilePath "npm.cmd" -ArgumentList @("run", "dev", "--workspace", "@classroom/api") `
    -WorkingDirectory $repoRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $apiLog `
    -RedirectStandardError $apiErrorLog `
    -PassThru

  $webProcess = Start-Process -FilePath "npm.cmd" -ArgumentList @("run", "dev", "--workspace", "@classroom/web", "--", "--host", "0.0.0.0") `
    -WorkingDirectory $repoRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $webLog `
    -RedirectStandardError $webErrorLog `
    -PassThru

  @{
    apiPid = $apiProcess.Id
    webPid = $webProcess.Id
    apiLog = $apiLog
    apiErrorLog = $apiErrorLog
    webLog = $webLog
    webErrorLog = $webErrorLog
  } | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding UTF8

  Write-Output "Dev stack started."
  Write-Output "Frontend: http://localhost:5173"
  Write-Output "API: http://localhost:8080/api/health"
  Write-Output "WebSocket: ws://localhost:8081/ws"
  Write-Output "Logs:"
  Write-Output "  API stdout: $apiLog"
  Write-Output "  API stderr: $apiErrorLog"
  Write-Output "  Web stdout: $webLog"
  Write-Output "  Web stderr: $webErrorLog"
  Write-Output "Stop with: npm.cmd run stop:dev"
}
finally {
  Pop-Location
}
