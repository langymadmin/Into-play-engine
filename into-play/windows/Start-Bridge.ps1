# Start the Into Play engine bridge on Windows.
#
#   powershell -ExecutionPolicy Bypass -File into-play\windows\Start-Bridge.ps1
#
# No Maven, no bash. You need two things in place first:
#
#   1. This repository, cloned (for forge-gui\res — the 34,000 card scripts,
#      which are NOT in the build artifact).
#   2. An "engine" folder of jars, either from the CI artifact or from a local
#      Maven build. See LOCAL-WINDOWS.md.
#
# The one Windows-specific thing that bites: the classpath separator is a
# SEMICOLON here and a colon everywhere else, so a command copied from the bash
# scripts will fail with "Could not find or load main class" and no other clue.

param(
    [int]$Port = 8099,
    [string]$EngineDir = "engine",
    [string]$Res = "forge-gui\res",
    [string]$Heap = "2g"
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

function Fail($msg) { Write-Host "`n  $msg`n" -ForegroundColor Red; exit 1 }

# --- java -------------------------------------------------------------------
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Fail "No java on PATH. Install a Java 21 runtime (Temurin: winget install EclipseAdoptium.Temurin.21.JRE) and reopen this window."
}
$ver = (& java -version 2>&1 | Select-Object -First 1)
Write-Host "  java    $ver" -ForegroundColor DarkGray

# --- the jars ---------------------------------------------------------------
if (-not (Test-Path $EngineDir)) {
    Fail "No '$EngineDir' folder. Download the 'into-play-engine' artifact from the latest green Engine build run and unzip it here. See into-play\windows\LOCAL-WINDOWS.md."
}
$jars = @(Get-ChildItem -Path $EngineDir -Filter *.jar)
if ($jars.Count -lt 10) {
    Fail "'$EngineDir' has only $($jars.Count) jar(s); a complete engine has a few hundred. Re-download the artifact."
}
Write-Host "  engine  $($jars.Count) jars in $EngineDir" -ForegroundColor DarkGray

# --- the card scripts -------------------------------------------------------
if (-not (Test-Path (Join-Path $Res "cardsfolder"))) {
    Fail "No '$Res\cardsfolder'. The card scripts are not in the artifact — they come from this repository, so run this from a full clone."
}
$cards = (Get-ChildItem -Path (Join-Path $Res "cardsfolder") -Recurse -Filter *.txt).Count
Write-Host "  cards   $cards scripts in $Res" -ForegroundColor DarkGray

# --- go ---------------------------------------------------------------------
# Semicolon, not colon. The ".\engine\*" wildcard is Java's own jar-directory
# syntax; it is not a shell glob, so it must stay quoted.
$cp = "$EngineDir\*;$EngineDir"

Write-Host "`n  starting the bridge on ws://localhost:$Port/play" -ForegroundColor Cyan
Write-Host "  (first start loads 34k card scripts — about five seconds)`n" -ForegroundColor DarkGray

& java "-Xmx$Heap" -cp $cp forge.intoplay.BridgeMain $Res $Port
