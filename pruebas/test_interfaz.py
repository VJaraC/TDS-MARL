#!/usr/bin/env python3
"""Pruebas de interfaz_lotes.py (necesita entorno grafico y tkinter).

Todo ocurre en una copia temporal de interfaz_lotes.py y correr_lote.py, con un
"java" falso en el PATH (el mismo de test_correr_lote): no se ejecuta JADE ni se
toca corridas/ del repositorio. Los dialogos se reemplazan por funciones que
registran lo que se mostro.

Uso (desde la raiz del repo):  python3 -m unittest pruebas.test_interfaz -v
"""
import importlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))
from pruebas.test_correr_lote import JAVA_FALSO  # noqa: E402

try:
    import tkinter as tk
    tk.Tk().destroy()
    HAY_TK = True
except Exception:  # sin tkinter o sin pantalla
    HAY_TK = False


@unittest.skipUnless(HAY_TK, "requiere tkinter y entorno grafico")
class Interfaz(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.d = Path(tempfile.mkdtemp(prefix="tds_ui_"))
        for f in ("correr_lote.py", "interfaz_lotes.py", "experimentos_ejemplo.json"):
            shutil.copy(REPO / f, cls.d)
        import correr_lote
        for owl in correr_lote.ESCENARIOS.values():
            (cls.d / owl).write_text("x")
        binf = cls.d / "falso"
        binf.mkdir()
        (binf / "java").write_text(JAVA_FALSO)
        (binf / "java").chmod((binf / "java").stat().st_mode | stat.S_IEXEC)
        cls.path_antes = os.environ["PATH"]
        os.environ["PATH"] = str(binf) + os.pathsep + os.environ["PATH"]
        os.environ["JAVA_FALSO_SEG"] = "2"
        # importar las COPIAS (su REPO es la carpeta temporal)
        for m in ("correr_lote", "interfaz_lotes"):
            sys.modules.pop(m, None)
        sys.path.insert(0, str(cls.d))
        cls.il = importlib.import_module("interfaz_lotes")
        assert cls.il.REPO == cls.d

    @classmethod
    def tearDownClass(cls):
        os.environ["PATH"] = cls.path_antes
        os.environ.pop("JAVA_FALSO_SEG", None)
        sys.path.remove(str(cls.d))
        for m in ("correr_lote", "interfaz_lotes"):
            sys.modules.pop(m, None)
        shutil.rmtree(cls.d, ignore_errors=True)

    def setUp(self):
        self.errores, self.preguntas = [], []
        mb = self.il.messagebox
        mb.showerror = lambda t, m: self.errores.append(m)
        mb.askyesno = lambda *a, **k: True
        self.respuesta_cerrar = None
        mb.askyesnocancel = lambda *a, **k: (self.preguntas.append(a[0]), self.respuesta_cerrar)[1]
        self.raiz = tk.Tk()
        self.ui = None

    def tearDown(self):
        try:
            self.raiz.destroy()
        except tk.TclError:
            pass

    def abrir(self, archivo=None):
        self.ui = self.il.Interfaz(self.raiz, archivo)
        self.raiz.update()
        return self.ui

    def filas(self):
        return [self.ui.tabla.item(i, "values") for i in self.ui.tabla.get_children()]

    def esperar(self, condicion, maximo=90):
        t0 = time.time()
        while time.time() - t0 < maximo:
            self.raiz.update()
            if condicion():
                return True
            time.sleep(0.1)
        return False

    # ── edicion ───────────────────────────────────────────────────────
    def test_ida_y_vuelta_del_ejemplo(self):
        ui = self.abrir(self.d / "experimentos_ejemplo.json")
        self.assertEqual(ui.a_datos(), json.loads((self.d / "experimentos_ejemplo.json").read_text()))

    def test_opciones_generales_ida_y_vuelta(self):
        ui = self.abrir()
        casos = [
            {"tiempo_maximo_por_corrida_min": None, "nucleos_cpu": "0-3", "mostrar_ventana": True},
            {"tiempo_maximo_por_corrida_min": 30, "tope_negociacion_min": 2.5, "nucleos_cpu": None},
            {"tiempo_maximo_por_corrida_min": 360, "tope_negociacion_min": None, "nucleos_cpu": None},
        ]
        for gen in casos:
            d = dict(gen, configuraciones=[{"nombre": "a", "escenario": "mediano", "repeticiones": 2, "deadline_ms": 1}])
            ui.desde_datos(d)
            self.assertEqual(ui.a_datos(), d)

    def test_agregar_duplicar_quitar_mover(self):
        ui = self.abrir()
        ui.nueva_fila()
        self.assertEqual([f[0] for f in self.filas()], ["base", "config2"])
        ui.tabla.selection_set(ui.tabla.get_children()[0])
        ui.duplicar()
        self.assertEqual([f[0] for f in self.filas()], ["base", "base_copia", "config2"])
        ui.mover(1)
        self.assertEqual([f[0] for f in self.filas()], ["base", "config2", "base_copia"])
        ui.quitar()
        self.assertEqual([f[0] for f in self.filas()], ["base", "config2"])

    def test_editar_celda_con_doble_clic(self):
        ui = self.abrir()
        iid = ui.tabla.get_children()[0]
        self.raiz.update()
        x, y, w, h = ui.tabla.bbox(iid, "#4")                          # columna deadline_ms
        ui.editar_celda(type("E", (), {"x": x + 5, "y": y + 3})())
        editor = [c for c in ui.tabla.winfo_children() if c.winfo_class() in ("TEntry", "TCombobox")][-1]
        editor.focus_force()
        self.raiz.update()
        editor.delete(0, "end")
        editor.insert(0, "1234")
        editor.event_generate("<Return>", when="tail")
        self.raiz.update()
        self.assertEqual(self.filas()[0][3], "1234")
        self.assertEqual(ui.a_datos()["configuraciones"][0]["deadline_ms"], 1234)

    def test_validacion_con_mensajes_claros(self):
        ui = self.abrir()
        ui.nueva_fila({"nombre": "base", "escenario": "pequeno", "repeticiones": "x"})
        ui.nueva_fila({"nombre": "b", "escenario": "pequeno", "repeticiones": 1, "deadline_ms": "-3"})
        ui.v_tmax.set("mucho")
        self.assertIsNone(ui.validar())
        self.assertIn("'x' no es un número entero", self.errores[-1])
        self.assertIn("Tiempo máximo: 'mucho'", self.errores[-1])
        ui.v_tmax.set("60")
        ui.tabla.item(ui.tabla.get_children()[1], values=("base", "pequeno", "1", ""))
        self.assertIsNone(ui.validar())
        self.assertIn("el nombre esta repetido", self.errores[-1])
        ui.tabla.item(ui.tabla.get_children()[1], values=("otra", "pequeno", "1", "-3"))
        self.assertIsNone(ui.validar())
        self.assertIn("'deadline_ms' debe ser un entero > 0", self.errores[-1])
        n = len(self.errores)
        ui.tabla.item(ui.tabla.get_children()[1], values=("otra", "pequeno", "1", ""))
        ui.tabla.item(ui.tabla.get_children()[2], values=("b", "pequeno", "1", "2"))
        self.assertIsNotNone(ui.validar())
        self.assertEqual(len(self.errores), n)

    def test_guardar_no_escribe_si_hay_errores(self):
        ui = self.abrir()
        ui.archivo = self.d / "no_debe_existir.json"
        ui.nueva_fila({"nombre": "base", "escenario": "pequeno", "repeticiones": 1})     # nombre repetido
        self.assertFalse(ui.guardar())
        self.assertFalse(ui.archivo.exists())

    def test_cargar_json_roto(self):
        roto = self.d / "roto.json"
        roto.write_text("{ no")
        ui = self.abrir()
        ui.cargar(roto)
        self.assertTrue(self.errores)
        self.assertIsNone(ui.archivo)

    def test_simular(self):
        ui = self.abrir(self.d / "experimentos_ejemplo.json")
        lotes = self.d / "corridas" / "_lotes"
        antes = sorted(lotes.iterdir()) if lotes.exists() else []
        ui.simular()
        ventanas = [w for w in self.raiz.winfo_children() if isinstance(w, tk.Toplevel)]
        texto = ventanas[-1].winfo_children()[0].get("1.0", "end")
        self.assertIn("SIMULACION: no se compila ni se ejecuta nada.", texto)
        self.assertIn("Total: 30 corridas", texto)
        self.assertEqual(sorted(lotes.iterdir()) if lotes.exists() else [], antes)   # no crea lotes

    # ── lanzar ────────────────────────────────────────────────────────
    def preparar_lote(self, nombre, reps):
        archivo = self.d / ("%s.json" % nombre)
        archivo.write_text(json.dumps({"tiempo_maximo_por_corrida_min": 5, "configuraciones": [
            {"nombre": nombre, "escenario": "pequeno", "repeticiones": reps}]}))
        ui = self.abrir(archivo)
        # el script compilaria con javac: se lanza con --sin-compilar agregando el argumento
        orig = subprocess.Popen
        self.il.subprocess.Popen = lambda cmd, **kw: orig(cmd + ["--sin-compilar"], **kw)
        self.addCleanup(setattr, self.il.subprocess, "Popen", orig)
        return ui

    def test_lanzar_progreso_y_detener(self):
        ui = self.preparar_lote("ui_ok", 3)
        ui.lanzar()
        self.assertEqual(str(ui.b_lanzar.cget("state")), "disabled")
        vistos = set()

        def mirar():
            vistos.update({ui.l_lote.cget("text"), ui.l_corrida.cget("text"), ui.l_detalle.cget("text")})
            return "Corrida 2 de 3" in ui.l_corrida.cget("text")
        self.assertTrue(self.esperar(mirar))
        ui.detener()
        self.assertIn("Se detendrá", ui.b_detener.cget("text"))
        self.assertTrue(self.esperar(lambda: (mirar(), ui.proc is None)[1]))
        estados = [ui.t_corridas.item(i, "values")[1] for i in ui.t_corridas.get_children()]
        self.assertEqual(estados, ["OK", "OK", "no ejecutada"])
        self.assertEqual(ui.pb_lote["value"], 100.0)
        self.assertTrue(any("sin estimacion" in v for v in vistos))
        self.assertTrue(any("resta: 0 min 0" in v or "resta: 0 min 1" in v or "resta: 0 min 2" in v for v in vistos))
        self.assertTrue(any(v.startswith("Palas con turno lleno: 1 de 5") for v in vistos))
        self.assertIn("Resumen del lote", ui.texto.get("1.0", "end"))
        self.assertEqual(str(ui.b_lanzar.cget("state")), "normal")

    def test_cerrar_con_lote_corriendo(self):
        # Cancelar: la ventana sigue y el lote tambien
        ui = self.preparar_lote("ui_cerrar", 2)
        ui.lanzar()
        self.esperar(lambda: "Corrida 1" in ui.l_corrida.cget("text"))
        self.respuesta_cerrar = None
        ui.al_cerrar()
        self.assertEqual(len(self.preguntas), 1)
        self.assertTrue(self.raiz.winfo_exists())
        self.assertTrue(ui.corriendo())
        # Si: pide detener tras la corrida en curso y cierra; el lote termina solo
        proc, carpeta = ui.proc, ui.dir_lote
        self.respuesta_cerrar = True
        ui.al_cerrar()
        self.assertTrue((carpeta / "DETENER").exists())
        proc.wait(60)
        estados = [c["estado"] for c in json.loads((carpeta / "estado.json").read_text())["corridas"]]
        self.assertEqual(estados, ["OK", "no ejecutada"])

    def test_cerrar_y_dejar_corriendo(self):
        ui = self.preparar_lote("ui_fondo", 2)
        ui.lanzar()
        proc, carpeta = ui.proc, ui.dir_lote
        self.respuesta_cerrar = False
        ui.al_cerrar()                                                  # No: cierra sin detener
        self.assertFalse((carpeta / "DETENER").exists())
        proc.wait(60)                                                   # el lote sigue hasta el final
        estados = [c["estado"] for c in json.loads((carpeta / "estado.json").read_text())["corridas"]]
        self.assertEqual(estados, ["OK", "OK"])

    def test_cerrar_sin_lote_no_pregunta(self):
        ui = self.abrir()
        ui.al_cerrar()
        self.assertEqual(self.preguntas, [])


if __name__ == "__main__":
    unittest.main()
