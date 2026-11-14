param([switch]$Https, [string]$Domain, [string]$Email, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '请先安装 Docker 和 Compose v2' }
    if ($Domain -and ($Domain -notmatch '^[A-Za-z0-9.-]+$' -or $Domain -eq 'game.example.com')) { throw '请提供真实域名' }
    if ($Email -and $Email -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') { throw '邮箱格式错误' }
    if (-not (Test-Path -LiteralPath '.env')) { Copy-Item -LiteralPath '.env.example' -Destination '.env' }
    $configText = Get-Content -LiteralPath '.env' -Raw
    if ($Domain) { $configText = $configText -replace '(?m)^DOMAIN=.*$', ('DOMAIN=' + $Domain) }
    if ($Email) { $configText = $configText -replace '(?m)^ACME_EMAIL=.*$', ('ACME_EMAIL=' + $Email) }
    if ($Domain -or $Email) { Set-Content -LiteralPath '.env' -Value $configText -Encoding utf8 }
    if ($Https -and $configText -match '(?m)^DOMAIN=game.example.com\s*$') { throw 'HTTPS 部署需设置 .env 的 DOMAIN，或传入 -Domain' }
    $composeArgs = @('compose', '-f', 'docker-compose.yml')
    if ($Https) { $composeArgs += @('-f', 'docker-compose.https.yml') }
    & docker @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose 配置校验失败' }
    if ($CheckOnly) { Write-Host 'Compose 配置校验通过'; return }
    & docker @composeArgs up -d --build --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw '部署失败，请查看 docker compose logs' }
    & docker @composeArgs ps
    Write-Host '部署完成，应用健康检查已通过。'
} finally { Pop-Location }
