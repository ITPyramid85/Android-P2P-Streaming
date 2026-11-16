@echo off
setlocal
cd /d "%~dp0"
title Build Mobile

rem Wrapped JDK so the Gradle daemon is not broken by the Astrill Winsock LSP.
set "JAVA_HOME=C:\Sdk\jdk-wrap"
set "JAVA_REAL=C:\Sdk\jdk-wrap\bin\java_real.exe"
set "JAVA_TOOL_OPTIONS="
set "JAVA_LSP_DEBUG="
set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "GRADLE=C:\Program Files\Android\Android Studio\.gradle\wrapper\dists\gradle-8.14-all\c2qonpi39x1mddn7hk5gh9iqj\gradle-8.14\bin\gradle.bat"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JDK wrapper not found: %JAVA_HOME%\bin\java.exe
    goto fail
)
if not exist "%JAVA_REAL%" (
    echo Real JDK not found: %JAVA_REAL%
    goto fail
)
if not exist "%GRADLE%" (
    echo Gradle 8.14 not found: %GRADLE%
    goto fail
)

call "%GRADLE%" assembleDebug
if errorlevel 1 goto fail

echo.
echo BUILD SUCCEEDED
echo %~dp0build-app\outputs\apk\debug\app-debug.apk
pause
exit /b 0

:fail
echo.
echo BUILD FAILED
pause
exit /b 1
