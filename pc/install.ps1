<#
  Hermes Android: one-click PC setup.

    install.cmd               install / repair everything, then pair the phone
    install.cmd -PairOnly     only show the pairing page / pair a USB-connected phone again
    install.cmd -NoAdb        don't use adb even if a phone is connected (always show the QR page)
    install.cmd -Profiles default,work   install the skills only into these profiles (no questions asked)
    install.cmd -AllProfiles   install the skills into every profile (no questions asked)
    install.cmd -Uninstall    remove the relay, tasks, tray, both skills (from ALL profiles), the virtual display
                              driver (install-vdd.ps1 -Uninstall, one UAC prompt) and the update folder (keys in .env are kept)

  What it does (safe to re-run):
    1. Finds Hermes (%LOCALAPPDATA%\hermes) and its venv Python.
    2. API key: reuses API_SERVER_KEY from the default .env, or generates one, and writes the same key to
       every profile's .env that lacks one (each touched .env is backed up to .env.bak-android first).
    3. Copies the phone relay to launchers\ and registers the "Hermes_PhoneRelay" scheduled task
       (at logon, on wake from sleep/hibernate, every 5 min). Starts the relay and checks /health.
    4. Creates the in-app update folder and seeds it with the latest APK.
    5. Installs the skills "hermes-android" and "hermes-agent-screen" so the local Hermes can use, fix and improve
       this setup. For each skill you pick the profiles (all pre-selected: Enter = all, "1,3" or names = those,
       "none" = skip; Out-GridView when available). With -Profiles / -AllProfiles, or when there is no console
       (hidden / redirected), every profile gets both skills. The choice is not remembered. "default" = the root profile.
       If the profile sync daemon is installed (profile-sync\sync_engine.py), nothing is asked: SKILL.md goes to the default
       profile only (the shared layer all profiles read), copies that would shadow it are moved to profile-sync\backups.
    6. Pairs the phone: over USB/adb if a phone is connected (installs the app and pairs it, no typing), otherwise
       opens a page with two QR codes (download the app, then pair). Also copies the key to the clipboard.
  The key is never printed.
#>
param([switch]$Uninstall, [switch]$PairOnly, [switch]$NoAdb, [string]$Apk = "", [string[]]$Profiles = @(), [switch]$AllProfiles)
$ErrorActionPreference = "Stop"
$Repo     = "o12r/HermesAndroid"
$Here     = Split-Path -Parent $MyInvocation.MyCommand.Path
$HH       = Join-Path $env:LOCALAPPDATA "hermes"
$Launch   = Join-Path $HH "launchers"
$Updates  = Join-Path $Launch "app-updates\hermes-android"
$PyLib    = Join-Path $Launch "pylib"
$Py       = Join-Path $HH "hermes-agent\venv\Scripts\python.exe"
$PyW      = Join-Path $HH "hermes-agent\venv\Scripts\pythonw.exe"
if (-not (Test-Path $Py)) {
    # Newer Hermes keeps the venv outside the checkout: installs\<id>\environments\<id>\venv (also try hermes-agent\.venv).
    $cand = @(Join-Path $HH "hermes-agent\.venv\Scripts\python.exe")
    $cand += @(Get-ChildItem (Join-Path $HH "installs\*\environments\*\venv\Scripts\python.exe") -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | ForEach-Object { $_.FullName })
    foreach ($c in $cand) { if (Test-Path $c) { $Py = $c; $PyW = Join-Path (Split-Path $c) "pythonw.exe"; break } }
    # The tray/relay/vdisplay scripts all hard-code hermes-agent\venv: link it to the real venv (re-run this installer after a Hermes update moves it).
    if ((Test-Path $Py) -and -not (Test-Path (Join-Path $HH "hermes-agent\venv"))) {
        try {
            $vl = Join-Path $HH "hermes-agent\venv"
            if (Test-Path $vl) { (Get-Item $vl -Force).Delete() }   # dangling junction from an earlier install
            New-Item -ItemType Junction -Path $vl -Target (Split-Path (Split-Path $Py)) -ErrorAction Stop | Out-Null
            $Py = Join-Path $vl "Scripts\python.exe"; $PyW = Join-Path $vl "Scripts\pythonw.exe"
        } catch { Write-Host "   !!  could not link hermes-agent\venv ($($_.Exception.Message)); the tray/relay launchers may not find Python" -ForegroundColor Yellow }
    }
}
$Task     = "Hermes_PhoneRelay"
$Port     = 8643

