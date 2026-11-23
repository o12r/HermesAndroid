# Registers the elevated scheduled task "HermesAndroidTray" (logon trigger, highest privilege) that runs
# the tray app as administrator, stops any running tray, then starts the task. One silent elevation
# (the user has UAC elevate-without-prompt on). -Uninstall stops the tray and removes the task.
param([switch]$Uninstall)
$ErrorActionPreference = "Stop"
$me = "$env:USERDOMAIN\$env:USERNAME"
$Launch = Join-Path $env:LOCALAPPDATA "hermes\launchers"
$Tray = Join-Path $Launch "hermes-tray.ps1"
$Task = "HermesAndroidTray"

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    $a = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    if ($Uninstall) { $a += "-Uninstall" }
    Start-Process powershell -Verb RunAs -ArgumentList $a -Wait
    exit
}

# stop any running tray (so the new one can take the mutex)
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -like "*hermes-tray.ps1*" -and $_.ProcessId -ne $PID } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $Task -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Tray task removed."; exit
}

$act = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File `"$Tray`""
$pr  = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Highest
$trg = New-ScheduledTaskTrigger -AtLogOn -User $me
$set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 -MultipleInstances IgnoreNew -StartWhenAvailable
Register-ScheduledTask -TaskName $Task -Action $act -Principal $pr -Trigger $trg -Settings $set -Force | Out-Null
Start-ScheduledTask -TaskName $Task
Write-Host "Tray task registered (logon, highest) and started."
