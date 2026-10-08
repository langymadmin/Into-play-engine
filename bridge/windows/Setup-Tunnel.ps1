# Give the engine a public address on your own domain, through a Cloudflare
# Tunnel - so a friend on any network opens https://play.<your domain> and
# plays against you. Run once:
#
#   powershell -ExecutionPolicy Bypass -File bridge\windows\Setup-Tunnel.ps1 -Hostname play.example.com
#
# Before it, two steps that are yours (they install a program and sign in to
# your Cloudflare account):
#   winget install Cloudflare.cloudflared
#   cloudflared tunnel login        (pick the domain in the browser)
#
# What it makes:
#   - a named tunnel "into-play" in your Cloudflare account;
#   - a DNS record: <Hostname> -> that tunnel;
#   - %USERPROFILE%\.cloudflared\into-play.yml: everything on <Hostname> goes
#     to the engine on this PC (http://localhost:8099) - the page, its socket,
#     the sounds.
# From then on Start-Into-Play.ps1 (the desktop icon) starts the tunnel with
# the engine and opens the table on the public address, so the invite and
# "Pair a phone" codes work from anywhere.
#
# Anyone with the address can open the page while the engine runs. To ask for
# a sign-in first, put the hostname behind Cloudflare Access (Zero Trust >
# Access > Applications) - free for small groups.
#
# ASCII only: Windows PowerShell 5.1 misreads anything else in a script.

param(
    [Parameter(Mandatory = $true)][string]$Hostname,
    [string]$Name = "into-play",
    [int]$Port = 8099
)

$ErrorActionPreference = "Stop"
function Fail($msg) { Write-Host "`n  $msg`n" -ForegroundColor Red; exit 1 }

$cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cf) {
    foreach ($p in "C:\Program Files (x86)\cloudflared\cloudflared.exe", "C:\Program Files\cloudflared\cloudflared.exe") {
        if (Test-Path $p) { $cf = $p }
    }
}
if (-not $cf) { Fail "cloudflared is not installed. Run: winget install Cloudflare.cloudflared - then open a new window." }

$home_cf = Join-Path $env:USERPROFILE ".cloudflared"
if (-not (Test-Path (Join-Path $home_cf "cert.pem"))) {
    Fail "Not signed in to Cloudflare yet. Run: cloudflared tunnel login - and pick your domain in the browser."
}

# The tunnel: reuse it if it exists (running this again is harmless).
$list = & $cf tunnel list --output json 2>$null | ConvertFrom-Json
$t = $list | Where-Object { $_.name -eq $Name } | Select-Object -First 1
if (-not $t) {
    Write-Host "  creating tunnel $Name" -ForegroundColor Cyan
    & $cf tunnel create $Name | Out-Host
    $list = & $cf tunnel list --output json 2>$null | ConvertFrom-Json
    $t = $list | Where-Object { $_.name -eq $Name } | Select-Object -First 1
}
if (-not $t) { Fail "Could not create the tunnel. See the message above." }
$id = $t.id
$cred = Join-Path $home_cf "$id.json"
if (-not (Test-Path $cred)) { Fail "The tunnel exists but its credentials file $cred is not on this PC. Delete the tunnel in the Cloudflare dashboard and run this again." }

# The address -> the tunnel. Fails harmlessly if the record is already there.
Write-Host "  pointing $Hostname at the tunnel" -ForegroundColor Cyan
& $cf tunnel route dns $Name $Hostname | Out-Host

$yml = Join-Path $home_cf "$Name.yml"
@"
tunnel: $id
credentials-file: $cred
ingress:
  - hostname: $Hostname
    service: http://localhost:$Port
  - service: http_status:404
"@ | Out-File -Encoding ascii $yml
Write-Host "  wrote $yml" -ForegroundColor Green
Write-Host "`n  Done. Double-click the Into Play icon: it starts the tunnel too and opens https://$Hostname/?ui=3`n" -ForegroundColor Green
