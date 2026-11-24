@echo off
rem Hermes Android - one-click PC setup. Double-click, or: install.cmd -Uninstall (removes everything incl. the virtual display driver, one UAC) / -PairOnly / -Profiles a,b / -AllProfiles
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
echo.
pause
