@echo off
rem Launch the Fabric dev client OUTSIDE IntelliJ - a frozen/force-killed game
rem can never take the IDE down with it. Double-click, or run from a terminal.
rem
rem --no-daemon is CRITICAL: with the daemon, the game runs INSIDE a shared
rem Gradle daemon process, and any "gradlew --stop" from ANY project using the
rem same Gradle version (e.g. a parallel Claude session doing cleanup) stops
rem that daemon and KILLS the live game with it - no crash report, no error,
rem the window just closes ("Gradle build daemon has been stopped: stop
rem command received", seen 2026-07-05). With --no-daemon the build runs in
rem this launcher process, immune to daemon stops. Costs a few seconds of
rem Gradle startup per launch.
cd /d "%~dp0"
call gradlew.bat :fabric:runClient --console=plain --no-daemon
echo.
echo (client exited - press any key to close)
pause >nul
