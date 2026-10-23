@echo off
rem Checks this installation and tells you what is wrong, in plain words. Run it after installing and whenever something misbehaves.
rem It never prints passwords or keys. The result is also saved in selfcheck-result.txt: send that file when asking for help.
cd /d "%~dp0"
if not exist "fuel-marking-desktop-win.jar" (
  echo Cannot find fuel-marking-desktop-win.jar in %~dp0
  pause
  exit /b 1
)
java -jar "fuel-marking-desktop-win.jar" --selfcheck > selfcheck-result.txt 2>&1
type selfcheck-result.txt
echo.
echo (saved to %~dp0selfcheck-result.txt)
pause
