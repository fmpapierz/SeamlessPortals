@echo off
rem ONE-TIME setup (right-click -> Run as administrator).
rem Enables Windows process-creation auditing with command lines, so the NEXT
rem time IntelliJ vanishes we can see exactly what program ran at that second
rem (taskkill, an updater, a cleaner, ...) in the Security event log (Event 4688).
net session >nul 2>&1
if errorlevel 1 (
    echo This needs administrator rights. Right-click this file and
    echo choose "Run as administrator".
    pause
    exit /b 1
)
auditpol /set /subcategory:"Process Creation" /success:enable /failure:enable
auditpol /set /subcategory:"Process Termination" /success:enable
reg add "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System\Audit" /v ProcessCreationIncludeCmdLine_Enabled /t REG_DWORD /d 1 /f
echo(
echo Done. Process starts (with command lines) and exits are now audited.
echo When IntelliJ closes on its own again, note the time and tell Claude.
pause
