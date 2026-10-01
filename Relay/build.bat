@echo off
setlocal
set PATH=C:\msys64\mingw64\bin;%PATH%
g++ -O2 -std=c++17 -static -o rc-relay.exe relay.cpp -lws2_32 -ladvapi32
if errorlevel 1 exit /b 1
echo Built rc-relay.exe
dir rc-relay.exe
