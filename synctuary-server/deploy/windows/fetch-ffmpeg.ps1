# fetch-ffmpeg.ps1 - Download static ffmpeg/ffprobe for the Synctuary installer.
#
# ASCII ENGLISH ONLY (PowerShell 5.1 reads BOM-less UTF-8 as CP932 and
# corrupts multibyte characters -> parser errors). Do not add non-ASCII text.
#
# Downloads a static Windows ffmpeg build, extracts ffmpeg.exe / ffprobe.exe
# and the build's LICENSE (+ README if present) into deploy\windows\ffmpeg\
# in the layout synctuary.iss expects:
#
#   deploy\windows\ffmpeg\bin\ffmpeg.exe
#   deploy\windows\ffmpeg\bin\ffprobe.exe
#   deploy\windows\ffmpeg\LICENSE
#   deploy\windows\ffmpeg\README.txt   (if the build ships one)
#
# ffmpeg is a separate GPL-licensed program invoked by the server as an
# external process; its LICENSE is shipped alongside the binaries.
#
# Usage:
#   .\fetch-ffmpeg.ps1            # skip download if exes already present
#   .\fetch-ffmpeg.ps1 -Force     # re-download even if present

[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'  # much faster Invoke-WebRequest

# Resolve paths relative to this script so it works from any CWD.
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FfmpegDir = Join-Path $ScriptDir 'ffmpeg'
$BinDir    = Join-Path $FfmpegDir 'bin'
$FfmpegExe = Join-Path $BinDir 'ffmpeg.exe'
$FfprobeExe = Join-Path $BinDir 'ffprobe.exe'

# Primary and fallback download URLs (both GPL static win64 builds).
$PrimaryUrl  = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
$FallbackUrl = 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip'

# Idempotency: skip when both exes already exist unless -Force.
if ((Test-Path $FfmpegExe) -and (Test-Path $FfprobeExe) -and (-not $Force)) {
    Write-Host "ffmpeg.exe and ffprobe.exe already present in $BinDir"
    Write-Host "Use -Force to re-download."
    exit 0
}

# Fresh workspace.
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

$TempZip = Join-Path ([System.IO.Path]::GetTempPath()) ("ffmpeg-{0}.zip" -f ([System.Guid]::NewGuid().ToString('N')))
$ExtractDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ffmpeg-extract-{0}" -f ([System.Guid]::NewGuid().ToString('N')))

function Get-Zip {
    param([string]$Url, [string]$OutFile)
    Write-Host "Downloading $Url"
    Write-Host "(static ffmpeg is ~80-100 MB; this can take a while)"
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 900
}

$downloaded = $false
try {
    Get-Zip -Url $PrimaryUrl -OutFile $TempZip
    $downloaded = $true
} catch {
    Write-Warning "Primary download failed: $($_.Exception.Message)"
    Write-Host "Trying fallback: $FallbackUrl"
    try {
        Get-Zip -Url $FallbackUrl -OutFile $TempZip
        $downloaded = $true
    } catch {
        Write-Error "Both downloads failed. Last error: $($_.Exception.Message)"
        exit 1
    }
}

if (-not $downloaded) {
    Write-Error "Download did not complete."
    exit 1
}

try {
    Write-Host "Extracting archive..."
    if (Test-Path $ExtractDir) { Remove-Item -Recurse -Force $ExtractDir }
    Expand-Archive -Path $TempZip -DestinationPath $ExtractDir -Force

    # Both gyan and BtbN builds nest binaries under <root>\bin\.
    $foundFfmpeg = Get-ChildItem -Path $ExtractDir -Recurse -Filter 'ffmpeg.exe' | Select-Object -First 1
    $foundFfprobe = Get-ChildItem -Path $ExtractDir -Recurse -Filter 'ffprobe.exe' | Select-Object -First 1

    if ($null -eq $foundFfmpeg -or $null -eq $foundFfprobe) {
        Write-Error "Extracted archive did not contain ffmpeg.exe and ffprobe.exe."
        exit 1
    }

    Copy-Item -Path $foundFfmpeg.FullName -Destination $FfmpegExe -Force
    Copy-Item -Path $foundFfprobe.FullName -Destination $FfprobeExe -Force
    Write-Host "Copied ffmpeg.exe and ffprobe.exe to $BinDir"

    # LICENSE lives at the archive root (gyan) or LICENSE.txt / LICENSE (BtbN).
    $license = Get-ChildItem -Path $ExtractDir -Recurse -Include 'LICENSE', 'LICENSE.txt', 'COPYING.GPLv3' |
        Select-Object -First 1
    if ($null -ne $license) {
        Copy-Item -Path $license.FullName -Destination (Join-Path $FfmpegDir 'LICENSE') -Force
        Write-Host "Copied LICENSE to $FfmpegDir"
    } else {
        Write-Warning "No LICENSE file found in the archive; the installer will ship without it."
    }

    # README (gyan ships README.txt) - optional.
    $readme = Get-ChildItem -Path $ExtractDir -Recurse -Include 'README.txt', 'README' |
        Select-Object -First 1
    if ($null -ne $readme) {
        Copy-Item -Path $readme.FullName -Destination (Join-Path $FfmpegDir 'README.txt') -Force
        Write-Host "Copied README to $FfmpegDir"
    }

    # Verify the fetched binary actually runs. Capture stdout only (do NOT
    # redirect native stderr with 2>&1 under -ErrorActionPreference Stop, and
    # avoid Select-Object early pipeline termination, both of which can throw
    # PipelineStoppedException and corrupt the exit code in PowerShell 5.1).
    Write-Host "Verifying ffmpeg.exe..."
    $verLines = & $FfmpegExe -version
    if ($verLines -and $verLines.Count -gt 0) {
        Write-Host $verLines[0]
    }
    Write-Host ""
    Write-Host "Done. ffmpeg is ready in $BinDir"
    Write-Host "Now compile the installer with ISCC.exe to include the ffmpeg component."
}
finally {
    if (Test-Path $TempZip) { Remove-Item -Force $TempZip -ErrorAction SilentlyContinue }
    if (Test-Path $ExtractDir) { Remove-Item -Recurse -Force $ExtractDir -ErrorAction SilentlyContinue }
}
