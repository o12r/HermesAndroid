# Bring Tailscale up: start the service and the tray client, then connect (opening the login page if it needs a login).
# Started hidden by hermes-tray.ps1 (at most once a minute while Tailscale is down). The Tailscale service lets
# normal users start it (SERVICE_START for BUILTIN\Users), so no elevation is needed.
# Writes "ok" or "fail: <reason>" to tailscale-connect.result for the tray to report.
$ErrorActionPreference = "Continue"
$Launch = Join-Path $env:LOCALAPPDATA "hermes\launchers"
$Result = Join-Path $Launch "tailscale-connect.result"
$Opened = Join-Path $Launch "tailscale-connect.authurl"    # last login URL opened, so it is opened once
$Log    = Join-Path $Launch "tailscale-connect.log"
function L($m) { Add-Content $Log "$(Get-Date -Format s) $m" }
function Done($r) { L $r; Set-Content $Result $r -Encoding UTF8; exit 0 }

$dir = Join-Path $env:ProgramFiles "Tailscale"
$ts = Join-Path $dir "tailscale.exe"
if (-not (Test-Path $ts)) { $c = Get-Command tailscale -ErrorAction SilentlyContinue; if ($c) { $ts = $c.Source; $dir = Split-Path $ts } else { Done "fail: Tailscale is not installed" } }
function St { try { & $ts status --json 2>$null | Out-String | ConvertFrom-Json } catch { $null } }

$svc = Get-Service Tailscale -ErrorAction SilentlyContinue
if (-not $svc) { Done "fail: Tailscale service not found" }
if ($svc.Status -ne "Running") {
    L "starting service"
    try { Start-Service Tailscale -ErrorAction Stop } catch { Done "fail: could not start the Tailscale service: $($_.Exception.Message)" }
    $svc.WaitForStatus("Running", [TimeSpan]::FromSeconds(20))
}
$ipn = Join-Path $dir "tailscale-ipn.exe"
if ((Test-Path $ipn) -and -not (Get-Process tailscale-ipn -ErrorAction SilentlyContinue)) { L "starting tailscale-ipn"; Start-Process $ipn }

for ($i = 0; $i -lt 10; $i++) { $s = St; if ($s -and $s.BackendState -ne "NoState" -and $s.BackendState -ne "Starting") { break }; Start-Sleep 1 }
if ($s.BackendState -eq "Running") { Done "ok" }

function Open-Login($url) {
    if ($url -and (-not (Test-Path $Opened) -or (Get-Content $Opened -Raw).Trim() -ne $url)) {
        L "opening login page"; Set-Content $Opened $url; Start-Process $url
    }
}

if ($s.BackendState -eq "NeedsLogin") {
    # `tailscale up` starts the login and blocks until it finishes; run it in the background and open the URL it gets.
    if (-not $s.AuthURL) { Start-Process $ts -ArgumentList "up", "--timeout=300s" -WindowStyle Hidden }
    for ($i = 0; $i -lt 30 -and -not $s.AuthURL; $i++) { Start-Sleep 1; $s = St; if ($s.BackendState -eq "Running") { Done "ok" } }
    if ($s.AuthURL) { Open-Login $s.AuthURL; Done "fail: Tailscale needs a login (login page opened)" }
    Done "fail: Tailscale needs a login, but no login URL came back"
}

# Stopped (disconnected): connect. If non-default settings are in use, `tailscale up` refuses and prints the full
# command that keeps them; run that one. Tailscale's suggestion can itself be refused when a leftover exit-node
# option is set without an exit node ("--x can only be used with --exit-node"); then pass --x=false, which is inert
# without an exit node.
L "tailscale up (state $($s.BackendState))"
$argv = @("--timeout=30s")
for ($try = 0; $try -lt 3; $try++) {
    $out = & $ts up @argv 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) { break }
    $keep = ($out -split "`r?`n" | Where-Object { $_ -match '^\s*tailscale up\b' } | Select-Object -First 1)
    if ($keep) { $argv = @(($keep.Trim() -split '\s+') | Select-Object -Skip 2) }
    elseif ($out -match '(--[\w-]+) can only be used with --exit-node') {
        $f = $Matches[1]; $argv = @($argv | Where-Object { $_ -ne $f -and $_ -notlike "$f=*" }) + "$f=false"
    } else { break }
    if ($argv -notcontains "--timeout=30s" -and -not ($argv | Where-Object { $_ -like "--timeout*" })) { $argv += "--timeout=30s" }
    L "retry: tailscale up $($argv -join ' ')"
}
$s = St
if ($s.BackendState -eq "Running") { Done "ok" }
if ($s.AuthURL) { Open-Login $s.AuthURL; Done "fail: Tailscale needs a login (login page opened)" }
Done ("fail: " + (($out.Trim() -split "`r?`n" | Select-Object -Last 1) + " (state $($s.BackendState))"))
