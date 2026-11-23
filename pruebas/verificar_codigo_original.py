#!/usr/bin/env python3
"""Verifica que el codigo ORIGINAL de la simulacion y del scheduling no cambio.

Compara cada archivo src/*.java que existia en el commit original (ORIGINAL,
anterior a la rama cierre-schedule-inicial) con su version actual en disco.
Falla (codigo de salida 1) si:
  - cambio un archivo PROTEGIDO (agentes, Schedule, Slot, registros, plan,
    reprogramacion, ... : todo lo que no esta en AUTORIZADOS), o
  - se borro un archivo original, o
  - cambio un valor por defecto de los parametros.
Los archivos AUTORIZADOS solo tienen cambios alrededor de la negociacion
(arranque, cierre, registros); se listan con su cantidad de lineas cambiadas.

Uso (desde la raiz del repo):  python3 pruebas/verificar_codigo_original.py
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ORIGINAL = "cc87b39"   # "Agrega preprocesador de registros...", antes de cierre-schedule-inicial

# Archivos originales que SI se modificaron, y por que (todos aprobados).
AUTORIZADOS = {
    "VentanaSimulacion.java": "cierre al capturar el plan inicial y cierre sin slots",
    "InicializadorSimulador.java": "opcion -Dtds.sinVentana (no mostrar la mina)",
    "MainContainer.java": "valida parametros, verifica etiqueta, guarda el contenedor para apagarlo",
    "ParametrosConfiguracion.java": "DEADLINE_MS y escenario entregables al lanzar (mismos valores por defecto)",
    "ParametrosSimulador.java": "etiqueta y tope entregables; USAR_PLAN_INICIAL_FIJO=false, NINGUNO; interruptor de cierre",
    "GuardarPlanInicial.java": "no guarda un plan vacio como plan unico",
    "CsvNegotiationsLogger.java": "solo el encabezado proposes/refuses corregido",
}

# Valores por defecto que deben seguir iguales (archivo, patron).
DEFECTOS = [
    ("ParametrosConfiguracion.java", r"DEADLINE_MS=ConfiguracionCorrida\.deadlineMs\(5000L\)"),
    ("ParametrosConfiguracion.java", r'NOMBRE_ARCHIVO_FLOTA = ConfiguracionCorrida\.escenario\("datosShovelTrucksGrande_original\.owl"\)'),
    ("ParametrosConfiguracion.java", r"SEGUNDO_FINAL_HORARIO = 6\*60\*60\*1000"),
    ("ParametrosSimulador.java", r'ETIQUETA_ESCENARIO = ConfiguracionCorrida\.etiqueta\("grande_F1_COMPLETE"\)'),
    ("ParametrosSimulador.java", r"TOPE_NEGOCIACION_INICIAL_MS = ConfiguracionCorrida\.topeNegociacionMs\(5_400_000L\)"),
    ("ParametrosSimulador.java", r"ARRANQUE_AUTOMATICO = true"),
    ("ParametrosSimulador.java", r"VARIACION_VELOCIDAD_CAMION = 0\.0"),
    ("ParametrosSimulador.java", r"SEMILLA_ALEATORIA = 42L"),
]


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True)


def main():
    r = git("ls-tree", "--name-only", ORIGINAL, "src/")
    if r.returncode != 0:
        print("ERROR: no se encontro el commit original %s (%s)" % (ORIGINAL, r.stderr.strip()))
        return 2
    originales = sorted(Path(p).name for p in r.stdout.split() if p.endswith(".java"))
    errores, intactos, autorizados = [], [], []
    for nombre in originales:
        actual = REPO / "src" / nombre
        if not actual.exists():
            errores.append("se borro el archivo original src/%s" % nombre)
            continue
        antes = git("show", "%s:src/%s" % (ORIGINAL, nombre)).stdout.encode("utf-8")
        ahora = actual.read_text(encoding="utf-8", errors="replace").encode("utf-8")
        if antes == ahora:
            intactos.append(nombre)
        elif nombre in AUTORIZADOS:
            n = git("diff", "--numstat", ORIGINAL, "--", "src/" + nombre).stdout.split()
            autorizados.append("%-30s +%s -%s  (%s)" % (nombre, n[0] if n else "?", n[1] if n else "?", AUTORIZADOS[nombre]))
        else:
            errores.append("CAMBIO un archivo protegido: src/%s" % nombre)
    for archivo, patron in DEFECTOS:
        if not re.search(patron, (REPO / "src" / archivo).read_text(encoding="utf-8")):
            errores.append("cambio un valor por defecto en %s (se esperaba /%s/)" % (archivo, patron))

    print("Comparado con el commit original %s: %d archivos .java" % (ORIGINAL, len(originales)))
    print("  intactos (protegidos): %d" % len(intactos))
    for x in intactos:
        print("     ok  " + x)
    print("  modificados autorizados: %d" % len(autorizados))
    for x in autorizados:
        print("     ~   " + x)
    nuevos = sorted(p.name for p in (REPO / "src").glob("*.java") if p.name not in originales)
    print("  nuevos (solo registros/configuracion): %s" % ", ".join(nuevos))
    if errores:
        print("\nFALLA:")
        for e in errores:
            print("  - " + e)
        return 1
    print("\nOK: el codigo original de la simulacion y del scheduling no cambio.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
