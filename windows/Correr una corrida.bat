@echo off
rem Una corrida a mano, con la ventana de la mina y el dialogo final (como siempre).
title TDS-MARL - una corrida
cd /d "%~dp0"
if not exist "runtime\entorno.cmd" (
  echo Primero hay que instalar: haz doble clic en INSTALAR.bat
  pause
  exit /b 1
)
call "runtime\entorno.cmd"
echo Corrida a mano de TDS-MARL. Deja en blanco para usar el valor indicado.
echo.
set "ETIQUETA="
set /p "ETIQUETA=Etiqueta (nombre unico de la corrida, sin espacios): "
if "%ETIQUETA%"=="" (
  echo La etiqueta es obligatoria.
  pause
  exit /b 1
)
set "ESCENARIO=pequeno"
set /p "ESCENARIO=Escenario: pequeno, mediano o grande [pequeno]: "
set "DEADLINE="
set /p "DEADLINE=DEADLINE_MS en milisegundos [5000]: "
set "TOPE="
set /p "TOPE=Tope de la negociacion en minutos, o null = sin tope [90]: "
set "OPCIONES=-Dtds.etiqueta=%ETIQUETA% -Dtds.escenario=%ESCENARIO%"
if not "%DEADLINE%"=="" set "OPCIONES=%OPCIONES% -Dtds.deadlineMs=%DEADLINE%"
if not "%TOPE%"=="" set "OPCIONES=%OPCIONES% -Dtds.topeNegociacionMin=%TOPE%"
echo.
echo Corriendo... (al terminar aparece un resumen; los resultados quedan en corridas\%ETIQUETA%)
"%TDS_JAVA%" -Dfile.encoding=UTF-8 %OPCIONES% -cp "tds-marl.jar;lib\jade.jar;lib\jgrapht-core-1.0.1.jar" MainContainer
echo.
pause
