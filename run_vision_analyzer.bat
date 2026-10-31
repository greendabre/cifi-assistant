@echo off
title CIFI AI Vision Dossier Analyzer
cd /d "%~dp0"

echo ==========================================================
echo    CIFI AI VISION DOSSIER ANALYZER (PP-OCRv4 ONNX)
echo ==========================================================
echo [*] Checking dependencies...
python -c "import cv2, rapidocr_onnxruntime" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [!] Installing rapidocr-onnxruntime and opencv-python...
    pip install rapidocr-onnxruntime opencv-python
)

echo [*] Starting Local Deep Learning AI Vision Server...
start "" http://localhost:8765
python cifi_vision_service.py
pause
