# Stop the engine that Start-Into-Play.ps1 started (it has no window to close).
#
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Stop-Into-Play.ps1

param([int]$Port = 8099)

$c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $c) { Write-Host "  nothing running on port $Port"; exit 0 }
$c | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Write-Host "  stopped the engine on port $Port" -ForegroundColor Green
