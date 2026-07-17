$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "classpath.ps1")
$classPath = Get-JavaClassPath

& (Join-Path $PSScriptRoot "build.ps1")

Push-Location $projectRoot
try {
  $output = java -cp $classPath com.classroom.api.SmokeTest
  Write-Output $output
}
finally {
  Pop-Location
}

& (Join-Path $PSScriptRoot "validation.ps1")
