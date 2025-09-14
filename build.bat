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

rem Resolve OUT to a full path based on current dir if user passed only a name
set "OUT=%~f1"

echo === Cleaning build ===
call "%GRADLE%" --no-daemon --console=plain clean || exit /b 1

echo === Looking for local GraalVM toolchain ===
set "GRAAL_LOCAL=C:\graalvm\graalvm-jdk-24.0.2+11.1"
if exist "%GRAAL_LOCAL%\bin\native-image.cmd" (
  echo Using local GraalVM: %GRAAL_LOCAL%
  set "JAVA_HOME=%GRAAL_LOCAL%"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
) else (
  echo Local GraalVM not found at %GRAAL_LOCAL%, relying on system/toolchains.
  rem Reference download URL (not used by this script):
  rem https://download.oracle.com/graalvm/24/archive/graalvm-jdk-24.0.2_windows-x64_bin.zip
)

echo === Building GraalVM native exe ===
call "%GRADLE%" --no-daemon --console=plain packageNative || exit /b 1

rem Locate the produced native exe robustly
set "PACKED="
for /f "delims=" %%F in ('dir /b /s "build\native\nativeCompile\*.exe" 2^>nul') do set "PACKED=%%F"

if not defined PACKED (
  echo ERROR: Native exe not found under build\native\nativeCompile
  exit /b 1
)

echo Found native exe: %PACKED%

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
