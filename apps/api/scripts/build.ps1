$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot "src\main\java"
$outputRoot = Join-Path $env:TEMP "classroom-api\classes"

if (Test-Path -LiteralPath $outputRoot) {
  Remove-Item -Recurse -Force -LiteralPath $outputRoot -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

$sources = Get-ChildItem -Path $sourceRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }

if (-not $sources -or $sources.Count -eq 0) {
  throw "No Java source files found under $sourceRoot"
}

javac -encoding UTF-8 -d $outputRoot $sources

if ($LASTEXITCODE -ne 0) {
  throw "javac failed with exit code $LASTEXITCODE"
}
