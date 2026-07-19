$ErrorActionPreference = "Stop"

$stateDir = Join-Path $env:TEMP "classroom-dev"
$pidFile = Join-Path $stateDir "processes.json"

if (-not (Test-Path -LiteralPath $pidFile)) {
  Write-Output "Dev stack is not running."
  exit 0
}

$state = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json

foreach ($processId in @($state.apiPid, $state.webPid)) {
  if ($null -eq $processId) {
    continue
  }

  try {
    $process = Get-Process -Id $processId -ErrorAction Stop
    Stop-Process -Id $process.Id -Force
  } catch {
  }
}

Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
  docker compose down | Out-Null
}
finally {
  Pop-Location
}

Write-Output "Dev stack stopped."
