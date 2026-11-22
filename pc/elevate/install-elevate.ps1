# Creates the elevated scheduled task HermesElevate and the admin-only whitelist folder. One UAC prompt.
# -Uninstall removes both.
param([switch]$Uninstall,
      [string]$Python = "$env:LOCALAPPDATA\hermes\hermes-agent\venv\Scripts\pythonw.exe",
      [string]$Cli = "$env:LOCALAPPDATA\hermes\launchers\hermes-elevate.py")
$ErrorActionPreference = "Stop"
$me = "$env:USERDOMAIN\$env:USERNAME"
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    $a = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"", "-Python", "`"$Python`"", "-Cli", "`"$Cli`"")
    if ($Uninstall) { $a += "-Uninstall" }
    Start-Process powershell -Verb RunAs -ArgumentList $a -Wait
    exit
}
$Task = "HermesElevate"
$Dir = Join-Path $env:ProgramData "HermesElevate"
if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $Task -Confirm:$false -ErrorAction SilentlyContinue
    Remove-Item $Dir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Elevation helper removed."; exit
}
New-Item -ItemType Directory -Force $Dir | Out-Null
if (-not (Test-Path "$Dir\allow.json")) { "[]" | Set-Content "$Dir\allow.json" -Encoding UTF8 }
icacls $Dir /inheritance:r /grant:r "Administrators:(OI)(CI)F" "SYSTEM:(OI)(CI)F" "Users:(OI)(CI)RX" | Out-Null
$act = New-ScheduledTaskAction -Execute $Python -Argument "-I `"$Cli`" _runner"
$pr  = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Highest
$set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 5) -MultipleInstances Queue
Register-ScheduledTask -TaskName $Task -Action $act -Principal $pr -Settings $set -Force | Out-Null
Write-Host "Elevation helper installed. Whitelist: hermes-elevate.py allow add <exe>  (never shells/interpreters)."
