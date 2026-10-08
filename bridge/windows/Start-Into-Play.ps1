# One click: the engine, serving the table screen itself, and the browser.
#
#   Double-click the desktop icon (Install-Shortcut.ps1 makes it), or
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Start-Into-Play.ps1
#
# One process, no windows. The engine serves the app's built files
# (into-play\dist) on its own port, so there is no dev server to start:
#   tablet  http://localhost:8099/?ui=3
#   phone   http://<this PC>:8099/?ui=3&view=hand   ("Pair a phone" shows it)
#
# What it does, and only what is needed:
#   - the app is rebuilt (npm run build) only when its source is newer than
#     the last build - a quarter of a minute, while the cards load anyway;
#   - the engine starts hidden, its output in bridge\logs\engine.log. A running
#     engine keeps its game: clicking again just reopens the screen - unless
#     the engine has been updated since it started, when it offers to restart;
#   - then the browser opens the table.
# Stop-Into-Play.ps1 stops the engine (or end it from Task Manager: "java").
#
# Expects the two repositories side by side, as they were cloned:
#   ...\into play\into-play-engine   (this one)
#   ...\into play\into-play          (the app)
#
# ASCII only: Windows PowerShell 5.1 misreads anything else in a script.

param(
    [string]$App = "",
    [int]$Port = 8099,
    [string]$Heap = "2g",
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$engineRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $App) { $App = Join-Path (Split-Path -Parent $engineRoot) "into-play" }
$dist = Join-Path $App "dist"
$logs = Join-Path $engineRoot "bridge\logs"
$url = "http://localhost:$Port/?ui=3"

Add-Type -AssemblyName System.Windows.Forms
# Run hidden, so problems are said in a box rather than a console.
function Say($msg, $buttons = "OK", $icon = "Error") {
    return [System.Windows.Forms.MessageBox]::Show($msg, "Into Play", $buttons, $icon)
}
function Listening($port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}
function ServesApp {
    try { return (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://localhost:$Port/").StatusCode -eq 200 }
    catch { return $false }
}
function Open { if (-not $NoBrowser) { Start-Process $url } }

# --- the tunnel, when Setup-Tunnel.ps1 made one ------------------------------
# Started hidden beside the engine, once (it reconnects on its own if the
# engine restarts). The table then opens on the public address, so the invite
# and phone codes it shows work from any network.
$tunnelYml = Join-Path $env:USERPROFILE ".cloudflared\into-play.yml"
if (Test-Path $tunnelYml) {
    $hostLine = Select-String -Path $tunnelYml -Pattern "^\s*-\s*hostname:\s*(\S+)" | Select-Object -First 1
    $cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
    if (-not $cf) {
        foreach ($p in "C:\Program Files (x86)\cloudflared\cloudflared.exe", "C:\Program Files\cloudflared\cloudflared.exe") {
            if (Test-Path $p) { $cf = $p }
        }
    }
    if ($hostLine -and $cf) {
        $url = "https://$($hostLine.Matches[0].Groups[1].Value)/?ui=3"
        $ours = Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like "*into-play.yml*" }
        if (-not $ours) {
            New-Item -ItemType Directory -Force $logs | Out-Null
            Start-Process -FilePath $cf -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $logs "tunnel.log") -RedirectStandardError (Join-Path $logs "tunnel-errors.log") `
                -ArgumentList @("tunnel", "--config", "`"$tunnelYml`"", "run")
        }
    }
}

# --- already running? -------------------------------------------------------
function EngineProcess {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue }
}
function StopEngine {
    Get-NetTCPConnection -LocalPort $Port -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
    for ($i = 0; $i -lt 20 -and (Listening $Port); $i++) { Start-Sleep -Milliseconds 300 }
}
# The engine's own code (the bridge, and Forge classes patched locally), newest
# file first: compiled after the running engine started = it is out of date.
function EngineUpdatedAfter($started) {
    foreach ($d in (Join-Path $engineRoot "engine\forge\intoplay"), (Join-Path $engineRoot "engine-override")) {
        if ((Test-Path $d) -and (Get-ChildItem $d -Recurse -File -ErrorAction SilentlyContinue |
                Where-Object { $_.LastWriteTime -gt $started } | Select-Object -First 1)) { return $true }
    }
    return $false
}

