# Installs SudoVDA (SudoMaker, MIT; signed build from Apollo v0.4.6, unchanged) for Hermes' virtual monitors.
# Every monitor (per-session agent screens, screen-off screens) is created on demand by hermes-vdisplay and removed
# when no longer needed, so nothing sits idle and the mouse has nowhere to wander.
# Also removes the older VirtualDrivers MttVDD device, but only when Hermes-legacy evidence exists (see below).
# Ownership: the SudoVDA device is removed on -Uninstall only if Hermes installed it (marker sudovda-owned.json
# in %LOCALAPPDATA%\hermes\vdisplay, written on install when no such device existed before). -Force removes it regardless.
#   https://github.com/SudoMaker/SudoVDA   (driver, MIT)
#   https://github.com/nefarius/nefcon     (device-node installer, MIT)
# Needs admin (one UAC prompt). Run with -Uninstall to remove the device.
param([switch]$Uninstall, [switch]$TaskOnly, [switch]$Force,
      [string]$Python = "$env:LOCALAPPDATA\hermes\hermes-agent\venv\Scripts\pythonw.exe",
      [string]$Cli = "$env:LOCALAPPDATA\hermes\launchers\hermes-vdisplay.py")
$ErrorActionPreference = "Stop"

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $a = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"",
           "-Python", "`"$Python`"", "-Cli", "`"$Cli`"")
    if ($Uninstall) { $a += "-Uninstall" }
    if ($TaskOnly) { $a += "-TaskOnly" }
    if ($Force) { $a += "-Force" }
    Start-Process powershell -Verb RunAs -ArgumentList $a -Wait
    exit
}

