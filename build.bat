@echo off
rem Compile common + fabric OUTSIDE IntelliJ (quick check before running the client).
cd /d "%~dp0"
call gradlew.bat :common:compileJava :fabric:compileJava --console=plain
echo.
echo (build finished — press any key to close)
pause >nul
