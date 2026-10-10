@echo off
REM Native Windows ARM64 build (Snapdragon X): VS Build Tools' ARM64 LLVM clang, MSVC ABI.
REM Links with MSVC link.exe: the lld-link bundled with VS lacks libxml2 and writes a manifest
REM without requestedExecutionLevel/@level, which Windows refuses to start (side-by-side error).
REM Usage: build-windows-arm64.cmd [GEN_DIR] [BUILD_DIR]
setlocal
cd /d "%~dp0"

set "VSROOT=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"
call "%VSROOT%\VC\Auxiliary\Build\vcvarsarm64.bat" >nul
if errorlevel 1 exit /b 1
set "PATH=%VSROOT%\VC\Tools\Llvm\ARM64\bin;%VSROOT%\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin;%VSROOT%\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja;%PATH%"
if not defined VULKAN_SDK set "VULKAN_SDK=C:\VulkanSDK"

set "GEN=%~1"
if "%GEN%"=="" set "GEN=%CD%\build\gen"
set "OUT=%~2"
if "%OUT%"=="" set "OUT=build\windows-arm64"

cmake -S . -B "%OUT%" -G Ninja -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_BUILD_TYPE=Release -DCMAKE_LINKER_TYPE=MSVC "-DGEN_DIR=%GEN%"
if errorlevel 1 exit /b 1
cmake --build "%OUT%"
exit /b %errorlevel%
