$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$classRoot = Join-Path $env:TEMP "classroom-api\classes"

& (Join-Path $PSScriptRoot "build.ps1")

Push-Location $projectRoot
try {
  $output = java -cp $classRoot com.classroom.api.SmokeTest
  Write-Output $output
}
finally {
  Pop-Location
}
