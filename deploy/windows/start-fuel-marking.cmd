@echo off
rem Starts Fuel Marking. Keep this file in the SAME folder as fuel-marking-desktop-win.jar and application-local.yml.
rem A minimised console window stays open while the app runs (closing it closes the app); it shows messages if something goes wrong.
cd /d "%~dp0"
if not exist "fuel-marking-desktop-win.jar" (
  echo Cannot find fuel-marking-desktop-win.jar in %~dp0
  pause
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 is not installed, or it is not on the PATH. Install a Java 21 runtime, for example Eclipse Temurin 21, and try again.
  pause
  exit /b 1
)
start "Fuel Marking" /min java -jar "fuel-marking-desktop-win.jar"
