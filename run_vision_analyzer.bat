@echo off
setlocal enabledelayedexpansion
title CIFI AI Vision Dossier Analyzer
cd /d "%~dp0"

echo ==========================================================
echo    CIFI AI VISION DOSSIER ANALYZER (PP-OCRv4 ONNX)
echo ==========================================================
echo.

:: 1. Check for Python command
set "PY_CMD="
where python >nul 2>&1
if %ERRORLEVEL% equ 0 (
    python --version >nul 2>&1
    if !ERRORLEVEL! equ 0 set "PY_CMD=python"
)
if not defined PY_CMD (
    where py >nul 2>&1
    if %ERRORLEVEL% equ 0 (
        py --version >nul 2>&1
        if !ERRORLEVEL! equ 0 set "PY_CMD=py"
    )
)

:: 2. Auto-install Python if not found
if not defined PY_CMD (
    echo [!] Python was not detected on this system.
    echo [*] Auto-installing Python for Windows...
    
    where winget >nul 2>&1
    if !ERRORLEVEL% equ 0 (
        echo [*] Installing Python via Windows Package Manager (winget)...
        winget install --id Python.Python.3.11 -e --silent --accept-package-agreements --accept-source-agreements
    ) else (
        echo [*] Downloading Python 3.11 official installer from python.org...
        powershell -NoProfile -ExecutionPolicy Bypass -Command "$wc = New-Object System.Net.WebClient; $installer = Join-Path $env:TEMP 'python_installer.exe'; Write-Host 'Downloading installer...'; $wc.DownloadFile('https://www.python.org/ftp/python/3.11.9/python-3.11.9-amd64.exe', $installer); Write-Host 'Installing silently (adding Python to PATH)...'; Start-Process -FilePath $installer -ArgumentList '/quiet InstallAllUsers=0 PrependPath=1 Include_test=0' -Wait; Remove-Item $installer -Force -ErrorAction SilentlyContinue"
    )

    :: Refresh PATH in current session
    for /f "tokens=2*" %%A in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "PATH=%%B;!PATH!"
    for /f "tokens=2*" %%A in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "PATH=%%B;!PATH!"

    where python >nul 2>&1
    if !ERRORLEVEL% equ 0 (
        set "PY_CMD=python"
    ) else (
        where py >nul 2>&1
        if !ERRORLEVEL! equ 0 (
            set "PY_CMD=py"
        ) else (
            echo [!] Python installation finished. Please restart this window to refresh PATH.
            pause
            exit /b 1
        )
    )
    echo [OK] Python installed successfully:
    !PY_CMD! --version
) else (
    echo [OK] Python detected:
    !PY_CMD! --version
)

echo.
echo [*] Checking required AI Vision dependencies...
!PY_CMD! -c "import cv2, rapidocr_onnxruntime" >nul 2>&1
if !ERRORLEVEL% neq 0 (
    echo [!] Missing dependencies. Installing rapidocr-onnxruntime and opencv-python...
    !PY_CMD! -m pip install --upgrade pip
    !PY_CMD! -m pip install rapidocr-onnxruntime opencv-python
    if !ERRORLEVEL% neq 0 (
        echo [x] Failed to install dependencies. Please check your internet connection.
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
