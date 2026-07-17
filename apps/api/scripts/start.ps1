$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$classRoot = Join-Path $env:TEMP "classroom-api\classes"

if (-not (Test-Path $classRoot)) {
  & (Join-Path $PSScriptRoot "build.ps1")
}

Push-Location $projectRoot
try {
  java -cp $classRoot com.classroom.api.Main
}
finally {
  Pop-Location
}
