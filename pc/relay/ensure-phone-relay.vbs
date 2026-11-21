' Runs ensure-phone-relay.ps1 with no visible window (scheduled task entry point).
CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & CreateObject("WScript.Shell").ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\hermes\launchers\ensure-phone-relay.ps1""", 0, False
