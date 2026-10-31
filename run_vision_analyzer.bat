@echo off
setlocal enabledelayedexpansion
title CIFI AI Vision Dossier Analyzer
cd /d "%~dp0"

echo ==========================================================
echo    CIFI AI VISION DOSSIER ANALYZER (PP-OCRv4 ONNX)
echo ==========================================================
echo.

set "PY_CMD="

REM 1. Check for python or py
where python >nul 2>&1
if %ERRORLEVEL% equ 0 (
    python --version >nul 2>&1
    if !ERRORLEVEL! equ 0 (
        set "PY_CMD=python"
        goto :python_found
    )
)

where py >nul 2>&1
if %ERRORLEVEL% equ 0 (
    py --version >nul 2>&1
    if !ERRORLEVEL! equ 0 (
        set "PY_CMD=py"
        goto :python_found
    )
)

REM 2. Python not found - auto install
echo [!] Python was not detected on this system.
echo [*] Auto-installing Python for Windows...

where winget >nul 2>&1
if %ERRORLEVEL% equ 0 goto :install_winget

:install_web
echo [*] Downloading Python official installer from python.org...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$wc = New-Object System.Net.WebClient; $installer = Join-Path $env:TEMP 'python_installer.exe'; $wc.DownloadFile('https://www.python.org/ftp/python/3.11.9/python-3.11.9-amd64.exe', $installer); Start-Process -FilePath $installer -ArgumentList '/quiet InstallAllUsers=0 PrependPath=1 Include_test=0' -Wait; Remove-Item $installer -Force -ErrorAction SilentlyContinue"
goto :refresh_path

:install_winget
echo [*] Installing Python via Windows Package Manager...
winget install --id Python.Python.3.11 -e --silent --accept-package-agreements --accept-source-agreements
goto :refresh_path

:refresh_path
for /f "tokens=2*" %%A in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "PATH=%%B;!PATH!"
for /f "tokens=2*" %%A in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "PATH=%%B;!PATH!"

where python >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "PY_CMD=python"
    goto :python_found
)

where py >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "PY_CMD=py"
    goto :python_found
)

echo [!] Python installed but not detected in current shell.
echo [*] Please close and reopen run_vision_analyzer.bat.
pause
exit /b 1

:python_found
echo [OK] Python detected:
!PY_CMD! --version
echo.

echo [*] Checking AI vision dependencies...
!PY_CMD! -c "import cv2, rapidocr_onnxruntime" >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo [!] Installing required dependencies: rapidocr-onnxruntime and opencv-python...
    !PY_CMD! -m pip install --upgrade pip
    !PY_CMD! -m pip install rapidocr-onnxruntime opencv-python
    if !ERRORLEVEL! neq 0 (
        echo [x] Dependency installation failed. Please check your internet connection.
        pause
        exit /b 1
    )
)
echo [OK] All dependencies satisfied.
echo.

echo [*] Starting CIFI AI Vision Server on http://localhost:8765 ...
start "" http://localhost:8765
!PY_CMD! cifi_vision_service.py
pause
