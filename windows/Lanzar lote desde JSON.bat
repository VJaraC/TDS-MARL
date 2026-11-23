@echo off
rem Lanza un lote en esta consola. Arrastra un archivo .json sobre este .bat,
rem o haz doble clic para usar experimentos_ejemplo.json.
rem Para retomar un lote cortado, vuelve a lanzar el mismo archivo.
title TDS-MARL - lote
cd /d "%~dp0"
if not exist "runtime\entorno.cmd" (
  echo Primero hay que instalar: haz doble clic en INSTALAR.bat
  pause
  exit /b 1
)
call "runtime\entorno.cmd"
set "ARCHIVO=%~1"
if "%ARCHIVO%"=="" set "ARCHIVO=%~dp0experimentos_ejemplo.json"
echo Archivo de experimentos: %ARCHIVO%
echo.
"%TDS_PY%" correr_lote.py "%ARCHIVO%" --simular
echo.
choice /C SN /M "Lanzar este lote ahora"
if errorlevel 2 exit /b 0
"%TDS_PY%" correr_lote.py "%ARCHIVO%"
echo.
pause
