# Desinstalador de TDS-MARL para Windows (lo ejecuta DESINSTALAR.bat).
# 1. Quita los accesos directos (escritorio y menu Inicio).
# 2. Si INSTALAR.bat instalo Python dentro de runtime\python, lo desinstala con su
#    propio instalador (asi no queda en "Aplicaciones" de Windows). Un Python que ya
#    estaba en el equipo antes NO se toca.
# 3. Borra runtime\ (Java y Python de este programa).
# La carpeta "corridas" (resultados) NO se borra: al final se indica borrar la carpeta a mano.

$ErrorActionPreference = 'Stop'
$Raiz = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Raiz
$Runtime = Join-Path $Raiz 'runtime'

Write-Host 'Desinstalar TDS-MARL de:' $Raiz -ForegroundColor White
$r = Read-Host 'Continuar? [s/N]'
if ($r -notmatch '^[sSyY]') { Write-Host 'Cancelado.'; exit 0 }

$escritorio = [Environment]::GetFolderPath('Desktop')
foreach ($n in 'TDS-MARL - Lotes.lnk', 'TDS-MARL - Resultados.lnk') {
    $f = Join-Path $escritorio $n
    if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force; Write-Host "  quitado $f" }
}
$menu = Join-Path ([Environment]::GetFolderPath('Programs')) 'TDS-MARL'
if (Test-Path -LiteralPath $menu) { Remove-Item -LiteralPath $menu -Recurse -Force; Write-Host "  quitado $menu" }

$pyPropio = Join-Path $Runtime 'python\python.exe'
if (Test-Path -LiteralPath $pyPropio) {
    $instalador = Get-ChildItem -LiteralPath (Join-Path $Raiz 'instaladores') -Filter 'python-*-amd64.exe' | Select-Object -First 1
    if ($instalador) {
        Write-Host '  desinstalando el Python de este programa (puede tardar un minuto) ...'
        $p = Start-Process -FilePath $instalador.FullName -ArgumentList '/quiet /uninstall' -Wait -PassThru
        Write-Host "  el desinstalador de Python termino con codigo $($p.ExitCode)"
    }
}
if (Test-Path -LiteralPath $Runtime) {
    Remove-Item -LiteralPath $Runtime -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "  borrado $Runtime"
}
Write-Host ''
Write-Host 'Listo. Si quieres conservar los resultados, copia la carpeta "corridas".' -ForegroundColor Green
Write-Host "Despues puedes borrar la carpeta completa: $Raiz" -ForegroundColor Green
exit 0
