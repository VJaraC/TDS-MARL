@echo off
rem Corre las pruebas automaticas (1-2 minutos). No levanta el sistema multiagente.
title TDS-MARL - pruebas
cd /d "%~dp0"
if not exist "runtime\entorno.cmd" (
  echo Primero hay que instalar: haz doble clic en INSTALAR.bat
  pause
  exit /b 1
)
call "runtime\entorno.cmd"
"%TDS_PY%" pruebas\correr_pruebas.py
echo.
pause
