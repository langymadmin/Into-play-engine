# Stop the engine that Start-Into-Play.ps1 started (it has no window to close).
#
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Stop-Into-Play.ps1

param([int]$Port = 8099)

# The tunnel too, if Start-Into-Play started one.
Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*into-play.yml*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

$c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $c) { Write-Host "  nothing running on port $Port"; exit 0 }
$c | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Write-Host "  stopped the engine on port $Port" -ForegroundColor Green
