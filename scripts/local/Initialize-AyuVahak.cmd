@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Initialize-AyuVahak.ps1" %*
exit /b %ERRORLEVEL%
