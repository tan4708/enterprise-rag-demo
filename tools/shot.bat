@echo off
rem Capture the running UI to docs\img\ui.png and docs\img\refuse.png with headless Edge (Windows).
rem Run tools\run.bat first, then double-click this file.
rem demo=static returns the whole answer at once, so the capture never cuts a streamed reply in half.
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

set "Q_REFUSE=%E5%A6%82%E4%BD%95%E8%AE%AD%E7%BB%83%E4%B8%80%E4%B8%AA%E5%A4%9A%E6%A8%A1%E6%80%81%E5%A4%A7%E6%A8%A1%E5%9E%8B%EF%BC%9F"

echo [1/2] capturing answered case  -^> docs\img\ui.png ...
"%EDGE%" --headless=new --disable-gpu --hide-scrollbars --no-first-run ^
  --user-data-dir="%TEMP%\rag-shot-profile" ^
  --window-size=1440,1080 --force-device-scale-factor=1 ^
  --virtual-time-budget=20000 ^
  --screenshot="%CD%\docs\img\ui.png" "http://127.0.0.1:8765/?demo=static"

echo [2/2] capturing refused case   -^> docs\img\refuse.png ...
"%EDGE%" --headless=new --disable-gpu --hide-scrollbars --no-first-run ^
  --user-data-dir="%TEMP%\rag-shot-profile-b" ^
  --window-size=1440,700 --force-device-scale-factor=1 ^
  --virtual-time-budget=20000 ^
  --screenshot="%CD%\docs\img\refuse.png" "http://127.0.0.1:8765/?demo=static&q=%Q_REFUSE%"

if exist "docs\img\ui.png" (echo OK -^> docs\img\ui.png) else (echo [WARN] ui.png not produced. Is the server running on 8765?)
if exist "docs\img\refuse.png" (echo OK -^> docs\img\refuse.png) else (echo [WARN] refuse.png not produced.)
pause
endlocal