$Task = "HermesVDisplayGuard"
function Register-Guard {
# The daemon (owns the virtual monitors, keeps non-agent windows off the agent screens) runs elevated on demand, so it can
    # also move admin windows (UIPI blocks a normal process). hermes-vdisplay starts it with (re-registering is idempotent: -Force): schtasks /run /tn $Task
    $act = New-ScheduledTaskAction -Execute $Python -Argument "-I `"$Cli`" _daemon"
    $pr  = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Highest
    $set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $Task -Action $act -Principal $pr -Settings $set -Force | Out-Null
}
if ($TaskOnly) { Register-Guard; Write-Host "Guard task registered." -ForegroundColor Green; Start-Sleep 2; exit }

$NefUrl = "https://github.com/nefarius/nefcon/releases/download/v1.21.1/nefcon_v1.21.1.zip"
$NefSha = "28292f4b1417484db6ad23c47ffc18ea99d3de3bdb3bbbe60c8ed11703a3780a"
$Work   = Join-Path $env:LOCALAPPDATA "hermes\vdisplay"
$Class  = "4D36E968-E325-11CE-BFC1-08002BE10318"

function Get-Pinned($url, $sha, $out) {
    if (-not (Test-Path $out) -or (Get-FileHash $out -Algorithm SHA256).Hash.ToLower() -ne $sha) {
        Write-Host "Downloading $url"
        Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
    }
    $h = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
    if ($h -ne $sha) { Remove-Item $out -Force; throw "Checksum mismatch for $url ($h)" }
}

New-Item -ItemType Directory -Force -Path $Work | Out-Null
Get-Pinned $NefUrl $NefSha (Join-Path $Work "nefcon.zip")
Expand-Archive (Join-Path $Work "nefcon.zip") (Join-Path $Work "nefcon") -Force
$Nef = Join-Path $Work "nefcon\x64\nefconc.exe"

# Legacy MttVDD (agent screen of Hermes <= 0.2.28): only touched when Hermes-legacy evidence exists, so a
# MttVDD installed by the user for something else is never removed.
$Marker = Join-Path $Work "sudovda-owned.json"
$LegacyEvidence = (Test-Path "C:\VirtualDisplayDriver\hermes-owned") -or (Test-Path (Join-Path $Work "driver")) -or
    (Test-Path (Join-Path $Work "agent-screens.json")) -or (Test-Path (Join-Path $Work "agent-requests.json")) -or
    (Test-Path (Join-Path $Work "guard.pid"))
if ($LegacyEvidence) {
    if (Get-PnpDevice -Class Display -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains "Root\MttVDD" }) {
        Write-Host "Removing the old MttVDD virtual display"
        & $Nef --remove-device-node --hardware-id "Root\MttVDD" --class-guid $Class
    }
    pnputil /enum-drivers | Select-String -Context 1,0 "mttvdd.inf" | ForEach-Object {
        if ($_.Context.PreContext[0] -match "(oem\d+\.inf)") { pnputil /delete-driver $Matches[1] /uninstall /force | Out-Null }
    }
    Remove-Item "C:\VirtualDisplayDriver", (Join-Path $Work "driver"), (Join-Path $Work "driver.zip"),
        (Join-Path $Work "agent-screens.json"), (Join-Path $Work "agent-requests.json"), (Join-Path $Work "guard.pid") -Recurse -Force -ErrorAction SilentlyContinue
}

function Test-SudoVda { [bool](Get-PnpDevice -Class Display -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains "root\sudomaker\sudovda" }) }

$Task = "HermesVDisplayGuard"
if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $Task -Confirm:$false -ErrorAction SilentlyContinue
    if (Test-Path $Marker) {
        & $Nef --remove-device-node --hardware-id "root\sudomaker\sudovda" --class-guid $Class
        Remove-Item $Marker -Force -ErrorAction SilentlyContinue
        Write-Host "Virtual display removed." -ForegroundColor Green
    } elseif ($Force) {
        & $Nef --remove-device-node --hardware-id "root\sudomaker\sudovda" --class-guid $Class
        Write-Host "Virtual display removed (-Force)." -ForegroundColor Green
    } elseif ((Test-SudoVda) -and (Test-Path (Join-Path $env:LOCALAPPDATA "hermes\launchers\sudovda"))) {
        Write-Host "WARNING: SudoVDA exists and Hermes files are present, but there is no ownership marker (installed by an older Hermes?). Kept. Re-run with -Force to remove it anyway." -ForegroundColor Yellow
    } else {
        Write-Host "SudoVDA was not installed by Hermes; kept"
    }
    Start-Sleep 3; exit
}

$Sv = Join-Path $PSScriptRoot "sudovda"
$SvSha = @{ "SudoVDA.inf" = "ad69ac682756f0cf339b081fac7e6e8159fdf2ca01ca69df8945c7246c286925"
            "sudovda.cat" = "2f9189de5604bec9d86f51640cc540639e394d9ad0f8e689129375e95f2d22f8"
            "SudoVDA.dll" = "47ee263cb5de9382c6630a2d7f3dafec4a49419f953beec869ca5dd0c460ff63"
            "sudovda.cer" = "6accdcd519f6179d967db4eaa20ecf25a732ba30e87f4cffebc768b2c13c9007" }
foreach ($f in $SvSha.Keys) {
    $h = (Get-FileHash (Join-Path $Sv $f) -Algorithm SHA256).Hash.ToLower()
    if ($h -ne $SvSha[$f]) { throw "Checksum mismatch for sudovda\$f ($h)" }
}
$cer = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2((Join-Path $Sv "sudovda.cer"))
foreach ($sn in "Root", "TrustedPublisher") {
    $store = New-Object System.Security.Cryptography.X509Certificates.X509Store($sn, "LocalMachine")
    $store.Open("ReadWrite"); $store.Add($cer); $store.Close()
}
$HadSudoVda = Test-SudoVda
& $Nef --create-device-node --hardware-id "root\sudomaker\sudovda" --class-name Display --class-guid $Class --no-duplicates
& $Nef --install-driver --inf-path (Join-Path $Sv "SudoVDA.inf")
if ($LASTEXITCODE -ne 0) { throw "SudoVDA install failed ($LASTEXITCODE)" }
Start-Sleep 3
if (-not $HadSudoVda -and (Test-SudoVda)) {   # we created it: remember, so -Uninstall may remove it
    @{ installed_by = "hermes"; time = (Get-Date).ToString("o") } | ConvertTo-Json | Set-Content -Path $Marker -Encoding UTF8
}

Register-Guard
Write-Host "Virtual display installed. Use: hermes-vdisplay status" -ForegroundColor Green
Start-Sleep 3
