# ============================================================
# 构建带版本信息的应用镜像（Windows）
#   .\docker\build-image.ps1                 # 默认 tag = sha-<git短sha>
#   .\docker\build-image.ps1 -Tag 0.1.0      # 发布版：tag = 0.1.0
#   .\docker\build-image.ps1 -AliasLatest    # 额外打 latest（仅用于正式发布）
#
# 版本策略：镜像 tag 与 APP_VERSION/GIT_SHA 构建参数同时落 OCI labels；
#           compose 按 IMAGE_TAG 选择版本，回滚 = 改回旧 tag + up -d --no-build。
# ============================================================
param(
    [string]$Tag = "",
    [switch]$AliasLatest
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Get-GitSha {
    try {
        $sha = git rev-parse --short=8 HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $sha) { return $sha.Trim() }
    } catch { }
    return "unknown"
}

function Get-ProjectVersion {
    try {
        $pom = Get-Content (Join-Path $Root "pom.xml") -Raw
        if ($pom -match '(?s)<artifactId>javaHarness</artifactId>\s*<version>([^<]+)</version>') {
            return $Matches[1].Trim()
        }
    } catch { }
    return "dev"
}

$sha = Get-GitSha
$version = Get-ProjectVersion
$imageTag = if ($Tag) { $Tag } else { "sha-$sha" }
$image = "java-harness:$imageTag"

$buildArgs = @(
    "build",
    "-f", "docker/Dockerfile",
    "-t", $image,
    "--build-arg", "APP_VERSION=$version",
    "--build-arg", "GIT_SHA=$sha"
)
if ($env:BUILD_BASE) { $buildArgs += @("--build-arg", "BUILD_BASE=$env:BUILD_BASE") }
if ($env:RUN_BASE) { $buildArgs += @("--build-arg", "RUN_BASE=$env:RUN_BASE") }
$buildArgs += "."

Write-Host "Building $image (version=$version, sha=$sha) ..."
& docker @buildArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($AliasLatest) {
    & docker tag $image "java-harness:latest"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "Aliased java-harness:latest -> $image"
}

Write-Host ""
Write-Host "Done. Start / upgrade:"
Write-Host "  `$env:IMAGE_TAG='$imageTag'; docker compose -f docker/docker-compose.yml up -d --no-build"
Write-Host "Rollback: set IMAGE_TAG to the previous tag and re-run the same up command."
