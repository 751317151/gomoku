[CmdletBinding()]
param([switch]$SkipDocker)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskPom = 'gomoku-game/gomoku-game/pom.xml'
    & mvn -q -f $taskPom test
    if ($LASTEXITCODE -ne 0) { throw "Maven tests failed with exit code $LASTEXITCODE" }

    $taskJsFiles = @('gomoku-game/gomoku-game/src/main/resources/static/app.js',
        'gomoku-game/gomoku-game/src/main/resources/static/js/games.js',
        'gomoku-game/gomoku-game/src/main/resources/static/sw.js') +
        @(Get-ChildItem 'gomoku-game/gomoku-game/docs/smoke' -Filter '*.mjs' -File | ForEach-Object { $_.FullName })
    foreach ($taskFile in $taskJsFiles) {
        & node --check $taskFile
        if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax check failed: $taskFile" }
    }

    # 运维脚本也要进语法门禁，避免备份/恢复脚本在真正需要时才发现写坏。
    $taskPowerShellScripts = @('scripts/verify-production.ps1', 'scripts/verify-https.ps1',
        'deploy/backup.ps1', 'deploy/restore.ps1')
    foreach ($taskScript in $taskPowerShellScripts) {
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile(
            (Resolve-Path -LiteralPath $taskScript).Path, [ref]$null, [ref]$parseErrors) | Out-Null
        if ($parseErrors.Count) { throw "PowerShell syntax check failed: $taskScript" }
    }

    $taskIndex = Get-Content 'gomoku-game/gomoku-game/src/main/resources/static/index.html' -Raw
    $taskVersions = @([regex]::Matches($taskIndex, '\?v=([0-9]+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($taskVersions.Count -ne 1) { throw 'Static cache versions are inconsistent in index.html' }
    $taskWorker = Get-Content 'gomoku-game/gomoku-game/src/main/resources/static/sw.js' -Raw
    $taskWorkerVersions = @([regex]::Matches($taskWorker, '\?v=([0-9]+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($taskWorkerVersions.Count -ne 1 -or $taskWorkerVersions[0] -ne $taskVersions[0]) {
        throw 'Service Worker shell and index.html must use the same static cache version'
    }

    if (-not $SkipDocker) {
        docker compose -f docker-compose.yml config --quiet
        docker compose -f docker-compose.yml -f docker-compose.https.yml config --quiet
    }
    Write-Host "Project verification passed: cache version $($taskVersions[0])"
} finally { Pop-Location }