function Step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "   OK  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "   !!  $m" -ForegroundColor Yellow }

function Stop-Relay {
    Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe'" |
        Where-Object { $_.CommandLine -like "*hermes-phone-relay.py*" -and $_.ProcessId -ne $PID } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Profile-Dirs {
    # Real profiles only: skip ".deleted" and other dot/hidden folders.
    @($HH) + @(Get-ChildItem (Join-Path $HH "profiles") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^[a-z0-9][a-z0-9_-]*$' } | ForEach-Object { $_.FullName })
}

function Profile-Name($d) { if ($d -eq $HH) { "default" } else { Split-Path $d -Leaf } }

# What each skill does - shown in the picker.
$SkillInfo = @{
    "hermes-android" = @{
        en = "Lets your local Hermes diagnose, fix and improve this phone setup: it knows the app, the phone relay, the tray menu, updates / hotfixes and the logs, so you can ask it to repair a broken connection or add a feature."
        zh = "让本机 Hermes 能诊断、修复和改进这套手机连接：了解手机 App、手机中继、托盘菜单、更新/热修复与日志，连接出问题或想加功能时可以直接让它处理。"
    }
    "hermes-agent-screen" = @{
        en = "Lets Hermes do GUI work on a separate agent virtual screen without taking over yours: background click / type / key, window placement, screen off / dim. Needs the virtual display driver."
        zh = "让 Hermes 在独立的agent 虚拟屏上操作图形界面而不抢你的屏幕：后台点击/输入/按键、窗口摆放、关屏/调暗。需要虚拟显示器驱动。"
    }
}
function Skill-Info([string]$Skill) { $k = if ($PSUICulture -like "zh*") { "zh" } else { "en" }; $SkillInfo[$Skill][$k] }

# One window for both skills: a description + a profile checklist (all pre-checked) per skill.
# Returns @{ skill = @(profile dirs) }, or $null when the window cannot be shown (-> console fallback) / is closed with Skip.
function Show-SkillPicker([string[]]$Skills, [string[]]$Dirs) {
    try { Add-Type -AssemblyName System.Windows.Forms, System.Drawing -ErrorAction Stop } catch { return $null }
    [System.Windows.Forms.Application]::EnableVisualStyles()
    $zh = $PSUICulture -like "zh*"
    $names = @($Dirs | ForEach-Object { Profile-Name $_ })
    $f = New-Object System.Windows.Forms.Form
    $f.Text = if ($zh) { "Hermes Android - 安装 skill" } else { "Hermes Android - install skills" }
    $f.StartPosition = "CenterScreen"; $f.TopMost = $true; $f.FormBorderStyle = "FixedDialog"; $f.MaximizeBox = $false; $f.MinimizeBox = $false
    $f.Font = New-Object System.Drawing.Font("Segoe UI", 9.5)
    $w = 600; $y = 12; $lists = @{}
    $hint = New-Object System.Windows.Forms.Label
    $hint.Text = if ($zh) { "为每个 skill 勾选要安装到的档案（默认全选）。" } else { "Tick the profiles each skill is installed into (all ticked by default)." }
    $hint.SetBounds(14, $y, $w - 28, 22); $f.Controls.Add($hint); $y += 28
    $rows = [Math]::Min([Math]::Max($names.Count, 2), 6)
    foreach ($sk in $Skills) {
        $g = New-Object System.Windows.Forms.GroupBox
        $g.Text = $sk; $g.SetBounds(12, $y, $w - 24, 70 + 20 * $rows + 26)
        $d = New-Object System.Windows.Forms.Label
        $d.Text = Skill-Info $sk; $d.SetBounds(12, 22, $w - 52, 66)
        $l = New-Object System.Windows.Forms.CheckedListBox
        $l.CheckOnClick = $true; $l.SetBounds(12, 92, $w - 52, 20 * $rows + 8)
        foreach ($n in $names) { [void]$l.Items.Add($n, $true) }
        $all = New-Object System.Windows.Forms.CheckBox
        $all.Text = if ($zh) { "全选" } else { "Select all" }; $all.Checked = $true
        $all.SetBounds(12, 92 + 20 * $rows + 12, 160, 24)
        $all.Add_CheckedChanged({ param($s, $e) $lb = $s.Tag; for ($i = 0; $i -lt $lb.Items.Count; $i++) { $lb.SetItemChecked($i, $s.Checked) } })
        $all.Tag = $l
        $g.Controls.AddRange(@($d, $l, $all)); $f.Controls.Add($g); $lists[$sk] = $l
        $y += $g.Height + 8
    }
    $ok = New-Object System.Windows.Forms.Button; $ok.Text = if ($zh) { "安装" } else { "Install" }; $ok.DialogResult = "OK"; $ok.SetBounds($w - 220, $y + 4, 100, 30)
    $no = New-Object System.Windows.Forms.Button; $no.Text = if ($zh) { "跳过" } else { "Skip" }; $no.DialogResult = "Cancel"; $no.SetBounds($w - 112, $y + 4, 100, 30)
    $f.Controls.AddRange(@($ok, $no)); $f.AcceptButton = $ok; $f.CancelButton = $no
    $f.ClientSize = New-Object System.Drawing.Size($w, ($y + 46))
    if ($f.ShowDialog() -ne "OK") { return @{} }
    $res = @{}
    foreach ($sk in $Skills) {
        $res[$sk] = @(for ($i = 0; $i -lt $Dirs.Count; $i++) { if ($lists[$sk].GetItemChecked($i)) { $Dirs[$i] } })
    }
    return $res
}

# Pick profiles for one skill. Returns the chosen profile dirs. All are pre-selected.
#   -Profiles / -AllProfiles given, or no interactive console -> all (or the named ones) without asking.
#   a small window (checklist + what each skill does), else a console prompt: Enter = all, "1,3" / names = those, "none" = skip.
function Select-Profiles([string]$Skill, [string[]]$Dirs, [string[]]$Names = @(), [switch]$All, [switch]$NonInteractive) {
    if ($Names.Count) {
        $want = @($Names | ForEach-Object { $_.Split(",") } | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
        $sel = @($Dirs | Where-Object { $want -contains (Profile-Name $_).ToLower() })
        foreach ($w in $want) { if (-not ($Dirs | Where-Object { (Profile-Name $_).ToLower() -eq $w })) { Warn "unknown profile '$w' (ignored)" } }
        return $sel
    }
    if ($All -or $NonInteractive) { return $Dirs }
    $names = @($Dirs | ForEach-Object { Profile-Name $_ })
    Write-Host "   Install skill '$Skill' into which profiles?" -ForegroundColor Cyan
    Write-Host ("   " + (Skill-Info $Skill)) -ForegroundColor DarkGray
    for ($i = 0; $i -lt $names.Count; $i++) { Write-Host ("     [{0}] {1}" -f ($i + 1), $names[$i]) }
    $ans = (Read-Host "   Enter = all, numbers/names separated by commas = those, none = skip").Trim()
    if (-not $ans) { return $Dirs }
    if ($ans -ieq "none") { return @() }
    $out = @()
    foreach ($tok in ($ans -split '[,\s]+' | Where-Object { $_ })) {
        if ($tok -match '^\d+$' -and [int]$tok -ge 1 -and [int]$tok -le $Dirs.Count) { $out += $Dirs[[int]$tok - 1] }
        elseif ($names -contains $tok.ToLower()) { $out += $Dirs[[array]::IndexOf(@($names | ForEach-Object { $_.ToLower() }), $tok.ToLower())] }
        else { Warn "ignored '$tok'" }
    }
    return @($out | Select-Object -Unique)
}

function Get-Key {
    $envf = Join-Path $HH ".env"
    if (Test-Path $envf) {
        $line = Get-Content $envf | Where-Object { $_ -match '^API_SERVER_KEY=.+' } | Select-Object -First 1
        if ($line) { return $line.Substring(15).Trim() }
    }
    return $null
}

function Tailscale-IP {
    $ts = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($ts) { $ip = (& $ts.Source ip -4 2>$null | Select-Object -First 1); if ($ip) { return $ip.Trim() } }
    (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like "100.*" } | Select-Object -First 1).IPAddress
}

function Lan-IP {
    # The private address of the adapter that has the default route (real Wi-Fi/Ethernet, not Docker/WSL/Hyper-V).
    $cfg = Get-NetIPConfiguration -ErrorAction SilentlyContinue | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" -and $_.InterfaceAlias -notmatch 'vEthernet|WSL|Tailscale|Loopback|tun|tap|vpn|clash|sing|wg|zerotier' -and
        $_.NetAdapter.InterfaceDescription -notmatch 'Tunnel|TAP|VPN|WireGuard|Wintun|Hyper-V Virtual Ethernet|Virtual Ethernet|VirtualBox|VMware' }
    foreach ($c in $cfg) { foreach ($a in $c.IPv4Address) { if ($a.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)') { return $a.IPAddress } } }
    return $null
}

# Start menu + desktop shortcuts for the tray app (it is single-instance, so clicking it again is harmless)
$Lnks = @((Join-Path ([Environment]::GetFolderPath("Programs")) "Hermes Android.lnk"),
          (Join-Path ([Environment]::GetFolderPath("Desktop")) "Hermes Android.lnk"))
function Add-TrayShortcuts {
    $sh = New-Object -ComObject WScript.Shell
    foreach ($l in $Lnks) {
        $s = $sh.CreateShortcut($l)
        $s.TargetPath = Join-Path $env:WINDIR "System32\wscript.exe"
        $s.Arguments = "//B //Nologo `"$Launch\hermes-tray.vbs`""
        $s.WorkingDirectory = $Launch
        $ico = Join-Path $Launch "hermes-tray.ico"   # generated by the tray at start; shell32 fallback until then
        $s.IconLocation = if (Test-Path $ico) { "$ico,0" } else { (Join-Path $env:WINDIR "System32\shell32.dll") + ",13" }
        $s.Description = "Hermes Android tray (phone relay status, agent screen)"
        $s.Save()
    }
}

function Find-Adb {
    $c = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.Source }
    foreach ($p in "$env:ANDROID_HOME\platform-tools\adb.exe", "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "C:\Android\Sdk\platform-tools\adb.exe") {
        if ($p -and (Test-Path $p)) { return $p } }
    return $null
}

# ---------------------------------------------------------------- uninstall
if ($Uninstall) {
    Step "Uninstalling Hermes Android PC parts"
    Unregister-ScheduledTask -TaskName $Task -Confirm:$false -ErrorAction SilentlyContinue; Ok "keep-alive task removed"
    Remove-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "HermesAndroidTray" -ErrorAction SilentlyContinue
    # the tray now runs elevated via its own task; remove it (install-tray-task.ps1 self-elevates)
    $tt = Join-Path $Launch "install-tray-task.ps1"
    if (Test-Path $tt) { Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$tt`"", "-Uninstall" -Wait -WindowStyle Hidden }
    else { Unregister-ScheduledTask -TaskName "HermesAndroidTray" -Confirm:$false -ErrorAction SilentlyContinue }
    foreach ($l in $Lnks) { Remove-Item $l -ErrorAction SilentlyContinue }
    Ok "tray app removed"
    Stop-Relay; Ok "relay stopped"
    # virtual display driver + its elevated daemon task (install-vdd.ps1 self-elevates: one UAC prompt)
    $vdd = Join-Path $Launch "install-vdd.ps1"
    if (Test-Path $vdd) {
        Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$vdd`"", "-Uninstall" -Wait
        Ok "virtual display driver uninstall finished"
    } else { Warn "install-vdd.ps1 not found in ${Launch}: virtual display driver not removed (run pc\vdisplay\install-vdd.ps1 -Uninstall)" }
    if (Get-ScheduledTask -TaskName "HermesVDisplayGuard" -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName "HermesVDisplayGuard" -Confirm:$false -ErrorAction SilentlyContinue
        if (Get-ScheduledTask -TaskName "HermesVDisplayGuard" -ErrorAction SilentlyContinue) { Warn "task HermesVDisplayGuard still present (needs admin: delete it with schtasks /Delete /TN HermesVDisplayGuard /F)" } else { Ok "task HermesVDisplayGuard removed" }
    }
    Remove-Item (Join-Path $Launch "phone-outbox") -Recurse -ErrorAction SilentlyContinue
    foreach ($f in "hermes-phone-relay.py", "ensure-phone-relay.ps1", "ensure-phone-relay.vbs", "send-to-phone.py", "pair_page.py", "hermes-tray.ps1", "hermes-tray.vbs", "tailscale-connect.ps1", "hotspot.ps1", "install-tray-task.ps1", "install.ps1", "hermes-android-pair.html", "hermes-vdisplay.py", "live_sessions.py", "install-vdd.ps1", "hermes-elevate.py", "install-elevate.ps1") {
        Remove-Item (Join-Path $Launch $f) -ErrorAction SilentlyContinue }
    Remove-Item (Join-Path $Launch "sudovda") -Recurse -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $Launch "app-updates\hermes-android") -Recurse -ErrorAction SilentlyContinue
    Remove-Item $PyLib -Recurse -ErrorAction SilentlyContinue
    foreach ($d in Profile-Dirs) { foreach ($sk in "hermes-android", "hermes-agent-screen") {
        $sd = Join-Path $d "skills\software-development\$sk"
        # With the profile sync daemon the default copy is the shared layer: delete only SKILL.md and keep references\ (extra notes live there).
        if (($d -eq $HH) -and (Test-Path (Join-Path $sd "references"))) { Remove-Item (Join-Path $sd "SKILL.md") -ErrorAction SilentlyContinue }
        else { Remove-Item $sd -Recurse -ErrorAction SilentlyContinue } } }
    Ok "files and skill removed (API_SERVER_KEY in .env files kept; delete those lines yourself if unwanted)"
    exit 0
}

# ---------------------------------------------------------------- 1. find Hermes
Step "Checking Hermes"
if (-not (Test-Path $Py)) { throw "Hermes not found at $HH (expected $Py). Install Hermes Agent first." }
$ErrorActionPreference = "Continue"; & $Py -I -c "import aiohttp" 2>$null; $ErrorActionPreference = "Stop"; if ($LASTEXITCODE) { throw "The Hermes venv has no aiohttp; update Hermes (hermes update)." }
Ok "Hermes at $HH"
$ts = Tailscale-IP; $lan = Lan-IP
if ($ts) { Ok "Tailscale IP $ts" } else { Warn "Tailscale not found/connected: the phone can then only connect over the same Wi-Fi" }
if ($lan) { Ok "LAN IP $lan" }

if (-not $PairOnly) {
    # ------------------------------------------------------------ 2. API key in every profile
    Step "API key"
    $key = Get-Key
    if (-not $key) {
        $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
        $key = -join ($b | ForEach-Object { $_.ToString("x2") }); Ok "generated a new key"
    } else { Ok "reusing the existing key" }
    foreach ($d in Profile-Dirs) {
        $envf = Join-Path $d ".env"
        $lines = if (Test-Path $envf) { @(Get-Content $envf) } else { @() }
        $has = $lines | Where-Object { $_ -match '^API_SERVER_KEY=' }
        if ($has -and ($has -eq "API_SERVER_KEY=$key")) { continue }
        if (Test-Path $envf) { Copy-Item $envf "$envf.bak-android" -Force }
        $lines = @($lines | Where-Object { $_ -notmatch '^API_SERVER_KEY=' }) + "API_SERVER_KEY=$key"
        [IO.File]::WriteAllLines($envf, [string[]]$lines)
        Ok ("key written to " + (Split-Path $d -Leaf) + "\.env")
    }

    # ------------------------------------------------------------ 3. relay + scheduled task
    Step "Phone relay"
    New-Item -ItemType Directory -Force $Launch | Out-Null
    Stop-Relay
    foreach ($f in "hermes-phone-relay.py", "ensure-phone-relay.ps1", "ensure-phone-relay.vbs", "send-to-phone.py") { Copy-Item (Join-Path $Here "relay\$f") $Launch -Force }
    Copy-Item (Join-Path $Here "pair_page.py") $Launch -Force
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $xml = (Get-Content (Join-Path $Here "relay\relay-task.xml") -Raw).Replace("__USER__", $user).Replace("__LAUNCHERS__", $Launch)
    Register-ScheduledTask -TaskName $Task -Xml $xml -Force | Out-Null
    Ok "scheduled task $Task (logon, wake, every 5 min)"
    Start-ScheduledTask $Task
    $up = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep 1
        try { if ((Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 2).status -eq "ok") { $up = $true; break } } catch {}
    }
    if ($up) { Ok "relay answering on :$Port" } else { Warn "relay not answering yet: see $Launch\phone-relay.log" }
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$Port/api/sessions?profile=default&limit=1" -Headers @{ Authorization = "Bearer $key" } -UseBasicParsing -TimeoutSec 60
        Ok "gateway reachable through the relay (HTTP $($r.StatusCode))"
    } catch { Warn "relay is up but the Hermes gateway didn't answer: run 'hermes gateway ensure' and re-run this installer" }
    # Inbound rule for the phone (Wi-Fi/LAN/Tailscale); Private+Domain only, so public networks stay closed.
    try {
        Get-NetFirewallRule -DisplayName "Hermes phone relay" -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
        New-NetFirewallRule -DisplayName "Hermes phone relay" -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -Profile Private,Domain -ErrorAction Stop | Out-Null
        Ok "firewall: inbound TCP $Port allowed on Private/Domain networks (Tailscale works either way)"
    } catch { Warn "could not add a firewall rule for :$Port ($($_.Exception.Message)): allow it manually if the phone cannot connect" }

    # ------------------------------------------------------------ 4. update folder + APK
    Step "App package"
    New-Item -ItemType Directory -Force $Updates | Out-Null
    if (-not $Apk) {
        $built = Join-Path (Split-Path $Here) "app\build\outputs\apk\debug\app-debug.apk"
        if (Test-Path $built) { $Apk = $built }
    }
    $script:ApkUrl = "https://github.com/$Repo/releases/latest"
    try {
        $rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -TimeoutSec 20 -Headers @{ "User-Agent" = "hermes-android-installer" }
        $asset = $rel.assets | Where-Object { $_.name -like "*.apk" } | Select-Object -First 1
        if ($asset) {
            $script:ApkUrl = $asset.browser_download_url
            if (-not $Apk) { $Apk = Join-Path $env:TEMP $asset.name; Invoke-WebRequest $asset.browser_download_url -OutFile $Apk -UseBasicParsing; Ok "downloaded $($asset.name)" }
        }
    } catch { Warn "couldn't reach GitHub releases ($($_.Exception.Message))" }
    if ($Apk -and (Test-Path $Apk)) {
        if (-not (Test-Path (Join-Path $Updates "app.apk"))) {
            Copy-Item $Apk (Join-Path $Updates "app.apk") -Force
            $m = [regex]::Match((Split-Path $Apk -Leaf), '(\d+\.\d+\.\d+)-(\d+)')
            if ($m.Success) { @{ version_code = [int]$m.Groups[2].Value; version_name = $m.Groups[1].Value; notes = "" } | ConvertTo-Json | Set-Content (Join-Path $Updates "latest.json") -Encoding utf8 }
        }
        Ok "APK ready: $(Split-Path $Apk -Leaf)"
    } else { Warn "no APK found; the phone can download it from $script:ApkUrl" }
    if (Test-Path (Join-Path (Split-Path $Here) "hotfix.json")) { Copy-Item (Join-Path (Split-Path $Here) "hotfix.json") $Updates -Force }

    # ------------------------------------------------------------ 5. skill for the local Hermes
    Step "Hermes skill"
    $allDirs = @(Profile-Dirs)
    $skills = @("hermes-android", "hermes-agent-screen")
    $syncOn = Test-Path (Join-Path $HH "profile-sync\sync_engine.py")
    if ($syncOn) {
        # Profile sync daemon present: the default profile's skills\ folder is the shared layer every profile reads (skills.external_dirs).
        # Install SKILL.md there only (references\ stays); a copy inside another profile would shadow the shared one and freeze it.
        Ok "profile sync detected: skills go to the shared layer (default) only"
        $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
        foreach ($sk in $skills) {
            $dst = Join-Path $HH "skills\software-development\$sk"
            New-Item -ItemType Directory -Force $dst | Out-Null
            Copy-Item (Join-Path $Here "skill\$sk\SKILL.md") $dst -Force
            foreach ($d in $allDirs) {
                if ($d -eq $HH) { continue }
                $loc = Join-Path $d "skills\software-development\$sk"
                if (Test-Path $loc) {
                    $bk = Join-Path $HH ("profile-sync\backups\skill-installer\$stamp\" + (Profile-Name $d))
                    New-Item -ItemType Directory -Force $bk | Out-Null
                    Move-Item $loc $bk -Force
                    Ok "moved the shadowing copy of '$sk' out of profile $(Profile-Name $d) (backup: $bk)"
                }
            }
            Ok "skill '$sk' installed in the shared layer"
        }
        Warn "to hide a skill from some profile, add its name to that profile's skills.disabled (the installer does not edit config)"
    } else {
    $noAsk = $AllProfiles -or ($Profiles.Count -gt 0) -or [Console]::IsInputRedirected -or -not [Environment]::UserInteractive
    $picked = $null
    if (-not $noAsk -and -not $env:HERMES_INSTALL_NO_GUI) { $picked = Show-SkillPicker $skills $allDirs }
    foreach ($sk in $skills) {
        $chosen = if ($picked -ne $null) { @($picked[$sk]) } else { @(Select-Profiles $sk $allDirs -Names $Profiles -All:$AllProfiles -NonInteractive:$noAsk) }
        foreach ($d in $chosen) {
            $dst = Join-Path $d "skills\software-development\$sk"
            New-Item -ItemType Directory -Force $dst | Out-Null
            Copy-Item (Join-Path $Here "skill\$sk\SKILL.md") $dst -Force
        }
        Ok "skill '$sk' installed in $($chosen.Count) of $($allDirs.Count) profile(s)"
    }
    }

    # ------------------------------------------- tray app (admin: owns relay + gateway + Tailscale + vdisplay host)
    Step "Tray app"
    foreach ($f in "hermes-tray.ps1", "hermes-tray.vbs", "tailscale-connect.ps1", "hotspot.ps1", "install-tray-task.ps1", "update-pc.ps1") { Copy-Item (Join-Path $Here "tray\$f") $Launch -Force }
    # virtual display (agent screen) CLI + driver installer; the driver is installed by the next step
    foreach ($f in "hermes-vdisplay.py", "live_sessions.py", "install-vdd.ps1") { Copy-Item (Join-Path $Here "vdisplay\$f") $Launch -Force }
    Copy-Item (Join-Path $Here "vdisplay\sudovda") $Launch -Recurse -Force   # on-demand agent + screen-off monitors
    # phone-approved elevation (task + whitelist are set up on demand with install-elevate.ps1)
    foreach ($f in "hermes-elevate.py", "install-elevate.ps1") { Copy-Item (Join-Path $Here "elevate\$f") $Launch -Force }
    Copy-Item (Join-Path $Here "install.ps1") $Launch -Force      # the tray's "Show pairing QR code" runs it with -PairOnly
    # The tray now runs as administrator via its own scheduled task; drop the old HKCU Run entry and the
    # Hermes_PhoneRelay keep-alive task so the tray is the single owner of the relay (no double start).
    Unregister-ScheduledTask -TaskName $Task -Confirm:$false -ErrorAction SilentlyContinue
    Remove-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "HermesAndroidTray" -ErrorAction SilentlyContinue
    # register + start the elevated tray (self-elevates; the user has UAC elevate-without-prompt on)
    Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$(Join-Path $Launch 'install-tray-task.ps1')`"" -Wait -WindowStyle Hidden
    Add-TrayShortcuts
    Ok "tray icon started as administrator (logon task; toggle in its menu); shortcuts in Start menu and on the desktop"

    # ------------------------------------------- virtual display driver (SudoVDA; install-vdd.ps1 self-elevates, one UAC prompt)
    Step "Virtual display driver"
    $vddOk = $false
    try { $vddOk = [bool](Get-PnpDevice -Class Display -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains "root\sudomaker\sudovda" }) } catch {}
    if ($vddOk) { Ok "virtual display driver already installed" }
    else {
        $vdd = Join-Path $Launch "install-vdd.ps1"
        if (-not (Test-Path $vdd)) { Warn "install-vdd.ps1 missing: virtual display driver not installed" }
        else {
            try {
                $p = Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$vdd`"" -PassThru -ErrorAction Stop
                if ($p.WaitForExit(300000)) {
                    if ($p.ExitCode -eq 0) { Ok "virtual display driver installed" } else { Warn "install-vdd.ps1 exited with code $($p.ExitCode): virtual display driver may not be installed (re-run install.cmd to retry)" }
                } else { Warn "install-vdd.ps1 still running after 5 minutes; continuing" }
            } catch { Warn "virtual display driver not installed ($($_.Exception.Message)); re-run install.cmd to retry" }
        }
    }
} else {
    $key = Get-Key; if (-not $key) { throw "No API_SERVER_KEY yet: run install.cmd without -PairOnly first." }
    $script:ApkUrl = "https://github.com/$Repo/releases/latest"
}

# ---------------------------------------------------------------- 6. pair the phone
Step "Pair the phone"
$url = if ($ts) { "http://${ts}:$Port" } elseif ($lan) { "http://${lan}:$Port" } else { throw "No Tailscale or LAN address found." }
$lanUrl = if ($lan -and $ts) { "http://${lan}:$Port" } else { "" }
$pair = "hermes://pair?url=" + [uri]::EscapeDataString($url) + "&lan=" + [uri]::EscapeDataString($lanUrl) + "&key=" + $key
try { Set-Clipboard -Value $key; $clip = $true } catch { $clip = $false }   # no clipboard in non-interactive sessions
Ok "server address: $url$(if ($lanUrl) { "   (Wi-Fi fallback: $lanUrl)" })"
if ($clip) { Ok "API key copied to the clipboard (not shown)" } else { Warn "no clipboard available; the API key is in the Hermes .env (API_SERVER_KEY)" }

$ErrorActionPreference = "Continue"
$adbExe = Find-Adb
# Prefer a real phone over the emulator / WSA (127.0.0.1 / emulator-*), if both are attached.
$devs = if ($adbExe -and -not $NoAdb) { @(& $adbExe devices 2>$null | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] }) } else { @() }
$dev = ($devs | Where-Object { $_ -notmatch '^(127\.0\.0\.1|emulator-)' } | Select-Object -First 1)
if (-not $dev) { $dev = $devs | Select-Object -First 1 }
if ($dev) {
    $serial = $dev
    Ok "phone connected over adb ($serial)"
    if ($Apk -and (Test-Path $Apk) -and -not $PairOnly) { & $adbExe -s $serial install -r $Apk | Out-Null; Ok "app installed" }
    $out = (& $adbExe -s $serial shell am start -a android.intent.action.VIEW -d "'$pair'" dev.oscar.hermes 2>&1 | Out-String)
    if ($out -match 'Error|unable to resolve') {
        Warn "the app on $serial couldn't open the pairing link (too old?). Re-run without -PairOnly to install the current app, or use -NoAdb for the QR page."
    } else { Ok "pairing sent: the app on the phone should now show its sessions" }
} else {
    Write-Host "   No phone on adb: opening a page with QR codes (download app, then pair)."
    # Native tools write to stderr on expected failures; don't let "Stop" turn that into a crash.
    $ErrorActionPreference = "Continue"
    & $Py -I -c "import sys; sys.path.insert(0, r'$PyLib'); import segno" 2>$null | Out-Null
    if ($LASTEXITCODE) {
        # Hermes's venv has no pip (uv-managed): fetch the pure-Python segno wheel from PyPI and unzip it into pylib.
        try {
            $meta = Invoke-RestMethod "https://pypi.org/pypi/segno/json" -TimeoutSec 20
            $whl = $meta.urls | Where-Object { $_.filename -like "*py3-none-any.whl" } | Select-Object -First 1
            $zip = Join-Path $env:TEMP "segno.zip"
            Invoke-WebRequest $whl.url -OutFile $zip -UseBasicParsing
            New-Item -ItemType Directory -Force $PyLib | Out-Null
            Expand-Archive $zip -DestinationPath $PyLib -Force; Remove-Item $zip
            Ok "QR library installed (segno $($meta.info.version))"
        } catch { Warn "couldn't download the QR library: $($_.Exception.Message)" }
    }
    $page = Join-Path $Launch "hermes-android-pair.html"
    & $Py -I -c "import sys; sys.path.insert(0, r'$PyLib'); sys.argv = ['pair_page.py', r'$page', r'$script:ApkUrl', r'$pair']; exec(open(r'$(Join-Path $Launch 'pair_page.py')', encoding='utf-8').read())"
    if (Test-Path $page) { Start-Process $page; Ok "pairing page opened ($page)" }
    else { Warn "couldn't build the QR page. In the app: Settings → server $url, paste the key from the clipboard." }
}
Write-Host "`nDone. 完成。" -ForegroundColor Green
