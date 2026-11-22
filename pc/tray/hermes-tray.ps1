# Hermes Android tray app: shows relay / gateway / Tailscale health, keeps the relay running,
# warns with a Windows notification when something breaks. Started hidden by hermes-tray.vbs.
# The Hermes_PhoneRelay scheduled task stays as a fallback.
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$ErrorActionPreference = "Continue"

$created = $false
$mutex = New-Object System.Threading.Mutex($true, "Local\HermesAndroidTray", [ref]$created)
if (-not $created) { exit 0 }    # already running

$HH      = Join-Path $env:LOCALAPPDATA "hermes"
$Launch  = Join-Path $HH "launchers"
$Log     = Join-Path $Launch "phone-relay.log"
$Ensure  = Join-Path $Launch "ensure-phone-relay.ps1"
$Install = Join-Path $Launch "install.ps1"
$Port    = 8643
$RunKey  = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$RunName = "HermesAndroidTray"
$Vbs     = Join-Path $Launch "hermes-tray.vbs"

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
    if ($ts) {
        try { $j = (& $ts.Source status --json 2>$null | Out-String | ConvertFrom-Json); if ($j.BackendState -eq "Running") { return "ok" } else { return "warn" } } catch {}
    }
    if (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like "100.*" }) { "ok" } else { "warn" }
}

function Restart-Relay {
    Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe'" |
        Where-Object { $_.CommandLine -like "*hermes-phone-relay.py*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 800
    Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", "`"$Ensure`"" -WindowStyle Hidden
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
$VdCli = Join-Path $PSScriptRoot "hermes-vdisplay.py"
$VdPy  = Join-Path $env:LOCALAPPDATA "hermes\hermes-agent\venv\Scripts\python.exe"
function Vd([string[]]$a) {
    Start-Process $VdPy -ArgumentList (@("-I", "`"$VdCli`"") + $a) -WindowStyle Hidden }
$miVd = New-Object System.Windows.Forms.ToolStripMenuItem (T "Agent virtual screen" "Agent 虚拟屏")
foreach ($it in @(
        @((T "On (extend)" "开启（扩展）"), @("mode", "extend")),
        @((T "Second screen only (main screen off)" "仅虚拟屏（主屏息屏）"), @("mode", "second-only")),
        @((T "Off" "关闭"), @("off")))) {
    $a = $it[1]; $mi = $miVd.DropDownItems.Add($it[0]); $mi.Tag = $a
    $mi.add_Click({ param($s) Vd $s.Tag }) }
[void]$miVd.DropDownItems.Add((New-Object System.Windows.Forms.ToolStripSeparator))
$miVd.DropDownItems.Add((T "Install driver…" "安装驱动…")).add_Click({
    Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$(Join-Path $PSScriptRoot 'install-vdd.ps1')`"" })
$miVd.DropDownItems.Add((T "Uninstall driver" "卸载驱动")).add_Click({
    Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$(Join-Path $PSScriptRoot 'install-vdd.ps1')`"", "-Uninstall" })
$miVd.Visible = Test-Path $VdCli
[void]$menu.Items.Add($miVd)
$miAuto = New-Object System.Windows.Forms.ToolStripMenuItem (T "Start with Windows" "开机自启")
$miAuto.CheckOnClick = $true
$miAuto.Checked = [bool](Get-ItemProperty $RunKey -Name $RunName -ErrorAction SilentlyContinue)
$miAuto.add_Click({
    if ($miAuto.Checked) { Set-ItemProperty $RunKey -Name $RunName -Value "wscript.exe //B //Nologo `"$Vbs`"" }
    else { Remove-ItemProperty $RunKey -Name $RunName -ErrorAction SilentlyContinue } })
[void]$menu.Items.Add($miAuto)
[void]$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
$menu.Items.Add((T "Exit (relay keeps running)" "退出（中继继续运行）")).add_Click({ $tray.Visible = $false; [System.Windows.Forms.Application]::Exit() })
$tray.ContextMenuStrip = $menu
$tray.add_MouseClick({ if ($_.Button -eq "Left") { $script:next = 0 } })   # left click: re-check now

$names = @{ relay = (T "Relay" "中继"); gw = "Hermes gateway"; ts = "Tailscale" }
$word  = @{ ok = (T "OK" "正常"); warn = (T "not connected" "未连接"); bad = (T "down" "异常") }
$mark  = @{ ok = "●"; warn = "◐"; bad = "○" }
$state = @{ relay = "ok"; gw = "ok"; ts = "ok" }; $fails = @{ relay = 0; gw = 0; ts = 0 }
$script:next = 0; $script:lastRestart = [datetime]::MinValue

function Tick {
    $now = @{ relay = (Check-Relay) }
    $now.gw = if ($now.relay -eq "ok") { Check-Gateway } else { "bad" }
    $now.ts = Check-Tailscale

    # Keep the relay running (the scheduled task is the fallback): restart after 2 failed checks, at most once a minute.
    if ($now.relay -ne "ok" -and $fails.relay -ge 1 -and ((Get-Date) - $script:lastRestart).TotalSeconds -gt 60) {
        $script:lastRestart = Get-Date
        Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", "`"$Ensure`"" -WindowStyle Hidden
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

$timer = New-Object System.Windows.Forms.Timer; $timer.Interval = 1000
$timer.add_Tick({
    $script:next--
    if ($script:next -le 0) { $script:next = 15; try { Tick } catch {} }
})
$timer.Start()
[System.Windows.Forms.Application]::Run()
$tray.Dispose(); $mutex.ReleaseMutex()
