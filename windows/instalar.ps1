# Instalador de TDS-MARL para Windows (lo ejecuta INSTALAR.bat).
#
# Deja todo DENTRO de la carpeta del programa, sin permisos de administrador y
# sin tocar el PATH ni el sistema:
#   runtime\java     Java 21 (Temurin JRE), copiado desde instaladores\
#   runtime\python   Python 3.12 con tkinter, instalado en silencio solo aqui
#   runtime\entorno.cmd   rutas que usan los lanzadores .bat
# Luego verifica que todo funcione, corre las pruebas automaticas y crea los
# accesos directos (escritorio y menu Inicio).
# Se puede volver a ejecutar: lo que ya esta instalado se salta.
# Desinstalar: DESINSTALAR.bat (quita el Python registrado, los accesos directos
# y luego se borra la carpeta).
#
# Notas de compatibilidad (Windows PowerShell 5.1):
# - Los argumentos para otros programas NO llevan comillas dobles dentro: 5.1 las
#   elimina al pasarlos. Por eso el codigo Python de -c usa comillas simples.
# - runtime\entorno.cmd se escribe con la codificacion OEM, la que lee cmd.exe,
#   para que funcionen rutas con tildes (por ejemplo C:\Users\Jose\...).

$ErrorActionPreference = 'Stop'
$Raiz = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Raiz
$Runtime = Join-Path $Raiz 'runtime'
$Log = Join-Path $Raiz 'instalacion.log'
$Resultados = [ordered]@{}

function Escribir([string]$texto, [string]$color = 'Gray') {
    Write-Host $texto -ForegroundColor $color
    Add-Content -LiteralPath $Log -Value ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $texto)
}

function Paso([string]$nombre, [scriptblock]$accion) {
    Escribir ''
    Escribir "=== $nombre ===" 'Cyan'
    try {
        & $accion
        $Resultados[$nombre] = 'OK'
        Escribir "    OK" 'Green'
    } catch {
        $Resultados[$nombre] = 'FALLA: ' + $_.Exception.Message
        Escribir ("    FALLA: " + $_.Exception.Message) 'Red'
    }
}

function Ejecutar([string]$exe, [string[]]$argumentos) {
    # Ejecuta un programa y devuelve @(codigo, salida). No detiene el script si escribe en stderr.
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # Lo que el programa escribe en stderr llega como "ErrorRecord": se pasa a texto simple.
        $salida = & $exe @argumentos 2>&1 | ForEach-Object { "$_" } | Out-String
        return @($LASTEXITCODE, $salida)
    } finally {
        $ErrorActionPreference = $anterior
    }
}

function PythonSirve([string]$py) {
    # True si es Python 3.8 o mas nuevo y abre tkinter.
    if (-not $py -or -not (Test-Path -LiteralPath $py)) { return $false }
    $r = Ejecutar $py @('-c', 'import sys, tkinter; assert sys.version_info >= (3, 8); r = tkinter.Tk(); r.destroy(); print(''ok'')')
    return ($r[0] -eq 0 -and $r[1] -match 'ok')
}

New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
Escribir "Instalando TDS-MARL en: $Raiz" 'White'
Escribir "Registro de la instalacion: $Log"

# Los archivos de un zip descargado vienen "bloqueados" por Windows; se desbloquean
# para que el instalador de Python no muestre advertencias que corten la instalacion silenciosa.
Get-ChildItem -LiteralPath $Raiz -Recurse -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue

# ---------------------------------------------------------------------------
$JavaExe = Join-Path $Runtime 'java\bin\java.exe'
Paso '1. Java (Temurin JRE 21)' {
    if (Test-Path -LiteralPath $JavaExe) {
        Escribir '    Ya estaba instalado.'
    } else {
        $zip = Get-ChildItem -LiteralPath (Join-Path $Raiz 'instaladores') -Filter 'OpenJDK*jre*windows*.zip' | Select-Object -First 1
        if (-not $zip) { throw 'no se encontro el zip de Java en la carpeta instaladores' }
        $tmp = Join-Path $Runtime '_java_tmp'
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
        Escribir "    Descomprimiendo $($zip.Name) ..."
        Expand-Archive -LiteralPath $zip.FullName -DestinationPath $tmp -Force
        $interior = Get-ChildItem -LiteralPath $tmp -Directory | Select-Object -First 1
        $destinoJava = Join-Path $Runtime 'java'
        if (Test-Path -LiteralPath $destinoJava) { Remove-Item -LiteralPath $destinoJava -Recurse -Force }   # restos de un intento anterior
        Move-Item -LiteralPath $interior.FullName -Destination (Join-Path $Runtime 'java')
        Remove-Item -LiteralPath $tmp -Recurse -Force
    }
    $r = Ejecutar $JavaExe @('-version')
    if ($r[0] -ne 0) { throw "java no arranca: $($r[1])" }
    Escribir ('    ' + ($r[1] -split "`n")[0].Trim())
}

