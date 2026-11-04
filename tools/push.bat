@echo off
rem Double-click me to push this repo to GitHub. Creates the repo if needed.
setlocal
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push.ps1" %*
echo.
pause
endlocal
