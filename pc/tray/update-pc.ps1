# Hermes Android PC update: downloads the latest release's PC bundle and overwrites the files in %LOCALAPPDATA%\hermes\launchers.
# No elevation needed. Writes one line to update-result.txt: "uptodate <tag>" | "updated <tag>" | "fail <reason>".
param([switch]$Check)
$ErrorActionPreference = "Stop"
$Repo = "o12r/HermesAndroid"
$HH = Join-Path $env:LOCALAPPDATA "hermes"; $Launch = Join-Path $HH "launchers"
$Res = Join-Path $Launch "update-result.txt"; $Ver = Join-Path $Launch "pc-version.txt"
function Done($s) { Set-Content -Path $Res -Value $s -Encoding UTF8; exit 0 }
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ "User-Agent" = "HermesAndroid" } -TimeoutSec 30
    $tag = $rel.tag_name
    $cur = if (Test-Path $Ver) { (Get-Content $Ver -Raw).Trim() } else { "" }
    if ($cur -eq $tag) { Done "uptodate $tag" }
    if ($Check) { Done "available $tag" }
    $asset = $rel.assets | Where-Object { $_.name -like "HermesAndroid-PC-*.zip" } | Select-Object -First 1
    if (-not $asset) { Done "fail no PC bundle in release $tag" }
    $tmp = Join-Path $env:TEMP ("hermes-pc-update-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $tmp | Out-Null
    $zip = Join-Path $tmp "pc.zip"
    Invoke-WebRequest $asset.browser_download_url -OutFile $zip -UseBasicParsing -TimeoutSec 300
    Expand-Archive $zip -DestinationPath $tmp -Force
    $Here = Join-Path $tmp "HermesAndroid-PC"
    if (-not (Test-Path (Join-Path $Here "install.ps1"))) { Done "fail bundle layout unexpected" }
    function Put($sub, $files) { foreach ($f in $files) { Copy-Item (Join-Path $Here "$sub\$f") $Launch -Force } }
    Put "relay" @("hermes-phone-relay.py", "ensure-phone-relay.ps1", "ensure-phone-relay.vbs", "send-to-phone.py")
    Copy-Item (Join-Path $Here "pair_page.py") $Launch -Force
    Put "tray" @("hermes-tray.ps1", "hermes-tray.vbs", "tailscale-connect.ps1", "hotspot.ps1", "install-tray-task.ps1", "update-pc.ps1")
    Put "vdisplay" @("hermes-vdisplay.py", "live_sessions.py", "install-vdd.ps1")
    Copy-Item (Join-Path $Here "vdisplay\sudovda") $Launch -Recurse -Force
    Put "elevate" @("hermes-elevate.py", "install-elevate.ps1")
    Copy-Item (Join-Path $Here "install.ps1") $Launch -Force
    # skills: only where they are already installed (default profile + every named profile)
    $roots = @($HH) + @(Get-ChildItem (Join-Path $HH "profiles") -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    foreach ($sk in "hermes-android", "hermes-agent-screen") {
        foreach ($r in $roots) {
            $dst = Join-Path $r "skills\software-development\$sk"
            if (Test-Path $dst) { Copy-Item (Join-Path $Here "skill\$sk\SKILL.md") $dst -Force }
        }
    }
    Set-Content $Ver $tag -Encoding ASCII
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Done "updated $tag"
} catch { Done "fail $($_.Exception.Message)" }
