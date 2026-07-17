$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "classpath.ps1")
$classRoot = Get-ClassRoot
$classPath = Get-JavaClassPath

if (-not (Test-Path $classRoot)) {
  & (Join-Path $PSScriptRoot "build.ps1")
}

Push-Location $projectRoot
try {
  java -cp $classPath com.classroom.api.Main
}
finally {
  Pop-Location
}
