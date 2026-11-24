# Mobile hotspot helper for hermes-tray.ps1. Needs Windows PowerShell 5.1 (WinRT).
# -Query prints the state only; -Start turns the hotspot on and writes hotspot.result.
param([switch]$Query, [switch]$Start, [int]$Tries = 4, [int]$DelaySec = 20)
$ErrorActionPreference = "Stop"
$Launch = Join-Path $env:LOCALAPPDATA "hermes\launchers"
$Result = Join-Path $Launch "hotspot.result"
$Log    = Join-Path $Launch "hotspot.log"
function L($m) { try { Add-Content $Log "$(Get-Date -Format s) $m" } catch {} }
function Done($r) { L $r; if ($Start) { try { Set-Content $Result $r -Encoding UTF8 } catch {} } else { Write-Output $r }; exit 0 }

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType = WindowsRuntime]
    $null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType = WindowsRuntime]
    $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq "AsTask" -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
    function Await($op, [Type]$t, [int]$sec = 30) {
        $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op))
        if (-not $task.Wait($sec * 1000)) { throw "timeout waiting for WinRT call" }
        $task.Result
    }
    function Get-Mgr {
        $p = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
        if (-not $p) { return $null }
        [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($p)
    }

    if ($Query) {
        $m = Get-Mgr
        if (-not $m) { Done "NoProfile" }
        Done ([string]$m.TetheringOperationalState)
    }
    if (-not $Start) { Done "fail: use -Query or -Start" }

    $deadline = (Get-Date).AddMinutes(3); $last = $null
    for ($i = 1; $i -le $Tries -and (Get-Date) -lt $deadline; $i++) {
        $m = Get-Mgr
        if (-not $m) { L "try ${i}: no internet connection profile yet"; Start-Sleep -Seconds $DelaySec; continue }
        $st = [string]$m.TetheringOperationalState
        if ($st -eq "On") { Done "ok: hotspot already on" }
        if ($st -eq "InTransition") { L "try ${i}: in transition"; Start-Sleep -Seconds 5; continue }
        $r = Await ($m.StartTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
        if ([string]$r.Status -eq "Success") { Done "ok: hotspot started" }
        $last = "$($r.Status) $($r.AdditionalErrorMessage)".Trim()
        L "try ${i}: start failed: $last"
        Start-Sleep -Seconds $DelaySec
    }
    if ($last) { Done "fail: $last" } else { Done "fail: no internet connection to share (hotspot not started)" }
} catch { Done "fail: $($_.Exception.Message)" }
