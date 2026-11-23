# Hermes Android tray app (runs as administrator). Starts and keeps running the phone relay,
# the Hermes gateway, the Tailscale daemon and the vdisplay host as its own children, and shows
# a green/yellow/red icon. Started elevated by the "HermesAndroidTray" scheduled task (logon, highest);
# the Start-menu / desktop shortcuts self-elevate through the same task. The Hermes_PhoneRelay
# keep-alive task is disabled by install.ps1 so the tray is the single owner of the relay.
#
# On exit: if no other Hermes process is running (the desktop Hermes.exe, an interactive hermes CLI
# session — excluding the tray's own children and the gateway itself) the gateway and the related
# processes are stopped too; otherwise only the relay (and other tray children) are stopped and the
# gateway is left running for the desktop app / CLI.
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$ErrorActionPreference = "Continue"

# ---- run as administrator: re-enter through the elevated scheduled task --------------------------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    $t = Get-ScheduledTask -TaskName "HermesAndroidTray" -ErrorAction SilentlyContinue
    if ($t) { Start-ScheduledTask -TaskName "HermesAndroidTray" | Out-Null; exit 0 }
    # no task (e.g. started by hand before install): run unprivileged, best effort
}

$created = $false
$mutex = New-Object System.Threading.Mutex($true, "Local\HermesAndroidTray", [ref]$created)
if (-not $created) { exit 0 }    # already running

$HH      = Join-Path $env:LOCALAPPDATA "hermes"
$Launch  = Join-Path $HH "launchers"
$Log     = Join-Path $Launch "phone-relay.log"
$Install = Join-Path $Launch "install.ps1"
$Port    = 8643
$RunKey  = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$RunName = "HermesAndroidTray"
$Task    = "HermesAndroidTray"
$Vbs     = Join-Path $Launch "hermes-tray.vbs"
$TsFix   = Join-Path $Launch "tailscale-connect.ps1"
$TsRes   = Join-Path $Launch "tailscale-connect.result"
$Hermes  = Join-Path $HH "bin\hermes.exe"
$PyW     = Join-Path $HH "hermes-agent\venv\Scripts\pythonw.exe"
$RelayPy = Join-Path $Launch "hermes-phone-relay.py"
$VdState = Join-Path $HH "vdisplay"
$VdCli   = Join-Path $Launch "hermes-vdisplay.py"

# zh UI when Windows is Chinese
$zh = (Get-UICulture).Name -like "zh*"
function T($en, $cn) { if ($zh) { $cn } else { $en } }

function Get-Key {
    $f = Join-Path $HH ".env"
    if (-not (Test-Path $f)) { return $null }
    $l = Get-Content $f | Where-Object { $_ -match '^API_SERVER_KEY=.+' } | Select-Object -First 1
    if ($l) { $l.Substring(15).Trim() } else { $null }
}

function New-DotIcon([System.Drawing.Color]$c) {
    $bmp = New-Object System.Drawing.Bitmap 32, 32
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = "AntiAlias"
    $g.FillEllipse((New-Object System.Drawing.SolidBrush $c), 3, 3, 26, 26)
    $f = New-Object System.Drawing.Font("Segoe UI", 15, [System.Drawing.FontStyle]::Bold)
    $sf = New-Object System.Drawing.StringFormat; $sf.Alignment = "Center"; $sf.LineAlignment = "Center"
    $g.DrawString("H", $f, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0, 1, 32, 32), $sf)
    $g.Dispose()
    [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
}
$Icons = @{
    ok   = New-DotIcon ([System.Drawing.Color]::FromArgb(46, 160, 67))
    warn = New-DotIcon ([System.Drawing.Color]::FromArgb(210, 150, 0))
    bad  = New-DotIcon ([System.Drawing.Color]::FromArgb(200, 40, 40))
}

function Check-Relay {
    try { if ((Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 3).status -eq "ok") { return "ok" } } catch {}
    "bad"
}
function Check-Gateway {
    $k = Get-Key; if (-not $k) { return "bad" }
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$Port/api/sessions?profile=default&limit=1" -Headers @{ Authorization = "Bearer $k" } -UseBasicParsing -TimeoutSec 20
        if ($r.StatusCode -eq 200) { return "ok" }
    } catch {}
    "bad"
}
function Check-Tailscale {
    $ts = Get-Command tailscale -ErrorAction SilentlyContinue
    if (-not $ts) { $ts = Get-Command (Join-Path $env:ProgramFiles "Tailscale\tailscale.exe") -ErrorAction SilentlyContinue }
    if ($ts) {
        try { $j = (& $ts.Source status --json 2>$null | Out-String | ConvertFrom-Json); if ($j.BackendState -eq "Running") { return "ok" } else { return "warn" } } catch {}
    }
    if (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like "100.*" }) { "ok" } else { "warn" }
}

