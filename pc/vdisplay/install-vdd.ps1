# Installs the open-source VirtualDrivers "Virtual Display Driver" (MttVDD, IddCx) for Hermes' agent screen.
# Same steps as the project's own Community Scripts\silent-install.ps1, with pinned + hash-checked downloads.
#   https://github.com/VirtualDrivers/Virtual-Display-Driver   (driver, MIT)
#   https://github.com/nefarius/nefcon                         (device-node installer, MIT)
# Needs admin (one UAC prompt). Run with -Uninstall to remove the device.
param([switch]$Uninstall, [int]$Width = 0, [int]$Height = 0,
      [string]$Python = "$env:LOCALAPPDATA\hermes\hermes-agent\venv\Scripts\pythonw.exe",
      [string]$Cli = "$env:LOCALAPPDATA\hermes\launchers\hermes-vdisplay.py")
$ErrorActionPreference = "Stop"

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $a = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"", "-Width", $Width, "-Height", $Height,
           "-Python", "`"$Python`"", "-Cli", "`"$Cli`"")
    if ($Uninstall) { $a += "-Uninstall" }
    Start-Process powershell -Verb RunAs -ArgumentList $a -Wait
    exit
}

$DriverUrl = "https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/download/25.7.23/VirtualDisplayDriver-x86.Driver.Only.zip"
$DriverSha = "e24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a"
$NefUrl    = "https://github.com/nefarius/nefcon/releases/download/v1.21.1/nefcon_v1.21.1.zip"
$NefSha    = "28292f4b1417484db6ad23c47ffc18ea99d3de3bdb3bbbe60c8ed11703a3780a"
$Work      = Join-Path $env:LOCALAPPDATA "hermes\vdisplay"
$Settings  = "C:\VirtualDisplayDriver"          # where MttVDD reads vdd_settings.xml

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

$Task = "HermesVDisplayGuard"
if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $Task -Confirm:$false -ErrorAction SilentlyContinue
    & $Nef --remove-device-node --hardware-id "Root\MttVDD" --class-guid "4D36E968-E325-11CE-BFC1-08002BE10318"
    & $Nef --uninstall-driver --inf-path (Join-Path $Work "driver\VirtualDisplayDriver\MttVDD.inf")
    Write-Host "Virtual display removed." -ForegroundColor Green
    Start-Sleep 3; exit
}

Get-Pinned $DriverUrl $DriverSha (Join-Path $Work "driver.zip")
Expand-Archive (Join-Path $Work "driver.zip") (Join-Path $Work "driver") -Force
$Drv = Join-Path $Work "driver\VirtualDisplayDriver"

# Modes: the main screen's real (unscaled) size for the user screen, 1280x800 for the agent screen, both at 30 Hz
# (hermes-vdisplay picks 30 Hz to keep the GPU cost low; nobody watches these screens).
if ($Width -le 0) {
    $vc = Get-CimInstance Win32_VideoController | Where-Object { $_.CurrentHorizontalResolution -and $_.Name -notlike "*Virtual*" } | Select-Object -First 1
    if ($vc) { $Width = $vc.CurrentHorizontalResolution; $Height = $vc.CurrentVerticalResolution }
    else { Add-Type -AssemblyName System.Windows.Forms; $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds; $Width = $b.Width; $Height = $b.Height }
}
New-Item -ItemType Directory -Force -Path $Settings | Out-Null
[xml]$x = Get-Content (Join-Path $Drv "vdd_settings.xml")
$x.vdd_settings.monitors.count = "2"   # 1st = agent screen, 2nd = parks the user's windows in second-only mode
$g = $x.vdd_settings.global
if (-not ($g.g_refresh_rate | Where-Object { $_ -eq "30" })) {
    $e = $x.CreateElement("g_refresh_rate"); $e.InnerText = "30"; [void]$g.PrependChild($e)
}
foreach ($wh in @(@($Width, $Height), @(1280, 800))) {
    $have = $x.vdd_settings.resolutions.resolution | Where-Object { $_.width -eq "$($wh[0])" -and $_.height -eq "$($wh[1])" }
    if ($have) { continue }
    $r = $x.CreateElement("resolution")
    foreach ($kv in @(@("width", $wh[0]), @("height", $wh[1]), @("refresh_rate", 30))) {
        $e = $x.CreateElement($kv[0]); $e.InnerText = "$($kv[1])"; [void]$r.AppendChild($e)
    }
    [void]$x.vdd_settings.resolutions.AppendChild($r)
}
$x.Save((Join-Path $Settings "vdd_settings.xml"))

# Trust the driver's signing certificate (the upstream installer does the same), then create the device.
$certs = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2Collection
$certs.Import([IO.File]::ReadAllBytes((Join-Path $Drv "mttvdd.cat")))
$store = New-Object System.Security.Cryptography.X509Certificates.X509Store("TrustedPublisher", "LocalMachine")
$store.Open("ReadWrite"); foreach ($c in $certs) { $store.Add($c) }; $store.Close()

Push-Location $Drv
& $Nef --create-device-node --hardware-id "Root\MttVDD" --class-name Display --class-guid "4D36E968-E325-11CE-BFC1-08002BE10318" --no-duplicates
& $Nef --install-driver --inf-path ".\MttVDD.inf"
if ($LASTEXITCODE -ne 0) { throw "driver install failed ($LASTEXITCODE)" }
Pop-Location
# Already installed: restart the device so it re-reads vdd_settings.xml (new modes).
Get-PnpDevice -Class Display -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains "Root\MttVDD" } |
    ForEach-Object { pnputil /restart-device "$($_.InstanceId)" | Out-Null }
Start-Sleep 8

# Guard (keeps non-agent windows and the user's mouse off the agent screen) runs elevated on demand, so it can
# also move admin windows (UIPI blocks a normal process). hermes-vdisplay starts it with: schtasks /run /tn $Task
$act = New-ScheduledTaskAction -Execute $Python -Argument "-I `"$Cli`" _guard"
$pr  = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Highest
$set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $Task -Action $act -Principal $pr -Settings $set -Force | Out-Null

Write-Host "Virtual display installed ($Width x $Height). Use: hermes-vdisplay status" -ForegroundColor Green
Start-Sleep 3
