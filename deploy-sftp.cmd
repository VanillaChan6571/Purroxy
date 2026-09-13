@echo off
setlocal EnableExtensions
rem Uploads one built jar to every server named in deploy-sftp.local.cmd.
rem Usage: deploy-sftp.cmd [path/to/jar]   (defaults to the newest proxy jar)

set "PROJECT_DIR=%~dp0"
if not exist "%PROJECT_DIR%deploy-sftp.local.cmd" (
  echo Missing deploy-sftp.local.cmd. Copy deploy-sftp.example.cmd and fill it in.
  exit /b 1
)
call "%PROJECT_DIR%deploy-sftp.local.cmd"

set "JAR=%~1"
rem /o-d lists newest first, so the first match is the jar that was just built.
if not defined JAR for /f "delims=" %%F in ('dir /b /o-d "%PROJECT_DIR%proxy\build\libs\velocity-proxy-*-all.jar" 2^>nul') do if not defined JAR set "JAR=%PROJECT_DIR%proxy\build\libs\%%F"

if not defined JAR (
  echo No built jar found. Run gradlew :velocity-proxy:shadowJar first.
  exit /b 1
)
if not exist "%JAR%" (
  echo Jar not found: %JAR%
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%deploy-sftp.ps1" -JarPath "%JAR%" -Prefix PURROXY
exit /b %ERRORLEVEL%