if (Listening $Port) {
    if (ServesApp) {
        $proc = EngineProcess
        if ($proc -and (EngineUpdatedAfter $proc.StartTime)) {
            $a = Say "Into Play's engine has been updated since it started. Restart it now to use the new version? The current game ends." "YesNo" "Question"
            if ($a -eq "Yes") { StopEngine } else { Open; exit 0 }
        } else { Open; exit 0 }
    }
}
if (Listening $Port) {
    # An engine started the old way (Start-Bridge.ps1): it plays, but has no
    # screen to serve.
    $a = Say "An engine started the old way is running on port $Port. Restart it so it also serves the screen? Its current game ends." "YesNo" "Question"
    if ($a -ne "Yes") { exit 0 }
    StopEngine
}

# --- java -------------------------------------------------------------------
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) {
    # Temurin installs here without always touching PATH.
    $java = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.DirectoryName -like "*\bin" } | Select-Object -First 1).FullName
}
if (-not $java) { Say "No Java found. Install Java 21 (winget install EclipseAdoptium.Temurin.21.JRE) and try again."; exit 1 }
if (-not (Test-Path (Join-Path $engineRoot "engine"))) { Say "No 'engine' folder in $engineRoot. See bridge\windows\LOCAL-WINDOWS.md."; exit 1 }

# --- the engine (hidden; loads the cards while the app builds) --------------
New-Item -ItemType Directory -Force $logs | Out-Null
$cp = "engine\*;engine"
if (Test-Path (Join-Path $engineRoot "engine-override")) { $cp = "engine-override;$cp" }
# Quoted by hand: Start-Process joins these into one line, and the folder
# ("into play") has a space in it.
Start-Process -FilePath $java -WindowStyle Hidden -WorkingDirectory $engineRoot `
    -RedirectStandardOutput (Join-Path $logs "engine.log") -RedirectStandardError (Join-Path $logs "engine-errors.log") `
    -ArgumentList @("-Xmx$Heap", "`"-Dintoplay.app=$dist`"", "-cp", "`"$cp`"", "forge.intoplay.BridgeMain", "forge-gui\res", $Port)

# --- the app: rebuilt only when it changed ----------------------------------
if (-not (Test-Path (Join-Path $App "package.json"))) {
    Say "No into-play app at $App. Clone it next to into-play-engine."; exit 1
}
$built = Join-Path $dist "index.html"
$stale = -not (Test-Path $built)
if (-not $stale) {
    $at = (Get-Item $built).LastWriteTime
    $stale = [bool](Get-ChildItem (Join-Path $App "src"), (Join-Path $App "public") -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -gt $at } | Select-Object -First 1)
    foreach ($f in "index.html", "package.json", "vite.config.js") {
        $p = Join-Path $App $f
        if ((Test-Path $p) -and (Get-Item $p).LastWriteTime -gt $at) { $stale = $true }
    }
}
if ($stale) {
    # npm.cmd, not npm: PowerShell refuses npm.ps1 under the default policy.
    $npm = "cd /d `"$App`" && (if not exist node_modules npm.cmd install) && npm.cmd run build"
    $b = Start-Process cmd -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput (Join-Path $logs "build.log") -RedirectStandardError (Join-Path $logs "build-errors.log") `
        -ArgumentList @("/c", $npm)
    if ($b.ExitCode -ne 0 -and -not (Test-Path $built)) {
        Say "Building the screen failed. See $logs\build-errors.log."; exit 1
    }
}

# --- wait, then open --------------------------------------------------------
$deadline = (Get-Date).AddSeconds(120)
while (-not (Listening $Port)) {
    if ((Get-Date) -gt $deadline) { Say "The engine did not start in two minutes. See $logs\engine-errors.log."; exit 1 }
    Start-Sleep -Milliseconds 700
}
Open
