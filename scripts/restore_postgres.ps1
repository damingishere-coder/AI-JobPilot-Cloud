[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "High")]
param(
    [Parameter(Mandatory = $true)][string]$BackupPath,
    [string]$TargetDatabase = ("ai_jobpilot_restore_{0}" -f (Get-Date -Format "yyyyMMddHHmmss")),
    [switch]$AllowProductionTarget
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$BackupRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot "backups"))
$BackupFull = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $BackupPath))
if (-not $BackupFull.StartsWith($BackupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "恢复文件必须位于项目 backups 目录内。"
}
if (-not (Test-Path -LiteralPath $BackupFull -PathType Leaf)) {
    throw "备份文件不存在：$BackupFull"
}
if ($TargetDatabase -notmatch '^ai_jobpilot(?:_restore_[a-zA-Z0-9_]+)?$') {
    throw "目标数据库名不合法；测试恢复请使用 ai_jobpilot_restore_ 开头。"
}
if ($TargetDatabase -eq "ai_jobpilot" -and -not $AllowProductionTarget) {
    throw "禁止默认覆盖主数据库；如确需执行，必须显式传入 -AllowProductionTarget 并确认 PowerShell 提示。"
}
if (-not $PSCmdlet.ShouldProcess($TargetDatabase, "删除同名数据库并从备份恢复")) { return }

Push-Location $ProjectRoot
try {
    $ContainerId = (docker compose ps -q postgres).Trim()
    if ([string]::IsNullOrWhiteSpace($ContainerId)) { throw "PostgreSQL 容器未运行。" }
    $MigrationUser = (docker compose exec -T postgres printenv POSTGRES_USER).Trim()
    docker cp $BackupFull "${ContainerId}:/tmp/ai-jobpilot-restore.dump"
    if ($LASTEXITCODE -ne 0) { throw "复制备份到容器失败。" }
    docker compose exec -T postgres dropdb --if-exists --force --username $MigrationUser $TargetDatabase
    if ($LASTEXITCODE -ne 0) { throw "清理同名测试数据库失败。" }
    docker compose exec -T postgres createdb --username $MigrationUser $TargetDatabase
    if ($LASTEXITCODE -ne 0) { throw "创建测试恢复数据库失败。" }
    docker compose exec -T postgres pg_restore --exit-on-error --username $MigrationUser --dbname $TargetDatabase /tmp/ai-jobpilot-restore.dump
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 恢复失败。" }
    docker compose exec -T postgres rm -f /tmp/ai-jobpilot-restore.dump | Out-Null
} finally {
    Pop-Location
}

Write-Host "恢复演练完成，目标数据库：$TargetDatabase" -ForegroundColor Green
