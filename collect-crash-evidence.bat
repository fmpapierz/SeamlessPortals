@echo off
rem Double-click after IntelliJ closes on its own. Self-elevates (accept the
rem UAC prompt), then writes crash-evidence.txt next to this file.
net session >nul 2>&1
if errorlevel 1 (
    echo Requesting administrator rights - accept the prompt...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0collect-crash-evidence.ps1"
echo(
echo If it says "Wrote ...crash-evidence.txt", tell Claude to read it.
pause
