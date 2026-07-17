$ErrorActionPreference = "Stop"

function Get-ClassRoot {
  return Join-Path $env:TEMP "classroom-api\classes"
}

function Get-DriverJar {
  $projectRoot = Split-Path -Parent $PSScriptRoot
  return Join-Path $projectRoot "lib\postgresql-42.7.4.jar"
}

function Get-JavaClassPath {
  $classRoot = Get-ClassRoot
  $driverJar = Get-DriverJar
  if (-not (Test-Path -LiteralPath $driverJar)) {
    throw "PostgreSQL JDBC driver not found at $driverJar"
  }
  return "$classRoot;$driverJar"
}