# ---- child process management -------------------------------------------------------------
# Stop every relay / ticket-server process (filter by name AND command line, never the shell that runs the kill).
function Stop-Relay {
    Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe' OR Name='hermes.exe'" |
        Where-Object { ($_.CommandLine -like "*hermes-phone-relay.py*" -or $_.CommandLine -like "*gateway tickets*") -and $_.ProcessId -ne $PID } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
function Start-Relay {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return }
    Stop-Relay; Start-Sleep -Milliseconds 500
    Start-Process $PyW -ArgumentList "-I", "`"$RelayPy`"" -WorkingDirectory $Launch -WindowStyle Hidden | Out-Null
}
function Ensure-Gateway {
    Start-Process $Hermes -ArgumentList "gateway", "ensure" -WindowStyle Hidden | Out-Null
}
function Start-Tailscale {
    if (Test-Path $TsFix) { Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", "`"$TsFix`"" -WindowStyle Hidden | Out-Null }
}
# vdisplay host: only when an agent screen is actually wanted; the host drops itself when none is.
function Test-VdisplayWanted {
    foreach ($f in "agent-screens.json", "agent-requests.json") {
        $p = Join-Path $VdState $f
        if (Test-Path $p) { $j = Get-Content $p -Raw; if ($j -and $j.Trim() -notin @("{}", "[]")) { return $true } }
    }
    return $false
}
function Test-VdisplayHostAlive {
    $hp = Join-Path $VdState "host.pid"
    if (Test-Path $hp) {
        $pid2 = (Get-Content $hp -Raw).Trim()
        if ($pid2 -match '^\d+$') { return [bool](Get-Process -Id ([int]$pid2) -ErrorAction SilentlyContinue) }
    }
    return $false
}
function Ensure-VdisplayHost {
    if (-not (Test-Path $VdCli)) { return }
    if (-not (Test-VdisplayWanted)) { return }
    if (Test-VdisplayHostAlive) { return }
    Start-Process $PyW -ArgumentList "-I", "`"$VdCli`"", "_host" -WorkingDirectory $Launch -WindowStyle Hidden | Out-Null
}
function Stop-VdisplayHost {
    $hp = Join-Path $VdState "host.pid"
    if (Test-Path $hp) { $pid2 = (Get-Content $hp -Raw).Trim(); if ($pid2 -match '^\d+$') { Stop-Process -Id ([int]$pid2) -Force -ErrorAction SilentlyContinue } }
    Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe'" |
        Where-Object { $_.CommandLine -like "*hermes-vdisplay.py*_host*" -and $_.ProcessId -ne $PID } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
# Is another Hermes process running that is NOT the tray's own child / the gateway? (desktop Hermes.exe, hermes CLI…)
function Test-OtherHermes {
    $script:found = $false
    Get-CimInstance Win32_Process -Filter "Name='Hermes.exe' OR Name='hermes.exe' OR Name='python.exe' OR Name='pythonw.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -and $_.CommandLine -match 'hermes' } |
        ForEach-Object {
            $c = [string]$_.CommandLine
            if ($c -match 'hermes-phone-relay\.py' -or $c -match 'gateway (run|tickets|ensure|attach|status|stop|list|restart|start)' -or
                $c -match 'hermes-tray\.ps1' -or $c -match 'tailscale-connect' -or $c -match 'hermes-vdisplay' -or
                $c -match 'live_sessions' -or $c -match 'hermes-elevate') { return }
            $script:found = $true
        }
    return $script:found
}
function Stop-Gateway {
    & $Hermes gateway stop --all -y 2>$null | Out-Null
    Start-Sleep -Milliseconds 800
    Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
        Where-Object { $_.CommandLine -like "*gateway run*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
# Exit: stop our own children; only stop the gateway when nothing else Hermes is left running.
function Exit-Tray {
    Stop-Relay
    Stop-VdisplayHost
    if (-not (Test-OtherHermes)) { Stop-Gateway }
    $tray.Visible = $false
    [System.Windows.Forms.Application]::Exit()
}

function Restart-Relay {
    Stop-Relay
    Start-Sleep -Milliseconds 800
    Start-Relay
}

# ---------------------------------------------------------------- UI
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Icon = $Icons.warn; $tray.Text = "Hermes Android"; $tray.Visible = $true
$menu = New-Object System.Windows.Forms.ContextMenuStrip
$miRelay = $menu.Items.Add("…"); $miGw = $menu.Items.Add("…"); $miTs = $menu.Items.Add("…")
foreach ($m in $miRelay, $miGw, $miTs) { $m.Enabled = $false }
[void]$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
$menu.Items.Add((T "Restart relay" "重启中继")).add_Click({ Restart-Relay; $tray.ShowBalloonTip(3000, "Hermes", (T "Restarting the relay…" "正在重启中继…"), "Info") })
$menu.Items.Add((T "Open log" "打开日志")).add_Click({ if (Test-Path $Log) { Start-Process notepad.exe $Log } })
$menu.Items.Add((T "Show pairing QR code" "显示配对二维码")).add_Click({
    Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", "`"$Install`"", "-PairOnly", "-NoAdb" -WindowStyle Hidden })
# ---- agent virtual screen (hermes-vdisplay.py; driver installed on demand)
$VdPy  = Join-Path $env:LOCALAPPDATA "hermes\hermes-agent\venv\Scripts\python.exe"
function Vd([string[]]$a) {
    Start-Process $VdPy -ArgumentList (@("-I", "`"$VdCli`"") + $a) -WindowStyle Hidden }
$miVd = New-Object System.Windows.Forms.ToolStripMenuItem (T "Agent virtual screen" "Agent 虚拟屏")
foreach ($it in @(
        @((T "Agent screen on" "开启 Agent 屏"), @("on")),
        @((T "Agent screen off" "关闭 Agent 屏"), @("off")),
        @("-", $null),
        @((T "Screen off (windows to virtual screen)" "关闭屏幕（窗口挪到虚拟屏）"), @("screen", "off")),
        @((T "Screen on" "打开屏幕"), @("screen", "on")))) {
    if ($it[0] -eq "-") { [void]$miVd.DropDownItems.Add((New-Object System.Windows.Forms.ToolStripSeparator)); continue }
    $a = $it[1]; $mi = $miVd.DropDownItems.Add($it[0]); $mi.Tag = $a
    $mi.add_Click({ param($s) Vd $s.Tag }) }
[void]$miVd.DropDownItems.Add((New-Object System.Windows.Forms.ToolStripSeparator))
$miVd.DropDownItems.Add((T "Install driver…" "安装驱动…")).add_Click({
    Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$(Join-Path $Launch 'install-vdd.ps1')`"", "-Verb", "RunAs" })
$miVd.DropDownItems.Add((T "Uninstall driver" "卸载驱动")).add_Click({
    Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$(Join-Path $Launch 'install-vdd.ps1')`"", "-Uninstall", "-Verb", "RunAs" })
$miVd.Visible = Test-Path $VdCli
[void]$menu.Items.Add($miVd)
$miAuto = New-Object System.Windows.Forms.ToolStripMenuItem (T "Start with Windows" "开机自启")
$miAuto.CheckOnClick = $true
$miAuto.Checked = [bool](Get-ScheduledTask -TaskName $Task -ErrorAction SilentlyContinue | Where-Object { $_.State -ne 'Disabled' })
$miAuto.add_Click({
    if ($miAuto.Checked) { Enable-ScheduledTask -TaskName $Task -ErrorAction SilentlyContinue }
    else { Disable-ScheduledTask -TaskName $Task -ErrorAction SilentlyContinue } })
[void]$menu.Items.Add($miAuto)
[void]$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
$menu.Items.Add((T "Exit" "退出")).add_Click({ Exit-Tray })
$tray.ContextMenuStrip = $menu
$tray.add_MouseClick({ if ($_.Button -eq "Left") { $script:next = 0 } })   # left click: re-check now

$names = @{ relay = (T "Relay" "中继"); gw = "Hermes gateway"; ts = "Tailscale" }
$word  = @{ ok = (T "OK" "正常"); warn = (T "not connected" "未连接"); bad = (T "down" "异常") }
$mark  = @{ ok = "●"; warn = "◐"; bad = "○" }
$state = @{ relay = "ok"; gw = "ok"; ts = "ok" }; $fails = @{ relay = 0; gw = 0; ts = 0 }
$script:next = 0; $script:lastRelay = [datetime]::MinValue
$script:lastGw = [datetime]::MinValue; $script:lastTsFix = [datetime]::MinValue
$script:tsResSeen = if (Test-Path $TsRes) { (Get-Item $TsRes).LastWriteTime } else { [datetime]::MinValue }

function Tick {
    $now = @{ relay = (Check-Relay) }
    $now.gw = if ($now.relay -eq "ok") { Check-Gateway } else { "bad" }
    $now.ts = Check-Tailscale

    # Keep the relay running (the tray is the sole owner now): restart after 2 failed checks, at most once a minute.
    if ($now.relay -ne "ok" -and $fails.relay -ge 1 -and ((Get-Date) - $script:lastRelay).TotalSeconds -gt 60) {
        $script:lastRelay = Get-Date
        Start-Relay
    }
    # Gateway down (but the relay reachable): ensure it, at most once a minute.
    if ($now.gw -ne "ok" -and $now.relay -eq "ok" -and ((Get-Date) - $script:lastGw).TotalSeconds -gt 60) {
        $script:lastGw = Get-Date
        Ensure-Gateway
    }
    # Tailscale down: start it and connect (login page if needed); right away, then at most once a minute.
    if ($now.ts -ne "ok" -and (Test-Path $TsFix) -and ((Get-Date) - $script:lastTsFix).TotalSeconds -ge 60) {
        $script:lastTsFix = Get-Date
        Start-Tailscale
    }
    # vdisplay host: restart it if an agent screen is wanted but the host died.
    Ensure-VdisplayHost
    if ((Test-Path $TsRes) -and (Get-Item $TsRes).LastWriteTime -gt $script:tsResSeen) {
        $script:tsResSeen = (Get-Item $TsRes).LastWriteTime
        $r = (Get-Content $TsRes -Raw).Trim()
        if ($r -like "fail*") { $tray.ShowBalloonTip(8000, "Hermes Android", (T "Tailscale reconnect: " "Tailscale 重连：") + $r.Substring(5).Trim(), "Warning") }
        else { $script:next = 2 }    # connected: refresh the menu soon
    }
    foreach ($k in "relay", "gw", "ts") {
        $fails[$k] = if ($now[$k] -eq "ok") { 0 } else { $fails[$k] + 1 }
        # Notify only after 2 consecutive bad checks (ignores blips), and once when it recovers.
        if ($fails[$k] -eq 2 -and $state[$k] -eq "ok") {
            $state[$k] = $now[$k]
            $tray.ShowBalloonTip(5000, "Hermes Android", "$($names[$k]): $($word[$now[$k]])", "Warning")
        } elseif ($now[$k] -eq "ok" -and $state[$k] -ne "ok") {
            $state[$k] = "ok"
            $tray.ShowBalloonTip(3000, "Hermes Android", "$($names[$k]): $($word.ok)", "Info")
        }
    }
    $miRelay.Text = "$($mark[$now.relay])  $($names.relay) :$Port  $($word[$now.relay])"
    $miGw.Text    = "$($mark[$now.gw])  $($names.gw)  $($word[$now.gw])"
    $miTs.Text    = "$($mark[$now.ts])  $($names.ts)  $($word[$now.ts])"
    $worst = if ($now.relay -ne "ok" -or $now.gw -ne "ok") { "bad" } elseif ($now.ts -ne "ok") { "warn" } else { "ok" }
    $tray.Icon = $Icons[$worst]
    $t = "Hermes Android · " + $(if ($worst -eq "ok") { $word.ok } else { ($names.Keys | Where-Object { $now[$_] -ne "ok" } | ForEach-Object { $names[$_] }) -join ", " })
    $tray.Text = $t.Substring(0, [Math]::Min(63, $t.Length))
}

# ---- bring everything up once at start (relay, gateway, tailscale, vdisplay host) --------------
Start-Relay
Ensure-Gateway
Start-Tailscale
Ensure-VdisplayHost

$timer = New-Object System.Windows.Forms.Timer; $timer.Interval = 1000
$timer.add_Tick({
    $script:next--
    if ($script:next -le 0) { $script:next = 15; try { Tick } catch {} }
})
$timer.Start()
[System.Windows.Forms.Application]::Run()
$tray.Dispose(); $mutex.ReleaseMutex()
