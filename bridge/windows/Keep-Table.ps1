# Keeps the table reachable while the engine runs. Started hidden by
# Start-Into-Play.ps1; ends on its own when the engine stops.
#
#   - the PC does not go to sleep while the engine is running (the screen
#     still may). Asleep, the engine and the tunnel stop answering, and a
#     friend playing from another network loses the game.
#   - the tunnel is started again if it stopped. cloudflared gives up and
#     exits when the network has been gone for a while (Wi-Fi dropped, the
#     PC woke up); without it play.into-play.com does not answer.
#
# ASCII only: Windows PowerShell 5.1 misreads anything else in a script.

param([int]$Port = 8099)

$logs = Join-Path (Split-Path -Parent $PSScriptRoot) "logs"
$tunnelYml = Join-Path $env:USERPROFILE ".cloudflared\into-play.yml"

Add-Type -Namespace IntoPlay -Name Power -MemberDefinition @"
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern uint SetThreadExecutionState(uint esFlags);
"@
# ES_CONTINUOUS | ES_SYSTEM_REQUIRED: stay awake while this thread lives.
$ES_AWAKE = [uint32]"0x80000001"

function Listening {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}
function Cloudflared {
    $cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
    if (-not $cf) {
        foreach ($p in "C:\Program Files (x86)\cloudflared\cloudflared.exe", "C:\Program Files\cloudflared\cloudflared.exe") {
            if (Test-Path $p) { $cf = $p }
        }
    }
    return $cf
}
function TunnelRunning {
    return [bool](Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*into-play.yml*" })
}

# The engine takes a minute or two to load its cards: wait for it first.
$deadline = (Get-Date).AddMinutes(5)
while (-not (Listening)) {
    if ((Get-Date) -gt $deadline) { exit 0 }
    Start-Sleep -Seconds 5
}

while (Listening) {
    [IntoPlay.Power]::SetThreadExecutionState($ES_AWAKE) | Out-Null
    if ((Test-Path $tunnelYml) -and -not (TunnelRunning)) {
        $cf = Cloudflared
        if ($cf) {
            Add-Content -Path (Join-Path $logs "keep-table.log") -Value "$(Get-Date -Format s) tunnel was down - starting it again"
            Start-Process -FilePath $cf -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $logs "tunnel.log") -RedirectStandardError (Join-Path $logs "tunnel-errors.log") `
                -ArgumentList @("tunnel", "--config", "`"$tunnelYml`"", "run")
        }
    }
    Start-Sleep -Seconds 20
}
