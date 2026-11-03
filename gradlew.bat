@echo off
setlocal
set VERSION=8.14.6
set SHA256=7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24
if defined GRADLE_USER_HOME (set BASE=%GRADLE_USER_HOME%\device-manager-bootstrap) else (set BASE=%USERPROFILE%\.gradle\device-manager-bootstrap)
set DIST=%BASE%\gradle-%VERSION%
set BIN=%DIST%\bin\gradle.bat
if exist "%BIN%" goto run
if not exist "%BASE%" mkdir "%BASE%"
set ZIP=%BASE%\gradle-%VERSION%-bin.zip
 echo Gradle %VERSION% belum tersedia; mengunduh distribusi resmi...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'; $h=(Get-FileHash '%ZIP%' -Algorithm SHA256).Hash.ToLower(); if ($h -ne '%SHA256%') { Remove-Item '%ZIP%' -Force; throw 'SHA-256 Gradle tidak cocok' }; Expand-Archive -Path '%ZIP%' -DestinationPath '%BASE%' -Force; Remove-Item '%ZIP%' -Force"
if errorlevel 1 exit /b 1
:run
call "%BIN%" %*
exit /b %ERRORLEVEL%
