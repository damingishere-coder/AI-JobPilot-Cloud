param([string]$VersionLabel = "v1.6.0-beta")

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ExtensionRoot = Join-Path $ProjectRoot "chrome-extension"
$OutputRoot = Join-Path $ProjectRoot "tmp/beta-extension"
if ($VersionLabel -notmatch '^v?[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$') {
    throw "VersionLabel 格式不合法。"
}

Push-Location $ProjectRoot
try {
    node scripts/validate-chrome-extension.mjs
    if ($LASTEXITCODE -ne 0) { throw "Chrome 插件校验失败。" }
    New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
    $zipPath = Join-Path $OutputRoot "AI-JobPilot-Cloud-$VersionLabel-chrome-extension.zip"
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath }
    $staging = Join-Path $OutputRoot ("staging-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $staging | Out-Null
    $files = Get-ChildItem -LiteralPath $ExtensionRoot -File -Recurse | Where-Object {
        $_.FullName -notlike "*\tests\*" -and $_.Name -notlike "zhilian-*"
    }
    foreach ($file in $files) {
        $relative = [IO.Path]::GetRelativePath($ExtensionRoot, $file.FullName)
        $destination = [IO.Path]::GetFullPath((Join-Path $staging $relative))
        if (-not $destination.StartsWith([IO.Path]::GetFullPath($staging) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "插件打包路径越界：$relative"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination
    }
    [IO.Compression.ZipFile]::CreateFromDirectory($staging, $zipPath, [IO.Compression.CompressionLevel]::Optimal, $false)
    $resolvedStaging = [IO.Path]::GetFullPath($staging)
    $resolvedOutputRoot = [IO.Path]::GetFullPath($OutputRoot)
    if ($resolvedStaging.StartsWith($resolvedOutputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
    $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $hashPath = Join-Path $OutputRoot "SHA256SUMS.txt"
    Set-Content -LiteralPath $hashPath -Encoding Ascii -Value "$hash  $([IO.Path]::GetFileName($zipPath))"
    Write-Host "插件包：$zipPath" -ForegroundColor Green
    Write-Host "SHA-256：$hash"
} finally {
    Pop-Location
}
