# Stop the engine that Start-Into-Play.ps1 started (it has no window to close).
#
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Stop-Into-Play.ps1

param([int]$Port = 8099)

# The keeper first (Keep-Table.ps1), or it starts the tunnel again.
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*Keep-Table.ps1*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# The tunnel too, if Start-Into-Play started one.
Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*into-play.yml*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

$c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $c) { Say "Into Play was not running."; exit 0 }
$c | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Say "Into Play stopped. A game in progress was saved - the next start offers to resume it."
