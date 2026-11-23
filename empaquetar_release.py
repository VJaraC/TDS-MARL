#!/usr/bin/env python3
"""Arma el release de TDS-MARL para Windows: dist/TDS-MARL-<version>-windows.zip

Se ejecuta en el equipo de desarrollo (Linux o Windows con JDK y Python):
  1. corre las pruebas automaticas (se puede omitir con --sin-pruebas);
  2. compila src/ y arma tds-marl.jar (con Main-Class y Class-Path) y el jar
     del arnes de pruebas;
  3. descarga (una vez; quedan en ~/.cache/tds-marl-release) el Java portatil
     para Windows (Temurin JRE 21) y el instalador oficial de Python 3.12, y
     VERIFICA sus sumas de control publicadas; tambien los textos de licencia;
  4. junta todo en una carpeta y la comprime.

El usuario final solo descomprime el zip y ejecuta INSTALAR.bat (ver
windows/instalar.ps1 y windows/LEEME.txt). No modifica ningun archivo del repo.

Uso:  python3 empaquetar_release.py [--version v1.0.0] [--sin-pruebas]
"""
import argparse
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent
sys.path.insert(0, str(REPO))
import correr_lote  # noqa: E402

CACHE = Path.home() / ".cache" / "tds-marl-release"
DIST = REPO / "dist"
API_JRE = ("https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre"
           "&os=windows&vendor=eclipse")
PYTHON = {  # ultimo instalador binario oficial de la rama 3.12 (trae tkinter)
    "url": "https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe",
    "md5": "5eddb0b6f12c852725de071ae681dde4",          # publicado por python.org (API de descargas)
    "bytes": 26964224,
}
LICENCIAS = {
    "LGPL-2.1.txt": "https://raw.githubusercontent.com/spdx/license-list-data/main/text/LGPL-2.1-only.txt",
    "EPL-1.0.txt": "https://raw.githubusercontent.com/spdx/license-list-data/main/text/EPL-1.0.txt",
}
ESCENARIOS = ["datosShovelTrucksPequeno.owl", "datosShovelTrucksMediano.owl",
              "datosShovelTrucksGrande_original.owl", "points_20032019.owl"]
LANZADORES = ["INSTALAR.bat", "DESINSTALAR.bat", "TDS-MARL Lotes.bat", "Lanzar lote desde JSON.bat",
              "Correr una corrida.bat", "Correr pruebas.bat"]
DOCS = ["README.md", "COMO_CORRER.md", "DOCUMENTACION_REGISTROS.md", "HISTORIAL_TRABAJO_CIERRE_SCHEDULE.md"]
PRUEBAS = ["__init__.py", "correr_pruebas.py", "test_estadisticas.py", "test_correr_lote.py", "test_interfaz.py"]


def paso(texto):
    print("\n== " + texto, flush=True)


def digest(ruta, algoritmo):
    h = hashlib.new(algoritmo)
    with open(ruta, "rb") as f:
        for bloque in iter(lambda: f.read(1 << 20), b""):
            h.update(bloque)
    return h.hexdigest()


