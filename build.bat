@echo off
rem ------------------------------------------------------------
rem build.bat  <output-file>
rem Builds Catalan.exe via Gradle → copies it to the name OpenBench wants
rem ------------------------------------------------------------
setlocal

if "%~1"=="" (
  echo Usage: build.bat ^<output-file^>
  exit /b 1
)

set "GRADLE=%~dp0gradlew.bat"
set "PACKED=build\dist\Catalan.exe"

rem Resolve OUT to a full path based on current dir if user passed only a name
set "OUT=%~f1"

echo === Cleaning build ===
call "%GRADLE%" --no-daemon --console=plain clean || exit /b 1

echo === Building single-file exe ===
call "%GRADLE%" --no-daemon --console=plain warpPack || exit /b 1

if not exist "%PACKED%" (
  echo ERROR: %PACKED% not found
  exit /b 1
)

rem Ensure target has .exe unless caller already provided it
if /I "%~x1"==".exe" (
  set "TARGET=%OUT%"
) else (
  set "TARGET=%OUT%.exe"
)

rem Create target directory if missing
for %%I in ("%TARGET%") do set "TARGET_DIR=%%~dpI"
if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"

echo === Copying to %TARGET% ===
copy /Y "%PACKED%" "%TARGET%" >nul

echo Done.
endlocal