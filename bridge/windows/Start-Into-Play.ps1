# One click: the engine, the screen, and the browser on the table.
#
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Start-Into-Play.ps1
#
# Or double-click the desktop icon that Install-Shortcut.ps1 makes.
#
# Starts whatever is not already running, and only that:
#   - the engine (Start-Bridge.ps1) in its own minimized window, so its log is
#     there if something goes wrong. A running engine keeps its game: clicking
#     again just reopens the screen, it does not deal a new one.
#   - the screen server (into-play's "npm run dev") with --host, so a phone on
#     the same Wi-Fi or hotspot can open its hand ("Pair a phone" in the menu).
# Then opens the table screen.
#
# Expects the two repositories side by side, as they were cloned:
#   ...\into play\into-play-engine   (this one)
#   ...\into play\into-play          (the app)

param(
    [string]$App = "",
    [int]$EnginePort = 8099,
    [int]$ScreenPort = 5173,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$engineRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $App) { $App = Join-Path (Split-Path -Parent $engineRoot) "into-play" }

function Listening($port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}
function Fail($msg) {
    Write-Host "`n  $msg`n" -ForegroundColor Red
    Read-Host "Press Enter to close"
    exit 1
}

# --- the engine -------------------------------------------------------------
if (Listening $EnginePort) {
    Write-Host "  engine  already running on port $EnginePort - its game is kept" -ForegroundColor DarkGray
} else {
    Write-Host "  engine  starting (about half a minute to load the cards)..." -ForegroundColor Cyan
    Start-Process powershell -WindowStyle Minimized -WorkingDirectory $engineRoot -ArgumentList @(
        # Quoted by hand: Start-Process passes these as one line, and the
        # folder ("into play") has a space in it.
        "-NoExit", "-ExecutionPolicy", "Bypass", "-File", "`"$(Join-Path $PSScriptRoot 'Start-Bridge.ps1')`"", "-Port", $EnginePort)
}

# --- the screen -------------------------------------------------------------
if (Listening $ScreenPort) {
    Write-Host "  screen  already running on port $ScreenPort" -ForegroundColor DarkGray
} else {
    if (-not (Test-Path (Join-Path $App "package.json"))) {
        Fail "No into-play app at $App. Clone it next to into-play-engine, or pass -App <folder>."
    }
    if (-not (Test-Path (Join-Path $App "node_modules"))) {
        Write-Host "  screen  first run: installing packages..." -ForegroundColor Cyan
        Push-Location $App; & npm.cmd install; Pop-Location
    }
    Write-Host "  screen  starting..." -ForegroundColor Cyan
    # npm.cmd, not npm: PowerShell refuses npm.ps1 under the default policy.
    Start-Process cmd -WindowStyle Minimized -WorkingDirectory $App -ArgumentList @(
        "/k", "npm.cmd run dev -- --host --port $ScreenPort --strictPort")
}

# --- wait, then open --------------------------------------------------------
$deadline = (Get-Date).AddSeconds(120)
while (-not ((Listening $EnginePort) -and (Listening $ScreenPort))) {
    if ((Get-Date) -gt $deadline) {
        Fail "Still not up after two minutes. Look at the two minimized windows for the reason."
    }
    Start-Sleep -Milliseconds 700
}
$url = "http://localhost:$ScreenPort/?ui=3&bridge=ws://localhost:$EnginePort/play"
Write-Host "  open    $url" -ForegroundColor Green
if (-not $NoBrowser) { Start-Process $url }
