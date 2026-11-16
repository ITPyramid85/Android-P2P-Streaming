@echo off
setlocal
cd /d "%~dp0"
title Build Controller

set "PATH=C:\Qt\Tools\mingw1310_64\bin;C:\Qt\Tools\Ninja;C:\Qt\Tools\CMake_64\bin;%PATH%"
set "CMAKE=C:\Qt\Tools\CMake_64\bin\cmake.exe"
set "BUILD=%~dp0build-qt612"

if not exist "%CMAKE%" (
    echo CMake not found: %CMAKE%
    goto fail
)
if not exist "C:\Qt\6.12.0\mingw_64\bin\qmake.exe" (
    echo Qt 6.12.0 MinGW kit not found.
    goto fail
)
if not exist "C:\Qt\Tools\mingw1310_64\bin\g++.exe" (
    echo MinGW g++ not found.
    goto fail
)

"%CMAKE%" -G Ninja -S "%~dp0." -B "%BUILD%" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DCMAKE_PREFIX_PATH=C:/Qt/6.12.0/mingw_64 ^
    -DCMAKE_C_COMPILER=C:/Qt/Tools/mingw1310_64/bin/gcc.exe ^
    -DCMAKE_CXX_COMPILER=C:/Qt/Tools/mingw1310_64/bin/g++.exe ^
    -DCMAKE_MAKE_PROGRAM=C:/Qt/Tools/Ninja/ninja.exe ^
    -DFETCHCONTENT_SOURCE_DIR_LIBDATACHANNEL=%~dp0build-p2p/_deps/libdatachannel-src
if errorlevel 1 goto fail

"%CMAKE%" --build "%BUILD%"
if errorlevel 1 goto fail

echo.
echo BUILD SUCCEEDED
echo %BUILD%\RemoteController.exe
pause
exit /b 0

:fail
echo.
echo BUILD FAILED
pause
exit /b 1
