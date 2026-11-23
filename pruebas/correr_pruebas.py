#!/usr/bin/env python3
"""Corre todas las pruebas automaticas (unos 40 s, no levanta JADE).

  1. verificar_codigo_original.py: el codigo original de la simulacion y del
     scheduling no cambio (si falla, no sigue).
  2. test_estadisticas: calculos de EstadisticasPlanInicial con datos de
     resultado conocido, y validacion de parametros en Java.
  3. test_correr_lote: validacion del JSON, comandos y lotes completos con un
     java falso (OK, NO VALIDA, plan vacio, fallida, colgada, saltada,
     detener, Ctrl+C, retomar, --simular).
  4. test_interfaz: la ventana de lotes (se salta si no hay tkinter o pantalla).

Uso (desde cualquier carpeta):  python3 pruebas/correr_pruebas.py
"""
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def main():
    print("=== 1. Codigo original intacto ===", flush=True)
    r = subprocess.run([sys.executable, str(REPO / "pruebas" / "verificar_codigo_original.py")])
    if r.returncode != 0:
        print("\nFALLA la verificacion del codigo original: no se corren las demas pruebas.")
        return 1
    sys.path.insert(0, str(REPO))
    ok = True
    for n, modulo in enumerate(("test_estadisticas", "test_correr_lote", "test_interfaz"), 2):
        print("\n=== %d. %s ===" % (n, modulo), flush=True)
        suite = unittest.defaultTestLoader.loadTestsFromName("pruebas." + modulo)
        ok &= unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful()
    print("\n" + ("TODAS LAS PRUEBAS PASARON." if ok else "HAY PRUEBAS QUE FALLARON (ver arriba)."))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
