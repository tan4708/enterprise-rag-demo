@echo off
rem Start the demo server on port 8765 (Windows one-click helper).
rem Needs JDK 17+ (JAVA_HOME, or java/javac on PATH).
setlocal
cd /d "%~dp0..\demo"

if not "%JAVA_HOME%"=="" (
  set "JAVAC=%JAVA_HOME%\bin\javac.exe"
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVAC=javac"
  set "JAVA=java"
)

"%JAVAC%" -version >nul 2>&1
if errorlevel 1 (
  echo [ERROR] javac not found. Install JDK 17+ or set JAVA_HOME.
  pause
  exit /b 1
)

if not exist "out" mkdir "out"
echo [1/2] compiling...
"%JAVAC%" -encoding UTF-8 -d out src\com\tanke\rag\*.java
if errorlevel 1 (
  echo [ERROR] compile failed.
  pause
  exit /b 1
)

echo [2/2] starting on http://127.0.0.1:8765/  (Ctrl+C to stop)
start "" "http://127.0.0.1:8765/"
"%JAVA%" -cp out com.tanke.rag.Main 8765
endlocal
