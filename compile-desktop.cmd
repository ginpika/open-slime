@echo off
setlocal
rem ============================================================================
rem compile-desktop.cmd - rebuild the prebuilt runtime jar used by the desktop shell
rem
rem Why: desktop/runtime/ is a prebuilt bundle (jlink JRE + minis-backend.jar) that
rem the Tauri shell launches directly. Tauri never rebuilds it, so after changing
rem backend Java (or frontend React) you must repackage the jar and copy it there.
rem
rem Usage (run from the repo root, or just double-click):
rem   compile-desktop.cmd            rebuild backend jar only (-Pdesktop);
rem                                  builds the frontend first if frontend\dist is missing
rem   compile-desktop.cmd frontend   also rebuild the frontend dist (after frontend/ changes)
rem
rem Changing only the Rust shell or desktop/ui/index.html needs no script:
rem just run `tauri dev` / `tauri build`.
rem ============================================================================

cd /d "%~dp0"

set "ROOT=%CD%"
set "FRONTEND=%ROOT%\frontend"
set "BACKEND=%ROOT%\backend"
set "DESKTOP=%ROOT%\desktop"

rem 品牌名以根 branding.json 为准：先同步静态配置，再取 slug 拼出 jar 名
node "%ROOT%\scripts\brand-sync.mjs"
for /f "delims=" %%S in ('node -p "require('./branding.json').slug"') do set "SLUG=%%S"
if not defined SLUG set "SLUG=minis"
set "RUNTIME_JAR=%DESKTOP%\runtime\%SLUG%-backend.jar"

if not exist "%BACKEND%\mvnw.cmd" (
  echo [x] mvnw.cmd not found at %BACKEND% - run this script from the repo root
  exit /b 1
)

rem Rebuild the frontend when asked, or when its dist is missing.
set "BUILD_FRONTEND="
if /I "%~1"=="frontend" set "BUILD_FRONTEND=1"
if /I "%~1"=="full" set "BUILD_FRONTEND=1"
if not defined BUILD_FRONTEND if not exist "%FRONTEND%\dist\index.html" set "BUILD_FRONTEND=1"

if defined BUILD_FRONTEND (
  echo == 1/3 build frontend ==
  pushd "%FRONTEND%" || exit /b 1
  if not exist "node_modules" (
    echo    node_modules missing, running npm install
    call npm install || (popd ^& exit /b 1)
  )
  call npm run build || (popd ^& exit /b 1)
  popd
) else (
  echo == 1/3 skip frontend ^(reuse existing dist; pass "frontend" to rebuild^)
)

echo == 2/3 package backend ^(-Pdesktop: bundle frontend dist into jar static/^) ==
pushd "%BACKEND%" || exit /b 1
call .\mvnw.cmd -q -B -Pdesktop -DskipTests package || (popd ^& exit /b 1)
popd

echo == 3/3 update runtime\%SLUG%-backend.jar ==
set "JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%BACKEND%\target\%SLUG%-backend-*.jar" 2^>nul') do (
  set "JAR=%BACKEND%\target\%%F"
  goto :jar_found
)
:jar_found
if not defined JAR (
  echo [x] no %BACKEND%\target\minis-backend-*.jar found - package step failed?
  exit /b 1
)

if not exist "%DESKTOP%\runtime" mkdir "%DESKTOP%\runtime"

copy /Y "%JAR%" "%RUNTIME_JAR%" >nul
if errorlevel 1 (
  echo [x] failed to overwrite %RUNTIME_JAR%
  echo     it may be locked by a running local backend - close the minis app and retry.
  exit /b 1
)

for %%F in ("%RUNTIME_JAR%") do echo updated: %%~fF  ^(%%~zF bytes, %%~tF^)
echo done. local mode will use the new jar on next launch.
exit /b 0