# ---------------------------------------------------------------------------
$PyDir = Join-Path $Runtime 'python'
$PyExe = Join-Path $PyDir 'python.exe'
$PyRutaExterna = Join-Path $Runtime 'python_ruta.txt'
Paso '2. Python 3.12 con tkinter' {
    if (PythonSirve $PyExe) {
        Escribir '    Ya estaba instalado.'
        return
    }
    $instalador = Get-ChildItem -LiteralPath (Join-Path $Raiz 'instaladores') -Filter 'python-*-amd64.exe' | Select-Object -First 1
    if (-not $instalador) { throw 'no se encontro el instalador de Python en la carpeta instaladores' }
    Escribir "    Instalando $($instalador.Name) en runtime\python (silencioso, puede tardar 1-3 minutos) ..."
    $opciones = "/quiet InstallAllUsers=0 TargetDir=`"$PyDir`" PrependPath=0 Include_launcher=0 " +
                "Include_test=0 Include_doc=0 Include_dev=0 Include_tcltk=1 Include_pip=1 " +
                "Shortcuts=0 AssociateFiles=0 /log `"$Runtime\python_instalacion.log`""
    $p = Start-Process -FilePath $instalador.FullName -ArgumentList $opciones -Wait -PassThru
    Escribir "    El instalador termino con codigo $($p.ExitCode)."
    if (PythonSirve $PyExe) {
        if (Test-Path -LiteralPath $PyRutaExterna) { Remove-Item -LiteralPath $PyRutaExterna -Force }
        return
    }
    # Si esta misma version de Python ya estaba instalada en el equipo, el instalador la
    # "repara" en vez de instalar aqui. Se busca entonces un Python existente con tkinter.
    Escribir '    No quedo en runtime\python; se busca un Python ya instalado con tkinter ...' 'Yellow'
    $candidatos = @()
    foreach ($base in 'HKCU:\Software\Python\PythonCore', 'HKLM:\Software\Python\PythonCore', 'HKLM:\Software\WOW6432Node\Python\PythonCore') {
        if (Test-Path $base) {
            foreach ($version in Get-ChildItem $base) {
                try {
                    $ip = Join-Path $version.PSPath 'InstallPath'
                    if (Test-Path $ip) {
                        $exe = (Get-ItemProperty $ip).ExecutablePath
                        if (-not $exe) { $exe = Join-Path (Get-ItemProperty $ip).'(default)' 'python.exe' }
                        $candidatos += $exe
                    }
                } catch { }   # una entrada rota del registro no corta la busqueda
            }
        }
    }
    $py = Get-Command 'py.exe' -ErrorAction SilentlyContinue
    if ($py) {
        $r = Ejecutar $py.Source @('-3', '-c', 'import sys; print(sys.executable)')
        if ($r[0] -eq 0) { $candidatos += $r[1].Trim() }
    }
    foreach ($c in ($candidatos | Select-Object -Unique)) {
        if (PythonSirve $c) {
            Set-Content -LiteralPath $PyRutaExterna -Value $c -Encoding UTF8
            Escribir "    Se usara el Python ya instalado: $c"
            return
        }
    }
    throw ('no se pudo instalar Python. Instalalo desde https://www.python.org/downloads/ ' +
           '(marca "tcl/tk and IDLE") y vuelve a ejecutar INSTALAR.bat. Detalle en runtime\python_instalacion.log')
}

