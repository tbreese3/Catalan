@echo off
rem ------------------------------------------------------------
rem build.bat  [nopgo] <output-file>
rem - Default: PGO flow (instrument -> run -> optimize), copies to <output-file>[.exe]
rem - nopgo: plain native build only
rem   (instrument phase applies Windows workaround: -H:-SamplingCollect)
rem ------------------------------------------------------------
setlocal

set "MODE=pgo"
if /I "%~1"=="pgo" (
  shift
  goto :args_done
)
if /I "%~1"=="nopgo" (
  set "MODE=normal"
  shift
  goto :args_done
)
if /I "%~1"=="plain" (
  set "MODE=normal"
  shift
  goto :args_done
)
if /I "%~1"=="normal" (
  set "MODE=normal"
  shift
  goto :args_done
)
:args_done

if "%~1"=="" (
  echo Usage: build.bat [nopgo] ^<output-file^>
  exit /b 1
)

set "GRADLE=%~dp0gradlew.bat"

rem Resolve OUT to a full path based on current dir if user passed only a name
set "OUT=%~f1"

echo === Cleaning build ===
call "%GRADLE%" --no-daemon --console=plain clean || exit /b 1

echo === Preparing GraalVM toolchain (download if needed) ===
call "%GRADLE%" --no-daemon --console=plain prepareGraalToolchain || exit /b 1

rem Discover downloaded GraalVM path and export JAVA_HOME/PATH for this process
set "GRAAL_DL="
for /f "delims=" %%D in ('dir /b /ad "build\graalvm\graalvm-jdk-*" 2^>nul') do set "GRAAL_DL=%CD%\build\graalvm\%%D"
if defined GRAAL_DL (
  echo Using downloaded GraalVM: %GRAAL_DL%
  set "JAVA_HOME=%GRAAL_DL%"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
) else (
  echo No downloaded GraalVM found, relying on system/toolchains.
)

if /I "%MODE%"=="pgo" goto :pgo_flow

echo === Building GraalVM native exe ===
call "%GRADLE%" --no-daemon --console=plain packageNative || exit /b 1
goto :copy_out

:pgo_flow
echo === Building instrumented native exe (with -H:-SamplingCollect workaround) ===
call "%GRADLE%" --no-daemon --console=plain packageNative -PpgoInstrument || exit /b 1

rem Locate the instrumented exe
set "PACKED="
for /f "delims=" %%F in ('dir /b /s "build\native\nativeCompile\*.exe" 2^>nul') do set "PACKED=%%F"
if not defined PACKED (
  echo ERROR: Instrumented exe not found under build\native\nativeCompile
  exit /b 1
)
echo Found instrumented exe: %PACKED%

rem Run the instrumented exe to generate the .iprof
pushd "%~dp0"
pushd "%~dp0build\native\nativeCompile" || exit /b 1
echo === Running instrumented workload to collect profile (go movetime 15000) ===
"%PACKED%" pgo
set "IPROF=%CD%\default.iprof"
popd
popd

if not exist "%IPROF%" (
  echo ERROR: Profile not generated: %IPROF%
  exit /b 1
)
echo Found profile: %IPROF%

echo === Building optimized native exe with --pgo ===
call "%GRADLE%" --no-daemon --console=plain packageNative -PpgoUse -PpgoProfile="%IPROF%" || exit /b 1

:copy_out
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