def descargar(url, destino):
    if destino.is_file():
        print("   (en cache) %s" % destino.name)
        return destino
    print("   descargando %s ..." % url, flush=True)
    tmp = destino.with_suffix(destino.suffix + ".parcial")
    req = urllib.request.Request(url, headers={"User-Agent": "tds-marl-empaquetador"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, destino)
    return destino


def obtener_jre():
    req = urllib.request.Request(API_JRE, headers={"User-Agent": "tds-marl-empaquetador"})
    with urllib.request.urlopen(req, timeout=60) as r:
        a = json.load(r)[0]
    p = a["binary"]["package"]
    zipjre = descargar(p["link"], CACHE / p["name"])
    if digest(zipjre, "sha256") != p["checksum"]:
        zipjre.unlink()
        sys.exit("ERROR: el Java descargado no coincide con la suma SHA-256 publicada; se borro, reintenta.")
    print("   verificado SHA-256 (Adoptium): %s" % p["checksum"])
    return zipjre, a["version"]["openjdk_version"]


def obtener_python():
    exe = descargar(PYTHON["url"], CACHE / PYTHON["url"].rsplit("/", 1)[1])
    if exe.stat().st_size != PYTHON["bytes"] or digest(exe, "md5") != PYTHON["md5"]:
        exe.unlink()
        sys.exit("ERROR: el instalador de Python no coincide con la suma publicada; se borro, reintenta.")
    print("   verificado MD5 y tamano (python.org): %s" % PYTHON["md5"])
    return exe


def texto_windows(origen, destino, bom=False, reemplazos=None):
    """Copia un texto con fin de linea CRLF (y BOM UTF-8 si se pide), como lo necesita Windows."""
    t = Path(origen).read_text(encoding="utf-8").replace("\r\n", "\n")
    for a, b in (reemplazos or {}).items():
        t = t.replace(a, b)
    datos = t.replace("\n", "\r\n").encode("utf-8")
    Path(destino).write_bytes((b"\xef\xbb\xbf" if bom else b"") + datos)


def armar_jar(clases, destino, manifiesto, incluir=lambda p: True):
    with zipfile.ZipFile(destino, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", manifiesto)
        for p in sorted(Path(clases).rglob("*.class")):
            if incluir(p):
                z.write(p, p.relative_to(clases).as_posix())


def compilar(tmp):
    libs = [str(REPO / "lib" / "jade.jar"), str(correr_lote.JGRAPHT)]
    clases = tmp / "clases"
    arnes = tmp / "arnes"
    clases.mkdir()
    arnes.mkdir()
    r = subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-nowarn", "-d", str(clases),
                        "-cp", os.pathsep.join(libs)] + sorted(glob.glob(str(REPO / "src" / "*.java"))),
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("ERROR: no compila:\n" + r.stdout + r.stderr)
    r = subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-nowarn", "-d", str(arnes),
                        "-cp", os.pathsep.join([str(clases)] + libs),
                        str(REPO / "pruebas" / "java" / "ArnesEstadisticas.java")], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("ERROR: no compila el arnes:\n" + r.stdout + r.stderr)
    return clases, arnes


def git(*args):
    r = subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else ""


