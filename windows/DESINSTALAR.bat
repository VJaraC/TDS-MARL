@echo off
rem Quita Java, Python y los accesos directos instalados por INSTALAR.bat.
rem Los resultados (carpeta corridas) no se borran.
title Desinstalar TDS-MARL
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\desinstalar.ps1"
echo.
pause
