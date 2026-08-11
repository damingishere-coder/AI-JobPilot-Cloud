param(
    [string]$OutputPath = ("backups/ai-jobpilot-{0}.dump" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$BackupRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot "backups"))
$OutputFull = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $OutputPath))
if (-not $OutputFull.StartsWith($BackupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "备份文件必须位于项目 backups 目录内。"
}

New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
Push-Location $ProjectRoot
try {
    $ContainerId = (docker compose ps -q postgres).Trim()
    if ([string]::IsNullOrWhiteSpace($ContainerId)) {
        throw "PostgreSQL 容器未运行，请先启动 Cloud 环境。"
    }
    $MigrationUser = (docker compose exec -T postgres printenv POSTGRES_USER).Trim()
    $DatabaseName = (docker compose exec -T postgres printenv POSTGRES_DB).Trim()
    docker compose exec -T postgres pg_dump --username $MigrationUser --dbname $DatabaseName --format=custom --file=/tmp/ai-jobpilot-backup.dump
    if ($LASTEXITCODE -ne 0) { throw "pg_dump 执行失败。" }
    docker cp "${ContainerId}:/tmp/ai-jobpilot-backup.dump" $OutputFull
    if ($LASTEXITCODE -ne 0) { throw "从容器复制备份失败。" }
    docker compose exec -T postgres rm -f /tmp/ai-jobpilot-backup.dump | Out-Null
} finally {
    Pop-Location
}

Write-Host "PostgreSQL 备份完成：$OutputFull" -ForegroundColor Green
