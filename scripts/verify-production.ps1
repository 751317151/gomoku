[CmdletBinding()]
param(
    [string]$ProjectName = 'gomoku-prodverify',
    [int]$HttpPort = 19100,
    [int]$WsPort = 19101,
    [switch]$Keep
)

# 生产容器验收：真实 Dockerfile 构建、Compose 启动（非 root + 只读根 + tmpfs exec）、
# 健康检查、真实 WebSocket 对局、重启后资产、含 WAL 的备份恢复、治理接口令牌传递。
# 不触碰真实 gomoku-data 卷；所有容器/卷/网络用独立项目名，结束时清理。

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
$compose = @('compose', '-p', $ProjectName, '-f', 'docker-compose.yml')
$adminToken = 'verify-' + [guid]::NewGuid().ToString('N')
$image = $ProjectName + ':image'
$backupName = $null
$preRestoreNew = @()
$restoreVolume = $ProjectName + '-restore'
$restoreContainer = $ProjectName + '-restore'
$savedEnv = @{}
New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'target') | Out-Null

function Set-TempEnv([string]$name, [string]$value) {
    if (-not $script:savedEnv.ContainsKey($name)) {
        $script:savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    Set-Item -Path ("Env:" + $name) -Value $value
}

function Restore-TempEnv {
    foreach ($name in @($script:savedEnv.Keys)) {
        $value = $script:savedEnv[$name]
        if ($null -eq $value) {
            Remove-Item -Path ("Env:" + $name) -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path ("Env:" + $name) -Value $value
        }
    }
}

function Remove-ContainerIfExists([string]$name) {
    $found = & docker ps -a --filter ("name=^/" + $name + "$") --format '{{.Names}}'
    if ($LASTEXITCODE -eq 0 -and $found) { & docker rm -f $name | Out-Null }
}

function Remove-VolumeIfExists([string]$name) {
    $found = & docker volume ls --filter ("name=^" + $name + "$") --format '{{.Name}}'
    if ($LASTEXITCODE -eq 0) {
        foreach ($item in @($found)) {
            if ($item -eq $name) { & docker volume rm $name | Out-Null }
        }
    }
}

function Wait-AppHealthy([string]$baseUrl, [int]$seconds = 90) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        Start-Sleep -Milliseconds 700
        try { $up = (Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 2).status -eq 'UP' } catch { $up = $false }
    } while (-not $up -and (Get-Date) -lt $deadline)
    return $up
}

