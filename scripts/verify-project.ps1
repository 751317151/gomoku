[CmdletBinding()]
param([switch]$SkipDocker)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskPom = 'gomoku-game/gomoku-game/pom.xml'
    & mvn -q -f $taskPom test
    if ($LASTEXITCODE -ne 0) { throw "Maven tests failed with exit code $LASTEXITCODE" }

    $taskJsFiles = @('gomoku-game/gomoku-game/src/main/resources/static/app.js') +
        @(Get-ChildItem 'gomoku-game/gomoku-game/docs/smoke' -Filter '*.mjs' -File | ForEach-Object { $_.FullName })
    foreach ($taskFile in $taskJsFiles) {
        & node --check $taskFile
        if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax check failed: $taskFile" }
    }

    $taskIndex = Get-Content 'gomoku-game/gomoku-game/src/main/resources/static/index.html' -Raw
    $taskVersions = @([regex]::Matches($taskIndex, '\?v=([0-9]+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($taskVersions.Count -ne 1) { throw 'Static cache versions are inconsistent in index.html' }

    if (-not $SkipDocker) {
        docker compose -f docker-compose.yml config --quiet
        docker compose -f docker-compose.yml -f docker-compose.https.yml config --quiet
    }
    Write-Host "Project verification passed: cache version $($taskVersions[0])"
} finally { Pop-Location }
