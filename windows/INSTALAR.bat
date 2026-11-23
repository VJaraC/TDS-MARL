@echo off
rem Instala todo lo necesario para TDS-MARL dentro de esta carpeta (Java, Python con
rem tkinter, accesos directos). No necesita permisos de administrador.
title Instalacion de TDS-MARL
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\instalar.ps1"
echo.
pause
