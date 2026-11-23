@echo off
rem Abre la ventana para armar y lanzar lotes de corridas.
cd /d "%~dp0"
if not exist "runtime\entorno.cmd" (
  echo Primero hay que instalar: haz doble clic en INSTALAR.bat
  pause
  exit /b 1
)
call "runtime\entorno.cmd"
start "" "%TDS_PYW%" "%~dp0interfaz_lotes.py"
