@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Get-AyuVahak.ps1" %*
if errorlevel 1 pause
