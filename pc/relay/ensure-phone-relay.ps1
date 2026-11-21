# Start the Hermes phone relay (:8643) if it isn't listening. Safe to run repeatedly.
# Run by the "Hermes_PhoneRelay" scheduled task: at logon, on resume from sleep/hibernate, every 5 min.
$port = 8643
$launchers = "$env:LOCALAPPDATA\hermes\launchers"
$py = "$env:LOCALAPPDATA\hermes\hermes-agent\venv\Scripts\pythonw.exe"
$script = "$launchers\hermes-phone-relay.py"

if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { exit 0 }

# A relay process that is alive but not listening yet is still starting: leave it alone.
$running = Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*hermes-phone-relay.py*" }
if ($running) { exit 0 }

# After resume, give Tailscale up to 60 s to bring its 100.x address back (the phone usually connects through it).
for ($i = 0; $i -lt 60; $i++) {
    if (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like "100.*" }) { break }
    Start-Sleep -Seconds 1
}
Remove-Item Env:HERMES_HOME, Env:HERMES_PROFILE -ErrorAction SilentlyContinue
Start-Process -FilePath $py -ArgumentList "-I", "`"$script`"" -WorkingDirectory $launchers -WindowStyle Hidden
