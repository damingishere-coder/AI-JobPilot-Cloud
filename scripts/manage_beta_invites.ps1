param(
    [ValidateSet("Generate", "List", "Revoke")]
    [string]$Action = "List",
    [ValidateRange(1, 30)]
    [int]$ValidDays = 7,
    [ValidatePattern("^[A-Za-z0-9_-]{0,80}$")]
    [string]$Label = "",
    [string]$InviteId = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$DbName = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "ai_jobpilot" }
$DbOwner = if ($env:DB_MIGRATION_USERNAME) { $env:DB_MIGRATION_USERNAME } else { "jobpilot_owner" }

function Invoke-OwnerPsql {
    param([string]$Sql, [string[]]$Variables = @())
    $arguments = @(
        "compose", "exec", "-T", "postgres", "sh", "-lc",
        'export PGPASSWORD="$(cat /run/secrets/db_owner_password)"; exec psql "$@"',
        "sh", "-U", $DbOwner, "-d", $DbName, "-v", "ON_ERROR_STOP=1"
    ) + $Variables + @("-c", $Sql)
    & docker $arguments
    if ($LASTEXITCODE -ne 0) { throw "邀请码管理命令执行失败。" }
}

Push-Location $ProjectRoot
try {
    if (-not (docker compose ps -q postgres)) { throw "PostgreSQL 容器未运行。" }
    switch ($Action) {
        "Generate" {
            $bytes = New-Object byte[] 12
            [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
            $code = "BETA-" + (($bytes | ForEach-Object { $_.ToString("x2") }) -join "").ToUpperInvariant()
            $hashBytes = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($code))
            $hash = ($hashBytes | ForEach-Object { $_.ToString("x2") }) -join ""
            Invoke-OwnerPsql `
                "INSERT INTO app.beta_invites (code_hash, expires_at) VALUES (:'invite_hash', now() + make_interval(days => :'valid_days'::int)) RETURNING id, expires_at;" `
                @("-v", "invite_hash=$hash", "-v", "valid_days=$ValidDays")
            Write-Host "邀请码（仅此处显示一次）：$code" -ForegroundColor Green
            Write-Host "有效期：$ValidDays 天。请通过安全私聊单独发送给一名测试者。"
        }
        "Revoke" {
            $parsedId = [Guid]::Empty
            if (-not [Guid]::TryParse($InviteId, [ref]$parsedId)) { throw "Revoke 必须提供有效的 -InviteId。" }
            Invoke-OwnerPsql "UPDATE app.beta_invites SET revoked_at = now() WHERE id = :'invite_id'::uuid AND consumed_at IS NULL RETURNING id, revoked_at;" @("-v", "invite_id=$parsedId")
        }
        default {
            Invoke-OwnerPsql "SELECT id, expires_at, consumed_at, revoked_at FROM app.beta_invites ORDER BY created_at DESC LIMIT 50;"
        }
    }
} finally {
    Pop-Location
}