# ---------------------------------------------------------------------------
Paso '3. Rutas para los lanzadores' {
    if (Test-Path -LiteralPath $PyExe) { $python = $PyExe }
    elseif (Test-Path -LiteralPath $PyRutaExterna) { $python = (Get-Content -LiteralPath $PyRutaExterna -Raw -Encoding UTF8).Trim() }
    else { throw 'Python no quedo instalado (ver el paso 2)' }
    if (-not (Test-Path -LiteralPath $JavaExe)) { throw 'Java no quedo instalado (ver el paso 1)' }
    $pythonw = Join-Path (Split-Path -Parent $python) 'pythonw.exe'
    if (-not (Test-Path -LiteralPath $pythonw)) { $pythonw = $python }
    $lineas = @(
        '@rem Generado por windows\instalar.ps1. No editar: se vuelve a crear al instalar.',
        "set `"TDS_JAVA=$JavaExe`"",
        "set `"TDS_PY=$python`"",
        "set `"TDS_PYW=$pythonw`"",
        'set "PYTHONUTF8=1"',
        'set "PYTHONIOENCODING=utf-8"'
    )
    Set-Content -LiteralPath (Join-Path $Runtime 'entorno.cmd') -Value $lineas -Encoding Oem
    New-Item -ItemType Directory -Force -Path (Join-Path $Raiz 'corridas') | Out-Null
    $script:Python = $python
    $script:PythonW = $pythonw
    Escribir "    Java:   $JavaExe"
    Escribir "    Python: $python"
}

# ---------------------------------------------------------------------------
Paso '4. Verificacion rapida' {
    $cp = "tds-marl.jar;lib\jade.jar;lib\jgrapht-core-1.0.1.jar"
    # Un parametro invalido: la corrida debe negarse a arrancar (codigo 2) sin levantar JADE.
    $r = Ejecutar $JavaExe @('-Dfile.encoding=UTF-8', '-Dtds.sinDialogo=true', '-Dtds.deadlineMs=abc', '-cp', $cp, 'MainContainer')
    if ($r[0] -ne 2 -or $r[1] -notmatch 'DEADLINE_MS') { throw "el programa Java no responde como se espera: $($r[1])" }
    Escribir '    El programa Java arranca y valida sus parametros.'
    $r = Ejecutar $script:Python @('correr_lote.py', 'experimentos_ejemplo.json', '--simular')
    if ($r[0] -ne 0 -or $r[1] -notmatch 'Total: 30 corridas') { throw "correr_lote.py --simular fallo: $($r[1])" }
    Escribir '    El lanzador de lotes funciona (--simular).'
}

# ---------------------------------------------------------------------------
Paso '5. Pruebas automaticas (1-2 minutos)' {
    $env:PYTHONUTF8 = '1'
    $env:PYTHONIOENCODING = 'utf-8'
    $r = Ejecutar $script:Python @('pruebas\correr_pruebas.py')
    Add-Content -LiteralPath $Log -Value $r[1]
    $resumen = ($r[1] -split "`n" | Where-Object { $_ -match '^Ran |^OK|FAILED|TODAS|HAY PRUEBAS' }) -join ' | '
    Escribir "    $resumen"
    if ($r[0] -ne 0) { throw 'hay pruebas que fallaron (detalle en instalacion.log)' }
}

# ---------------------------------------------------------------------------
Paso '6. Accesos directos' {
    $shell = New-Object -ComObject WScript.Shell
    function Acceso([string]$ruta, [string]$destino, [string]$argumentos, [string]$icono, [string]$descripcion) {
        $a = $shell.CreateShortcut($ruta)
        $a.TargetPath = $destino
        $a.Arguments = $argumentos
        $a.WorkingDirectory = $Raiz
        $a.Description = $descripcion
        if ($icono) { $a.IconLocation = $icono }
        $a.Save()
    }
    $menu = Join-Path ([Environment]::GetFolderPath('Programs')) 'TDS-MARL'
    New-Item -ItemType Directory -Force -Path $menu | Out-Null
    $escritorio = [Environment]::GetFolderPath('Desktop')
    $iconoPy = "$script:PythonW,0"
    $lotes = "`"$Raiz\interfaz_lotes.py`""
    Acceso (Join-Path $escritorio 'TDS-MARL - Lotes.lnk') $script:PythonW $lotes $iconoPy 'Armar y lanzar lotes de corridas'
    Acceso (Join-Path $escritorio 'TDS-MARL - Resultados.lnk') "$env:WINDIR\explorer.exe" "`"$Raiz\corridas`"" '' 'Carpeta de resultados'
    Acceso (Join-Path $menu 'TDS-MARL - Lotes.lnk') $script:PythonW $lotes $iconoPy 'Armar y lanzar lotes de corridas'
    Acceso (Join-Path $menu 'TDS-MARL - Resultados.lnk') "$env:WINDIR\explorer.exe" "`"$Raiz\corridas`"" '' 'Carpeta de resultados'
    Acceso (Join-Path $menu 'TDS-MARL - Una corrida.lnk') (Join-Path $Raiz 'Correr una corrida.bat') '' '' 'Una corrida a mano'
    Acceso (Join-Path $menu 'TDS-MARL - Pruebas.lnk') (Join-Path $Raiz 'Correr pruebas.bat') '' '' 'Pruebas automaticas'
    Acceso (Join-Path $menu 'TDS-MARL - Leeme.lnk') "$env:WINDIR\notepad.exe" "`"$Raiz\LEEME.txt`"" '' 'Instrucciones'
    Escribir "    Escritorio: TDS-MARL - Lotes, TDS-MARL - Resultados"
    Escribir "    Menu Inicio: carpeta TDS-MARL"
    Acceso (Join-Path $menu 'TDS-MARL - Desinstalar.lnk') (Join-Path $Raiz 'DESINSTALAR.bat') '' '' 'Desinstalar TDS-MARL'
}

# ---------------------------------------------------------------------------
Escribir ''
Escribir '=== Resumen ===' 'Cyan'
foreach ($k in $Resultados.Keys) {
    $color = 'Green'
    if ($Resultados[$k] -ne 'OK') { $color = 'Red' }
    Escribir ("  {0,-40} {1}" -f $k, $Resultados[$k]) $color
}
$fallas = @($Resultados.Values | Where-Object { $_ -ne 'OK' }).Count
if ($fallas -gt 0) {
    Escribir ''
    Escribir 'La instalacion NO termino bien. Revisa los mensajes en rojo y el archivo instalacion.log,' 'Red'
    Escribir 'corrige lo indicado y vuelve a ejecutar INSTALAR.bat (lo que ya quedo instalado se salta).' 'Red'
    exit 1
}

Escribir ''
Escribir 'Instalacion completa.' 'Green'
$respuesta = Read-Host 'Hacer ahora una corrida de prueba corta (unos 2 minutos)? [S/n]'
if ($respuesta -eq '' -or $respuesta -match '^[sSyY]') {
    Escribir ''
    Escribir 'Corrida de prueba: escenario pequeno, tope de 1 minuto (quedara NO VALIDA a proposito).' 'Cyan'
    Escribir 'Si Windows pregunta por el firewall para "OpenJDK Platform binary", acepta en redes privadas:' 'Yellow'
    Escribir 'el sistema usa el puerto local 1099 para comunicar a sus agentes.' 'Yellow'
    $etiqueta = 'prueba_instalacion_' + (Get-Date -Format 'yyyyMMdd_HHmmss')
    $json = Join-Path $Runtime 'prueba_instalacion.json'
    $contenido = '{"tiempo_maximo_por_corrida_min": 10, "tope_negociacion_min": 1, "configuraciones": [' +
                 '{"nombre": "' + $etiqueta + '", "escenario": "pequeno", "repeticiones": 1}]}'
    Set-Content -LiteralPath $json -Value $contenido -Encoding ASCII
    Escribir 'Corriendo (unos 2 minutos, la ventana de la mina no se muestra) ...'
    $r = Ejecutar $script:Python @('correr_lote.py', $json)
    Write-Host $r[1]
    Add-Content -LiteralPath $Log -Value $r[1]
    $resumenCorrida = Join-Path $Raiz ("corridas\{0}_r01\resumen_plan.txt" -f $etiqueta)
    if (Test-Path -LiteralPath $resumenCorrida) {
        Escribir "Corrida de prueba OK: resultados en corridas\${etiqueta}_r01" 'Green'
    } else {
        Escribir 'La corrida de prueba no dejo resultados; revisa instalacion.log.' 'Red'
        exit 1
    }
}
Escribir ''
Escribir 'Listo. Abre "TDS-MARL - Lotes" desde el escritorio para empezar. Instrucciones: LEEME.txt' 'Green'
exit 0
