@echo off
rem Capture the running UI to docs\img\ui.png and docs\img\refuse.png with headless Edge (Windows).
rem Run tools\run.bat first, then double-click this file.
rem demo=static / demo=refuse return the whole answer at once, so a capture never cuts a streamed reply in half.
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


echo [1/2] capturing answered case  -^> docs\img\ui.png ...
"%EDGE%" --headless=new --disable-gpu --hide-scrollbars --no-first-run ^
  --user-data-dir="%TEMP%\rag-shot-profile" ^
  --window-size=1440,1080 --force-device-scale-factor=1 ^
  --virtual-time-budget=20000 ^
  --screenshot="%CD%\docs\img\ui.png" "http://127.0.0.1:8765/?demo=static"

echo [2/2] capturing refused case   -^> docs\img\refuse.png ...
"%EDGE%" --headless=new --disable-gpu --hide-scrollbars --no-first-run ^
  --user-data-dir="%TEMP%\rag-shot-profile-b" ^
  --window-size=1440,460 --force-device-scale-factor=1 ^
  --virtual-time-budget=20000 ^
  --screenshot="%CD%\docs\img\refuse.png" "http://127.0.0.1:8765/?demo=refuse"

if exist "docs\img\ui.png" (echo OK -^> docs\img\ui.png) else (echo [WARN] ui.png not produced. Is the server running on 8765?)
if exist "docs\img\refuse.png" (echo OK -^> docs\img\refuse.png) else (echo [WARN] refuse.png not produced.)
pause
endlocal
