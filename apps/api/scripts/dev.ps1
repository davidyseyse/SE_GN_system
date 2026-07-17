$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "classpath.ps1")
$classPath = Get-JavaClassPath

& (Join-Path $PSScriptRoot "build.ps1")

Push-Location $projectRoot
try {
  java -cp $classPath com.classroom.api.Main
}
finally {
  Pop-Location
}
