param([switch]$NoOpen)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Section {
    param([string]$Text)
    Write-Host ""
    Write-Host "==============================================="
    Write-Host $Text
    Write-Host "==============================================="
}

function Fail-WithHelp {
    param([string]$Message, [string]$Fix)
    Write-Host ""
    Write-Host "错误：$Message" -ForegroundColor Red
    Write-Host "解决办法：$Fix" -ForegroundColor Yellow
    exit 1
}

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Ensure-LocalSecrets {
    param([string]$SecretDirectory)
    New-Item -ItemType Directory -Force -Path $SecretDirectory | Out-Null
    foreach ($name in @("db_owner_password", "db_app_password", "redis_password", "auth_hash_pepper", "data_encryption_key")) {
        $path = Join-Path $SecretDirectory $name
        if (-not (Test-Path -LiteralPath $path) -or (Get-Item -LiteralPath $path).Length -eq 0) {
            Set-Content -LiteralPath $path -Value (New-RandomSecret) -Encoding Ascii -NoNewline
            Write-Host "已生成本机 Secret：$name"
        }
    }
    foreach ($name in @("ai_api_key", "tencentcloud_ses_secret_id", "tencentcloud_ses_secret_key")) {
        $optionalSecretPath = Join-Path $SecretDirectory $name
        if (-not (Test-Path -LiteralPath $optionalSecretPath)) {
            New-Item -ItemType File -Path $optionalSecretPath | Out-Null
            Write-Host "已创建空的本机 Secret：$name（未启用对应服务时保持为空）"
        }
    }
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrontendUrl = "http://localhost:8080"

Write-Section "投递牛马 Cloud Docker 一键启动器"
Write-Host "项目目录：$ProjectRoot"
Write-Host "统一入口：$FrontendUrl"

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail-WithHelp "没有找到 docker 命令。" "请安装并启动 Docker Desktop，重新打开 PowerShell 后再试。"
}

$StrictPreference = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
docker info *> $null
$DockerInfoExitCode = $LASTEXITCODE
docker compose version *> $null
$DockerComposeExitCode = $LASTEXITCODE
$ErrorActionPreference = $StrictPreference
if ($DockerInfoExitCode -ne 0 -or $DockerComposeExitCode -ne 0) {
    Fail-WithHelp "Docker 或 Docker Compose 没有正常运行。" "请打开 Docker Desktop，等待 Docker Engine running 后重试。"
}

Push-Location $ProjectRoot
try {
    Write-Section "1. 初始化本机 Secret"
    Ensure-LocalSecrets (Join-Path $ProjectRoot ".secrets")

    Write-Section "2. 校验并启动全部 Cloud 组件"
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose 配置校验失败" }

    docker compose up -d --build --wait --wait-timeout 600
    if ($LASTEXITCODE -ne 0) { throw "Cloud 容器未在规定时间内全部就绪" }

    $health = Invoke-RestMethod -Uri "$FrontendUrl/api/health" -TimeoutSec 10
    if ($health.status -ne "UP") { throw "统一健康检查未返回 UP" }
} catch {
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "查看原因：在 $ProjectRoot 执行 docker compose ps 和 docker compose logs" -ForegroundColor Yellow
    exit 1
} finally {
    Pop-Location
}

Write-Section "3. 启动完成"
Write-Host "统一入口：$FrontendUrl" -ForegroundColor Green
Write-Host "健康检查：$FrontendUrl/api/health"
Write-Host "查看日志：docker compose logs -f"
Write-Host "停止服务：docker compose down"
if (-not $NoOpen) {
    Start-Process $FrontendUrl
}
