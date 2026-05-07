# dev-start.ps1 — Launch Synctuary in dev-plaintext mode (no TLS)
#
# Intended for LAN integration testing with the Android debug APK.
# Uses relative ./devdata/ directory for all state so it does not
# interfere with production data.
#
# Usage:
#   cd synctuary-server
#   .\deploy\dev-start.ps1            # defaults to :8080 (HTTP)
#   .\deploy\dev-start.ps1 -Port 9090 # custom port

param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

$GoRoot = "C:\Users\FileServer\sdk\go"
$env:PATH = "$GoRoot\bin;$env:PATH"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$DevData = Join-Path $ProjectRoot "devdata"

# Create data directories if missing.
foreach ($sub in @("files", "staging", "secret")) {
    $dir = Join-Path $DevData $sub
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}

# Build the binary.
Write-Host "[dev-start] Building synctuaryd..." -ForegroundColor Cyan
Push-Location $ProjectRoot
try {
    & go build -o synctuaryd.exe ./cmd/synctuaryd
    if ($LASTEXITCODE -ne 0) { throw "go build failed" }
} finally {
    Pop-Location
}

# Environment overrides — dev-plaintext (no TLS paths = HTTP mode).
$env:SYNCTUARY_SERVER_ADDR            = ":$Port"
$env:SYNCTUARY_SERVER_NAME            = "Synctuary-Dev"
$env:SYNCTUARY_SERVER_TLS_CERT_PATH   = ""
$env:SYNCTUARY_SERVER_TLS_KEY_PATH    = ""
$env:SYNCTUARY_STORAGE_ROOT_PATH      = Join-Path $DevData "files"
$env:SYNCTUARY_STORAGE_STAGING_PATH   = Join-Path $DevData "staging"
$env:SYNCTUARY_STORAGE_SECRET_PATH    = Join-Path $DevData "secret\master_key"
$env:SYNCTUARY_DATABASE_PATH          = Join-Path $DevData "meta.db"
$env:SYNCTUARY_LOG_LEVEL              = "debug"
$env:SYNCTUARY_LOG_FORMAT             = "text"

Write-Host "[dev-start] Starting on http://0.0.0.0:$Port (dev-plaintext)" -ForegroundColor Green
Write-Host "[dev-start] Data dir: $DevData" -ForegroundColor DarkGray
Write-Host "[dev-start] Press Ctrl+C to stop." -ForegroundColor DarkGray
Write-Host ""

# Run the server.
$exe = Join-Path $ProjectRoot "synctuaryd.exe"
& $exe