function Run-Check([string]$label, [scriptblock]$body) {
    $global:LASTEXITCODE = 0
    & $body
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "$label 失败（exit=$code）" }
    Write-Host "PASS $label"
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '未安装 Docker' }
    & docker info | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker daemon 未运行' }

    Remove-ContainerIfExists $restoreContainer
    Remove-VolumeIfExists $restoreVolume

    Write-Host '== 1. Dockerfile 构建 =='
    Run-Check '生产镜像构建' { & docker build -q -t $image . | Out-Null }

    Write-Host '== 2. Compose 启动 =='
    Set-TempEnv 'GOMOKU_IMAGE' $image
    Set-TempEnv 'HTTP_PORT' "$HttpPort"
    Set-TempEnv 'WS_PORT' "$WsPort"
    Set-TempEnv 'ADMIN_TOKEN' $adminToken
    Set-TempEnv 'DOMAIN' 'localhost'
    Set-TempEnv 'ACME_EMAIL' 'verify@example.com'
    & docker @compose down -v --remove-orphans | Out-Null
    Run-Check 'Compose 启动并等待健康' { & docker @compose up -d --wait --wait-timeout 240 | Out-Null }

    $container = ($ProjectName + '-app-1')
    $state = & docker inspect $container --format '{{.State.Health.Status}} {{.Config.User}} {{.HostConfig.ReadonlyRootfs}}'
    if ($state -notmatch '^healthy gomoku true$') { throw "容器安全/健康状态不符: $state" }
    $hardening = & docker inspect $container --format '{{json .HostConfig.CapDrop}}|{{.HostConfig.Memory}}|{{.HostConfig.PidsLimit}}'
    if ($hardening -notmatch 'ALL' -or $hardening -notmatch '\|1073741824\|512$') {
        throw "容器资源/权限加固不符: $hardening"
    }
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$HttpPort/actuator/health" -TimeoutSec 10
    if ($health.status -ne 'UP') { throw "健康检查非 UP: $($health.status)" }
    Write-Host 'PASS 非 root / 只读根 / cap_drop ALL / 内存与进程上限 / 健康检查 UP'

    Write-Host '== 3. 真实对局与治理接口 =='
    Set-TempEnv 'HTTP_URL' "http://127.0.0.1:$HttpPort"
    Set-TempEnv 'WS_URL' "ws://127.0.0.1:$WsPort"
    Push-Location 'gomoku-game/gomoku-game'
    try {
        Run-Check '真实双人对局与存档' { & node docs/smoke/smoke-record.mjs }
    } finally { Pop-Location }
    Set-TempEnv 'API_BASE' "http://127.0.0.1:$HttpPort"
    Set-TempEnv 'ADMIN_TOKEN' $adminToken
    Push-Location 'gomoku-game/gomoku-game'
    try {
        Run-Check '运营看板与令牌传递' { & node docs/smoke/smoke-ops.mjs }
    } finally { Pop-Location }

    Write-Host '== 4. 重启后资产一致 =='
    $before = Invoke-RestMethod "http://127.0.0.1:$HttpPort/api/games?limit=100"
    # 运营冒烟会留下异步落盘的对局，等列表稳定后再取基线，避免把「新记录」误判为不一致。
    $stableSince = Get-Date
    $lastJson = $before | ConvertTo-Json -Depth 8 -Compress
    while (((Get-Date) - $stableSince).TotalSeconds -lt 2) {
        Start-Sleep -Milliseconds 500
        $current = Invoke-RestMethod "http://127.0.0.1:$HttpPort/api/games?limit=100"
        $currentJson = $current | ConvertTo-Json -Depth 8 -Compress
        if ($currentJson -ne $lastJson) { $stableSince = Get-Date; $lastJson = $currentJson }
    }
    $before = Invoke-RestMethod "http://127.0.0.1:$HttpPort/api/games?limit=100"
    & docker @compose restart app | Out-Null
    if (-not (Wait-AppHealthy "http://127.0.0.1:$HttpPort")) { throw '重启后健康检查超时' }
    $after = Invoke-RestMethod "http://127.0.0.1:$HttpPort/api/games?limit=100"
    $beforeById = @{}
    foreach ($item in @($before)) { $beforeById[$item.id] = ($item | ConvertTo-Json -Depth 8 -Compress) }
    $afterById = @{}
    foreach ($item in @($after)) { $afterById[$item.id] = ($item | ConvertTo-Json -Depth 8 -Compress) }
    foreach ($id in $beforeById.Keys) {
        if (-not $afterById.ContainsKey($id)) { throw "重启后丢失对局 $id" }
        if ($afterById[$id] -ne $beforeById[$id]) { throw "重启后对局 $id 内容变化" }
    }
    if (@($after).Count -lt @($before).Count) {
        throw "重启后对局数量减少: $(@($before).Count) -> $(@($after).Count)"
    }
    # 只匹配 ASCII 标记，避免 Windows PowerShell 控制台编码把中文日志解成乱码。
    $logs = (& docker @compose logs app --no-color 2>$null) -join "`n"
    if ($logs -notmatch '\[SHUTDOWN\]') { throw '重启时未观察到优雅停机日志' }
    Write-Host 'PASS 重启后对局列表逐字一致'
    Write-Host 'PASS 重启经过优雅停机（SIGTERM 被应用处理）'

    Write-Host '== 5. 含 WAL 的备份恢复 =='
    # 直接调用线上运维脚本，确保被验收的就是实际会使用的备份流程。
    $beforeBackup = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'target') -Filter 'gomoku-data-*.tgz' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    Run-Check '备份数据卷（deploy/backup.ps1）' {
        & powershell -NoProfile -ExecutionPolicy Bypass -File 'deploy/backup.ps1' -ProjectName $ProjectName -OutputDir 'target' -Keep 0
    }
    $newBackups = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'target') -Filter 'gomoku-data-*.tgz' |
        Where-Object { $beforeBackup -notcontains $_.FullName } | Sort-Object LastWriteTime -Descending)
    if (-not $newBackups.Count) { throw '备份脚本未生成归档' }
    $backupName = $newBackups[0].FullName
    $backupLeaf = $newBackups[0].Name
    if (-not (Test-Path -LiteralPath $backupName) -or (Get-Item -LiteralPath $backupName).Length -eq 0) {
        throw '备份文件未生成或为空'
    }
    $entries = & docker run --rm -v "${taskRoot}:/backup" alpine tar tzf "/backup/target/$backupLeaf"
    if ($LASTEXITCODE -ne 0) { throw '无法读取备份归档' }
    if (-not ($entries -match 'games/archive\.sqlite')) { throw '备份缺少 games/archive.sqlite' }
    if (-not ($entries -match 'games/index\.jsonl')) { throw '备份缺少 games/index.jsonl' }
    Write-Host 'PASS 备份包含 SQLite 索引与可重放日志'
    if (-not (Wait-AppHealthy "http://127.0.0.1:$HttpPort")) { throw '备份后重启健康检查超时' }

    Remove-ContainerIfExists $restoreContainer
    Remove-VolumeIfExists $restoreVolume
    Run-Check '创建恢复卷' { & docker volume create $restoreVolume | Out-Null }
    Run-Check '解压备份到新卷' {
        & docker run --rm -v "${restoreVolume}:/data" -v "${taskRoot}:/backup" alpine tar xzf "/backup/target/$backupLeaf" -C /data
    }
    & docker run --rm -v "${restoreVolume}:/data" alpine sh -c 'test -f /data/games/archive.sqlite && test -f /data/games/index.jsonl'
    if ($LASTEXITCODE -ne 0) { throw '恢复卷缺少 archive.sqlite 或 index.jsonl' }
    Run-Check '从备份启动新容器' {
        & docker run -d --name $restoreContainer -p "$($HttpPort + 10):9000" -p "$($WsPort + 10):9001" `
            -v "${restoreVolume}:/app/data" --read-only --tmpfs /tmp:size=64m,exec `
            --security-opt no-new-privileges:true $image | Out-Null
    }
    $restoreBase = "http://127.0.0.1:$($HttpPort + 10)"
    if (-not (Wait-AppHealthy $restoreBase)) { throw '恢复容器健康检查超时' }
    $restored = Invoke-RestMethod "$restoreBase/api/games?limit=100"
    $restoredById = @{}
    foreach ($item in @($restored)) { $restoredById[$item.id] = ($item | ConvertTo-Json -Depth 8 -Compress) }
    foreach ($id in $afterById.Keys) {
        if (-not $restoredById.ContainsKey($id)) { throw "恢复后丢失对局 $id" }
        if ($restoredById[$id] -ne $afterById[$id]) { throw "恢复后对局 $id 内容变化" }
    }
    $withMoves = $restored | Where-Object { $_.moveCount -gt 0 } | Select-Object -First 1
    if ($withMoves) {
        $detail = Invoke-RestMethod "$restoreBase/api/games/$($withMoves.id)"
        if (($detail.moveHistory | Measure-Object).Count -ne $withMoves.moveCount) { throw '恢复后的复盘棋谱不完整' }
    }
    Write-Host 'PASS 备份恢复后对局与复盘一致'

    Write-Host '== 6. 恢复脚本回滚演练 =='
    # 用真实运维脚本把主卷恢复回备份，并确认 pre-restore 保护归档确实能生成。
    $preRestoreBefore = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'target') -Filter 'pre-restore-*.tgz' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    Run-Check 'deploy/restore.ps1 恢复主卷' {
        & powershell -NoProfile -ExecutionPolicy Bypass -File 'deploy/restore.ps1' -BackupFile $backupName -ProjectName $ProjectName -PreRestoreDir 'target' -ConfirmRestore
    }
    $preRestoreNew = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'target') -Filter 'pre-restore-*.tgz' |
        Where-Object { $preRestoreBefore -notcontains $_.FullName })
    if (-not $preRestoreNew.Count) { throw '恢复脚本未生成保护性回滚归档' }
    $preRestore = $preRestoreNew[0].FullName
    $afterRestore = Invoke-RestMethod "http://127.0.0.1:$HttpPort/api/games?limit=100"
    $afterRestoreById = @{}
    foreach ($item in @($afterRestore)) { $afterRestoreById[$item.id] = ($item | ConvertTo-Json -Depth 8 -Compress) }
    foreach ($id in $afterById.Keys) {
        if (-not $afterRestoreById.ContainsKey($id)) { throw "回滚到备份后丢失对局 $id" }
        if ($afterRestoreById[$id] -ne $afterById[$id]) { throw "回滚到备份后对局 $id 内容变化" }
    }
    Write-Host 'PASS 回滚到备份后对局仍一致，且生成了保护性回滚归档'

    Write-Host '== 生产容器验收通过 =='
} finally {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if (-not $Keep) {
            Remove-ContainerIfExists $restoreContainer
            Remove-VolumeIfExists $restoreVolume
            & docker @compose down -v --remove-orphans | Out-Null
            if ($backupName) { Remove-Item -LiteralPath $backupName -ErrorAction SilentlyContinue }
            foreach ($file in $preRestoreNew) { Remove-Item -LiteralPath $file.FullName -ErrorAction SilentlyContinue }
        }
    } catch {
        Write-Warning "清理未完成：$($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = $previousPreference
        Restore-TempEnv
        Pop-Location
    }
}
