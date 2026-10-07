# Put an "Into Play" icon on the desktop that runs Start-Into-Play.ps1.
#
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Install-Shortcut.ps1
#
# Run once. Double-clicking the icon then starts whatever is not running yet
# (engine, screen) and opens the table - see Start-Into-Play.ps1.

$ErrorActionPreference = "Stop"
$start = Join-Path $PSScriptRoot "Start-Into-Play.ps1"
$desktop = [Environment]::GetFolderPath("Desktop")
$link = Join-Path $desktop "Into Play.lnk"

$shell = New-Object -ComObject WScript.Shell
$s = $shell.CreateShortcut($link)
$s.TargetPath = "powershell.exe"
$s.Arguments = "-ExecutionPolicy Bypass -WindowStyle Minimized -File `"$start`""
$s.WorkingDirectory = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$s.Description = "Start the Into Play engine and table screen"
# The app's own icon, when there is one next to it.
$ico = Join-Path (Split-Path -Parent $s.WorkingDirectory) "into-play\public\favicon.ico"
if (Test-Path $ico) { $s.IconLocation = $ico }
$s.Save()
Write-Host "  made $link" -ForegroundColor Green
