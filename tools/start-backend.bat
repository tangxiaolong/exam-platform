@echo off
rem Start exam-platform backend (UTF-8 console, no garbled log output)
rem Usage: tools\start-backend.bat [port]
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-backend.ps1" %*
pause
