@echo off
setlocal
cd /d "%~dp0"
title Build Relay

set "PATH=C:\Qt\Tools\mingw1310_64\bin;%PATH%"
set "CXX=C:\Qt\Tools\mingw1310_64\bin\g++.exe"

if not exist "%CXX%" (
    echo MinGW g++ not found: %CXX%
    goto fail
)
if not exist "%~dp0relay.cpp" (
    echo relay.cpp not found.
    goto fail
)

"%CXX%" -O2 -std=c++17 -static -o "%~dp0rc-relay.exe" "%~dp0relay.cpp" -lws2_32 -ladvapi32
if errorlevel 1 goto fail

echo.
echo BUILD SUCCEEDED
echo %~dp0rc-relay.exe
pause
exit /b 0

:fail
echo.
echo BUILD FAILED
pause
exit /b 1
