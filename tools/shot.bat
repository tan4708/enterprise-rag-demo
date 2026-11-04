@echo off
rem Capture the running UI to docs\img\ui.png with headless Edge (Windows).
rem Run tools\run.bat first, then double-click this file.
setlocal
cd /d "%~dp0.."
set "EDGE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist "%EDGE%" set "EDGE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if not exist "%EDGE%" (
  echo [ERROR] Microsoft Edge not found.
  pause
  exit /b 1
)
if not exist "docs\img" mkdir "docs\img"

echo capturing http://127.0.0.1:8765/?demo=1 ...
"%EDGE%" --headless=new --disable-gpu --hide-scrollbars --no-first-run ^
  --user-data-dir="%TEMP%\rag-shot-profile" ^
  --window-size=1440,1180 --force-device-scale-factor=1.2 ^
  --virtual-time-budget=6000 ^
  --screenshot="%CD%\docs\img\ui.png" "http://127.0.0.1:8765/?demo=1"

if exist "docs\img\ui.png" (
  echo OK -^> docs\img\ui.png
) else (
  echo [WARN] no screenshot produced. Is the server running on 8765?
)
pause
endlocal
