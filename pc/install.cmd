@echo off
rem Hermes Android - one-click PC setup. Double-click, or: install.cmd -Uninstall / -PairOnly
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
echo.
pause