def main():
    ap = argparse.ArgumentParser(description="Arma el zip de TDS-MARL para Windows.")
    ap.add_argument("--version", default="v1.0.0")
    ap.add_argument("--sin-pruebas", action="store_true", help="no correr las pruebas antes de empaquetar")
    a = ap.parse_args()
    nombre = "TDS-MARL-%s-windows" % a.version
    CACHE.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)

    if not a.sin_pruebas:
        paso("1. Pruebas automaticas y verificacion del codigo original")
        if subprocess.run([sys.executable, str(REPO / "pruebas" / "correr_pruebas.py")]).returncode != 0:
            sys.exit("ERROR: hay pruebas que fallan; no se empaqueta.")

    paso("2. Compilacion (javac --release 8) y jars")
    tmp = Path(tempfile.mkdtemp(prefix="tds_release_"))
    try:
        clases, arnes = compilar(tmp)
        destino = DIST / nombre
        if destino.exists():
            shutil.rmtree(destino)
        destino.mkdir()
        manifiesto = ("Manifest-Version: 1.0\r\nMain-Class: MainContainer\r\n"
                      "Class-Path: lib/jade.jar lib/jgrapht-core-1.0.1.jar\r\n"
                      "Implementation-Title: TDS-MARL\r\nImplementation-Version: %s\r\n\r\n" % a.version)
        armar_jar(clases, destino / "tds-marl.jar", manifiesto)
        (destino / "pruebas" / "java").mkdir(parents=True)
        armar_jar(arnes, destino / "pruebas" / "java" / "arnes.jar", "Manifest-Version: 1.0\r\n\r\n")
        print("   tds-marl.jar: %d clases" % len(list(clases.rglob("*.class"))))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    paso("3. Java y Python para Windows (con verificacion de sumas)")
    zipjre, version_java = obtener_jre()
    exe_python = obtener_python()
    licencias = {n: descargar(u, CACHE / n) for n, u in LICENCIAS.items()}

    paso("4. Armado de la carpeta")
    (destino / "lib").mkdir()
    shutil.copy2(REPO / "lib" / "jade.jar", destino / "lib")
    shutil.copy2(correr_lote.JGRAPHT, destino / "lib" / "jgrapht-core-1.0.1.jar")
    for f in ESCENARIOS + ["correr_lote.py", "interfaz_lotes.py", "experimentos_ejemplo.json"]:
        shutil.copy2(REPO / f, destino / f)
    shutil.copytree(REPO / "planes_iniciales", destino / "planes_iniciales")
    for f in PRUEBAS:
        shutil.copy2(REPO / "pruebas" / f, destino / "pruebas" / f)
    (destino / "docs").mkdir()
    for f in DOCS:
        shutil.copy2(REPO / f, destino / "docs" / f)
    (destino / "windows").mkdir()
    texto_windows(REPO / "windows" / "instalar.ps1", destino / "windows" / "instalar.ps1", bom=True)
    texto_windows(REPO / "windows" / "desinstalar.ps1", destino / "windows" / "desinstalar.ps1", bom=True)
    for f in LANZADORES:
        texto_windows(REPO / "windows" / f, destino / f)
    texto_windows(REPO / "windows" / "LEEME.txt", destino / "LEEME.txt", bom=True, reemplazos={"{VERSION}": a.version})
    (destino / "instaladores").mkdir()
    shutil.copy2(zipjre, destino / "instaladores" / zipjre.name)
    shutil.copy2(exe_python, destino / "instaladores" / exe_python.name)
    (destino / "corridas").mkdir()
    (destino / "corridas" / "LEEME.txt").write_bytes("Aqui quedan los resultados de las corridas.\r\n".encode())
    lic = destino / "licencias"
    lic.mkdir()
    for n, p in licencias.items():
        shutil.copy2(p, lic / n)
    aviso = [
        "TDS-MARL %s - componentes de terceros incluidos" % a.version, "",
        "JADE 4.6.0 (lib/jade.jar) - LGPL 2.1 - https://jade.tilab.com - ver LGPL-2.1.txt",
        "JGraphT 1.0.1 (lib/jgrapht-core-1.0.1.jar) - EPL 1.0 / LGPL 2.1 - https://jgrapht.org - ver EPL-1.0.txt y LGPL-2.1.txt",
        "Eclipse Temurin JRE %s (instaladores/%s) - GPLv2 con Classpath Exception - https://adoptium.net"
        " - la licencia completa viene dentro del zip (carpeta legal/) y queda en runtime/java/legal" % (version_java, zipjre.name),
        "Python 3.12.10 (instaladores/%s) - Python Software Foundation License - https://www.python.org"
        " - la licencia queda en runtime/python/LICENSE.txt al instalar" % exe_python.name,
    ]
    (lic / "AVISO.txt").write_bytes("\r\n".join(aviso).encode("utf-8") + b"\r\n")

    commit = git("rev-parse", "HEAD")
    sucio = bool(git("status", "--porcelain"))
    lineas = ["TDS-MARL %s para Windows" % a.version,
              "Armado: %s" % datetime.now().strftime("%Y-%m-%d %H:%M"),
              "Commit de origen: %s%s" % (commit or "desconocido", " (con cambios sin commit)" if sucio else ""),
              "Java incluido: Temurin JRE %s" % version_java, "Python incluido: 3.12.10", "",
              "SHA-256 de los archivos principales:"]
    for p in [destino / "tds-marl.jar", destino / "lib" / "jade.jar", destino / "lib" / "jgrapht-core-1.0.1.jar",
              destino / "instaladores" / zipjre.name, destino / "instaladores" / exe_python.name]:
        lineas.append("  %s  %s" % (digest(p, "sha256"), p.relative_to(destino).as_posix()))
    (destino / "VERSION.txt").write_bytes("\r\n".join(lineas).encode("utf-8") + b"\r\n")

    paso("5. Comprimir")
    zipfinal = DIST / (nombre + ".zip")
    if zipfinal.exists():
        zipfinal.unlink()
    with zipfile.ZipFile(zipfinal, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(destino.rglob("*")):
            z.write(p, (Path(nombre) / p.relative_to(destino)).as_posix())
    print("   %s (%.1f MB)" % (zipfinal, zipfinal.stat().st_size / 2 ** 20))
    print("   SHA-256: %s" % digest(zipfinal, "sha256"))
    if sucio:
        print("\nAVISO: el repositorio tiene cambios sin commit; para el release definitivo, haz commit y vuelve a empaquetar.")


if __name__ == "__main__":
    main()
