[CmdletBinding()]
param(
    [string]$ProjectName = 'gomoku',
    [string]$OutputDir = 'backups',
    [int]$Keep = 7,
    [int]$HealthTimeoutSeconds = 120,
    [switch]$Https,
    [switch]$SkipStop
)

# 备份线上数据卷。默认先停应用（保证 SQLite 主文件与 WAL 一致），打包后立即启动。
# 用法：./deploy/backup.ps1 [-ProjectName gomoku] [-OutputDir D:\backups] [-Keep 14] [-Https]

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
$compose = @('compose', '-p', $ProjectName, '-f', 'docker-compose.yml')
if ($Https) { $compose += @('-f', 'docker-compose.https.yml') }
$volume = $ProjectName + '_gomoku-data'
$container = $ProjectName + '-app-1'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$archiveName = "gomoku-data-$stamp.tgz"

function Wait-Healthy([int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        Start-Sleep -Seconds 1
        $status = & docker inspect $container --format '{{.State.Health.Status}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $status -eq 'healthy') { return $true }
    } while ((Get-Date) -lt $deadline)
    return $false
}

try {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $resolvedOut = (Resolve-Path -LiteralPath $OutputDir).Path
    $target = Join-Path $resolvedOut $archiveName

    if (-not $SkipStop) {
        Write-Host "停止应用（等待优雅停机）..."
        & docker @compose stop app | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '停止应用失败，未开始备份' }
    } else {
        Write-Host '警告：跳过停机，SQLite WAL 可能不在同一快照；仅建议维护窗口使用。'
    }

    try {
        Write-Host "备份卷 $volume -> $target"
        & docker run --rm -v "${volume}:/data" -v "${resolvedOut}:/backup" alpine `
            tar czf "/backup/$archiveName" -C /data .
        if ($LASTEXITCODE -ne 0) { throw '打包数据卷失败' }
        if (-not (Test-Path -LiteralPath $target) -or (Get-Item -LiteralPath $target).Length -eq 0) {
            throw '备份文件未生成或为空'
        }
        $entries = & docker run --rm -v "${resolvedOut}:/backup" alpine tar tzf "/backup/$archiveName"
        if ($LASTEXITCODE -ne 0) { throw '无法读取备份归档' }
        if (-not ($entries -match 'games/archive\.sqlite')) { throw '备份缺少 games/archive.sqlite' }
        if (-not ($entries -match 'games/index\.jsonl')) { throw '备份缺少 games/index.jsonl' }
        Write-Host ("PASS 备份完成：{0} ({1:N0} bytes)" -f $target, (Get-Item -LiteralPath $target).Length)
    } finally {
        if (-not $SkipStop) {
            Write-Host '重新启动应用...'
            & docker @compose start app | Out-Null
            if (-not (Wait-Healthy $HealthTimeoutSeconds)) {
                Write-Warning '应用重启后健康检查未在超时内通过，请立即查看 docker logs'
            } else {
                Write-Host 'PASS 应用已恢复健康'
            }
        }
    }

    if ($Keep -gt 0) {
        $old = Get-ChildItem -LiteralPath $resolvedOut -Filter 'gomoku-data-*.tgz' |
            Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep
        foreach ($file in $old) {
            Remove-Item -LiteralPath $file.FullName
            Write-Host "已清理旧备份 $($file.Name)"
        }
    }
} finally {
    Pop-Location
}
