$ErrorActionPreference = "Stop"

$webRoot = Split-Path -Parent $PSScriptRoot
Push-Location $webRoot
try {
  npx tsc -b --force
  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }
  node --test tests/*.test.mjs
  exit $LASTEXITCODE
}
finally {
  Pop-Location
}
