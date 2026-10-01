@echo off
cd /d "%~dp0"
if not exist "rc-relay.exe" (
    echo rc-relay.exe not found in %~dp0
    pause
    exit /b 1
)
rc-relay.exe --port 9000
pause
