[CmdletBinding()]
param(
    [string]$ProjectName = 'gomoku-httpsverify',
    [int]$CaddyHttpPort = 19092,
    [int]$CaddyHttpsPort = 19093,
    [int]$CaddyHttpsUdpPort = 19094,
    [switch]$Keep
)

# 本地 HTTPS/WSS 验收：Caddy 本地 CA 签发 localhost 证书，验证
#   1. 公网入口首页 200、/actuator/health 固定 404
#   2. wss://<host>/ws 能完成 WebSocket 握手
#   3. 经 HTTPS/WSS 跑完整双人对局与存档
# 不触碰真实 gomoku-data 卷；使用独立项目名，结束时清理。

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
$compose = @('compose', '-p', $ProjectName, '-f', 'docker-compose.yml', '-f', 'docker-compose.https.yml')
$image = $ProjectName + ':image'
$rootCa = Join-Path $taskRoot ('target\' + $ProjectName + '-caddy-root.crt')
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

function Run-Check([string]$label, [scriptblock]$body) {
    $global:LASTEXITCODE = 0
    & $body
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "$label 失败（exit=$code）" }
    Write-Host "PASS $label"
}

function Invoke-Curl([string[]]$curlArgs) {
    # Windows PowerShell 5.1 在 $ErrorActionPreference=Stop 时会把原生命令的 stderr
    # 当成终止错误；这里局部降级，由调用方按 exit code 判断是否就绪。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & curl.exe @curlArgs 2>$null
        return @{ Code = $LASTEXITCODE; Output = (@($output) -join "`n") }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Wait-HttpsUp([int]$seconds = 120) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        Start-Sleep -Milliseconds 1000
        # Windows 自带 curl 走 schannel，对本地 CA 无法查询吊销状态，需显式关闭吊销检查；
        # 证书链仍由 --cacert 校验。
        $result = Invoke-Curl @('-fsS', '--ssl-no-revoke', '--cacert', $rootCa,
            '--resolve', "localhost:$CaddyHttpsPort`:127.0.0.1", "https://localhost:$CaddyHttpsPort/", '-o', 'NUL')
        if ($result.Code -eq 0) { return $true }
    } while ((Get-Date) -lt $deadline)
    return $false
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '未安装 Docker' }
    & docker info | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker daemon 未运行' }
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { throw '未找到 curl.exe' }

    Write-Host '== 1. 构建镜像并启动 Caddy =='
    Set-TempEnv 'GOMOKU_IMAGE' $image
    Set-TempEnv 'DOMAIN' 'localhost'
    Set-TempEnv 'ACME_EMAIL' 'verify@example.com'
    Set-TempEnv 'CADDY_HTTP_PORT' "$CaddyHttpPort"
    Set-TempEnv 'CADDY_HTTPS_PORT' "$CaddyHttpsPort"
    Set-TempEnv 'CADDY_HTTPS_UDP_PORT' "$CaddyHttpsUdpPort"
    & docker @compose down -v --remove-orphans | Out-Null
    Run-Check 'HTTPS Compose 启动并等待健康' { & docker @compose up -d --build --wait --wait-timeout 240 | Out-Null }

    $projects = & docker ps --filter ("label=com.docker.compose.project=" + $ProjectName) --format '{{.Names}}'
    $caddyContainer = @($projects | Where-Object { $_ -like '*-caddy-*' }) | Select-Object -First 1
    if (-not $caddyContainer) { throw '未找到 Caddy 容器' }
    $appContainer = @($projects | Where-Object { $_ -like '*-app-*' }) | Select-Object -First 1
    if (-not $appContainer) { throw '未找到应用容器' }
    $appPorts = & docker port $appContainer
    if ($LASTEXITCODE -eq 0 -and $appPorts) { throw "HTTPS 模式下应用端口不应发布到宿主机: $appPorts" }
    Write-Host 'PASS 应用端口未发布到宿主机'

    Remove-Item -LiteralPath $rootCa -ErrorAction SilentlyContinue
    Run-Check '导出 Caddy 本地根证书' { & docker cp "${caddyContainer}:/data/caddy/pki/authorities/local/root.crt" $rootCa | Out-Null }
    if (-not (Test-Path -LiteralPath $rootCa)) { throw '根证书未导出' }
    if (-not (Wait-HttpsUp)) { throw 'HTTPS 首页不可访问' }
    Write-Host 'PASS 本地 CA 证书下 HTTPS 首页 200'

    $healthResult = Invoke-Curl @('-sS', '--ssl-no-revoke', '-o', 'NUL', '-w', '%{http_code}', '--cacert', $rootCa,
        '--resolve', "localhost:$CaddyHttpsPort`:127.0.0.1", "https://localhost:$CaddyHttpsPort/actuator/health")
    if ($healthResult.Output.Trim() -ne '404') { throw "公网 actuator 应为 404，实际 $($healthResult.Output.Trim())" }
    Write-Host 'PASS 公网 /actuator/health 返回 404'

    Write-Host '== 2. WSS 握手与整局对局 =='
    # Node 通过导出的 Caddy 本地根证书校验 localhost，不关闭 TLS 验证。
    Set-TempEnv 'NODE_EXTRA_CA_CERTS' $rootCa
    Set-TempEnv 'WS_URL' "wss://localhost:$CaddyHttpsPort/ws"
    Set-TempEnv 'WS_PATHS' '/ws'
    Push-Location 'gomoku-game/gomoku-game'
    try {
        Run-Check 'WSS /ws 握手' { & node docs/smoke/smoke-websocket-paths.mjs }
    } finally { Pop-Location }
    Set-TempEnv 'HTTP_URL' "https://localhost:$CaddyHttpsPort"
    Push-Location 'gomoku-game/gomoku-game'
    try {
        Run-Check 'HTTPS/WSS 真实双人对局与存档' { & node docs/smoke/smoke-record.mjs }
    } finally { Pop-Location }

    Write-Host '== HTTPS/WSS 验收通过 =='
} finally {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if (-not $Keep) {
            & docker @compose down -v --remove-orphans | Out-Null
            Remove-Item -LiteralPath $rootCa -ErrorAction SilentlyContinue
        }
    } catch {
        Write-Warning "清理未完成：$($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = $previousPreference
        Restore-TempEnv
        Pop-Location
    }
}
