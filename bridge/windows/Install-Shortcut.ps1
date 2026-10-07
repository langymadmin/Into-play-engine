# Put an "Into Play" icon on the desktop.
#
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Install-Shortcut.ps1
#   ... -Startup     also start the engine when Windows starts (no browser)
#
# Run once. Double-clicking the icon starts the engine if it is not running -
# hidden, no windows - and opens the table (see Start-Into-Play.ps1). It goes
# through Start-Into-Play.vbs, which is what keeps even the console flash away.

param([switch]$Startup)

$ErrorActionPreference = "Stop"
$vbs = Join-Path $PSScriptRoot "Start-Into-Play.vbs"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$shell = New-Object -ComObject WScript.Shell

function Make($link, $extra, $what) {
    $s = $shell.CreateShortcut($link)
    $s.TargetPath = "wscript.exe"
    $s.Arguments = "`"$vbs`"$extra"
    $s.WorkingDirectory = $root
    $s.Description = $what
    # The app's own icon, when there is one next to it.
    $ico = Join-Path (Split-Path -Parent $root) "into-play\public\favicon.ico"
    if (Test-Path $ico) { $s.IconLocation = $ico }
    $s.Save()
    Write-Host "  made $link" -ForegroundColor Green
}

Make (Join-Path ([Environment]::GetFolderPath("Desktop")) "Into Play.lnk") "" "Start Into Play and open the table"
if ($Startup) {
    Make (Join-Path ([Environment]::GetFolderPath("Startup")) "Into Play engine.lnk") " -NoBrowser" "Start the Into Play engine at sign-in"
}
