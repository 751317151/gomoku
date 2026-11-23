[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [string]$ProjectName = 'gomoku',
    [string]$PreRestoreDir = 'backups',
    [int]$HealthTimeoutSeconds = 180,
    [switch]$ConfirmRestore
)

# 从备份归档恢复线上数据卷。会先自动备份当前卷到 backups/pre-restore-*.tgz，
# 再清空目标卷并解压。必须显式传入 -ConfirmRestore，避免误操作。
# 用法：./deploy/restore.ps1 -BackupFile backups/gomoku-data-20261121-120000.tgz -ConfirmRestore

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
$compose = @('compose', '-p', $ProjectName, '-f', 'docker-compose.yml')
$volume = $ProjectName + '_gomoku-data'
$container = $ProjectName + '-app-1'
$preRestoreDir = Join-Path $taskRoot $PreRestoreDir
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$preRestoreName = "pre-restore-$stamp.tgz"

function Wait-Healthy([int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        Start-Sleep -Seconds 1
        $status = & docker inspect $container --format '{{.State.Health.Status}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $status -eq 'healthy') { return $true }
    } while ((Get-Date) -lt $deadline)
    return $false
}

if (-not $ConfirmRestore) { throw '恢复会覆盖现有数据卷。确认后请追加 -ConfirmRestore。' }
if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'ProjectName 只能包含小写字母、数字、下划线和连字符' }
$resolvedBackup = (Resolve-Path -LiteralPath $BackupFile).Path
$backupDir = Split-Path -Parent $resolvedBackup
$backupLeaf = Split-Path -Leaf $resolvedBackup

try {
    & docker volume inspect $volume | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "目标卷 $volume 不存在；请确认项目名与实际部署一致" }
    $entries = & docker run --rm -v "${backupDir}:/backup:ro" alpine tar tzf "/backup/$backupLeaf"
    if ($LASTEXITCODE -ne 0) { throw '无法读取备份归档' }
    if (-not ($entries -match 'games/archive\.sqlite')) { throw '备份缺少 games/archive.sqlite' }
    if (-not ($entries -match 'games/index\.jsonl')) { throw '备份缺少 games/index.jsonl' }

    New-Item -ItemType Directory -Force -Path $preRestoreDir | Out-Null
    $preRestoreDir = (Resolve-Path -LiteralPath $preRestoreDir).Path
    Write-Host "停止应用（等待优雅停机）..."
    & docker @compose stop app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '停止应用失败，未改动任何数据' }

    try {
        Write-Host '先备份当前卷，便于回滚本次恢复...'
        & docker run --rm -v "${volume}:/data:ro" -v "${preRestoreDir}:/backup" alpine `
            tar czf "/backup/$preRestoreName" -C /data .
        if ($LASTEXITCODE -ne 0) { throw '当前卷保护性备份失败，已中止，未改动任何数据' }
        $preRestorePath = Join-Path $preRestoreDir $preRestoreName
        if (-not (Test-Path -LiteralPath $preRestorePath)) { throw '保护性备份未生成，已中止' }
        Write-Host "PASS 当前卷已备份到 $preRestorePath"

        Write-Host "清空卷 $volume 并解压 $backupLeaf ..."
        & docker run --rm -v "${volume}:/data" alpine sh -c 'find /data -mindepth 1 -maxdepth 1 -exec rm -rf {} +'
        if ($LASTEXITCODE -ne 0) { throw '清空数据卷失败' }
        & docker run --rm -v "${volume}:/data" -v "${backupDir}:/backup:ro" alpine `
            tar xzf "/backup/$backupLeaf" -C /data
        if ($LASTEXITCODE -ne 0) { throw '解压备份失败' }
    } finally {
        Write-Host '重新启动应用...'
        & docker @compose start app | Out-Null
        if (-not (Wait-Healthy $HealthTimeoutSeconds)) {
            throw '恢复后应用未在超时内恢复健康；请用 pre-restore 归档回滚并查看 docker logs'
        }
    }

    Write-Host 'PASS 恢复完成，应用健康检查通过'
    Write-Host "回滚归档：$preRestorePath"
    Write-Host '请打开大厅抽查最近对局与复盘，再决定是否清理旧归档。'
} finally {
    Pop-Location
}
