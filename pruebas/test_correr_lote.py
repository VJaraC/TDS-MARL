#!/usr/bin/env python3
"""Pruebas de correr_lote.py.

- Validacion del JSON y armado de comandos: directamente sobre el modulo.
- Ejecucion de lotes: en una copia de correr_lote.py dentro de una carpeta
  temporal, con un "java" FALSO (un script de Python, indicado con la variable
  TDS_JAVA_CMD; funciona en Linux y en Windows) que imita los desenlaces de una
  corrida segun su etiqueta (no se ejecuta JADE ni se toca corridas/ del repo):
    ..._ok_...      deja resumen_plan.json valido          -> OK
    ..._novalida_.. deja resumen_plan.json no valido       -> NO VALIDA
    ..._vacio_...   valido con plan vacio                  -> OK (plan vacio)
    ..._falla_...   termina con codigo 3 sin resultados    -> fallida
    ..._cuelga_...  no termina nunca                       -> colgada
Uso (desde la raiz del repo):  python3 -m unittest pruebas.test_correr_lote -v
"""
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))
import correr_lote as cl  # noqa: E402

JAVA_FALSO = r'''#!/usr/bin/env python3
import json, os, sys, time
args = sys.argv[1:]
props = dict(a[2:].split("=", 1) for a in args if a.startswith("-D"))
et = props["tds.etiqueta"]
with open("llamadas.txt", "a") as f:
    f.write(json.dumps(args) + "\n")
print("[ConfiguracionCorrida] etiqueta=%s" % et, flush=True)
print("[Negociacion inicial] t+0s  palas 1/5  camiones 3  slots 12  (quieto hace 0s)", flush=True)
if "_cuelga" in et:
    time.sleep(600)
if "_falla" in et:
    sys.exit(3)
time.sleep(float(os.environ.get("JAVA_FALSO_SEG", "0.3")))
d = os.path.join("corridas", et)
os.makedirs(d)
json.dump({"corrida_valida": "_novalida" not in et, "plan_vacio": "_vacio" in et},
          open(os.path.join(d, "resumen_plan.json"), "w"))
'''


def lote(confs, **gen):
    d = {"configuraciones": confs}
    d.update(gen)
    return d


class Validacion(unittest.TestCase):
    def errores(self, datos):
        with self.assertRaises(cl.ErrorLote) as e:
            cl.validar_datos(datos)
        return str(e.exception)

    def test_ejemplo_valido(self):
        gen, corridas = cl.leer_lote(REPO / "experimentos_ejemplo.json")
        self.assertEqual(len(corridas), 30)
        self.assertEqual(corridas[0]["etiqueta"], "base_r01")
        self.assertEqual(corridas[-1]["etiqueta"], "deadline8000_r10")
        self.assertEqual(gen["tiempo_maximo_por_corrida_min"], 360)

    def test_por_defecto(self):
        gen, c = cl.validar_datos(lote([{"nombre": "a", "escenario": "pequeno", "repeticiones": 1}]))
        self.assertEqual(gen["tiempo_maximo_por_corrida_min"], 360)       # clave ausente
        self.assertNotIn("tope_negociacion_min", gen)                      # no se entrega: Java usa 90
        self.assertEqual(c[0]["propiedades"], {})                          # deadline por defecto
        self.assertFalse(gen["mostrar_ventana"])
        self.assertIsNone(gen["nucleos_cpu"])

    def test_nulls(self):
        gen, c = cl.validar_datos(lote([{"nombre": "a", "escenario": "pequeno", "repeticiones": 1, "deadline_ms": None}],
                                       tiempo_maximo_por_corrida_min=None, tope_negociacion_min=None))
        self.assertIsNone(gen["tiempo_maximo_por_corrida_min"])
        self.assertEqual(c[0]["propiedades"], {"tds.topeNegociacionMin": "null"})

    def test_escenarios(self):
        for corto, archivo in cl.ESCENARIOS.items():
            _, c = cl.validar_datos(lote([{"nombre": "a", "escenario": corto, "repeticiones": 1}]))
            self.assertEqual(c[0]["escenario"], archivo)
        _, c = cl.validar_datos(lote([{"nombre": "a", "escenario": "GRANDE", "repeticiones": 1}]))
        self.assertEqual(c[0]["escenario"], "datosShovelTrucksGrande_original.owl")
        _, c = cl.validar_datos(lote([{"nombre": "a", "escenario": "datosShovelTrucksMediano.owl", "repeticiones": 1}]))
        self.assertEqual(c[0]["escenario"], "datosShovelTrucksMediano.owl")

    def test_deadlines_sin_limites(self):
        for v in (1, 2000, 2001, 10 ** 9):
            _, c = cl.validar_datos(lote([{"nombre": "a", "escenario": "pequeno", "repeticiones": 1, "deadline_ms": v}]))
            self.assertEqual(c[0]["propiedades"]["tds.deadlineMs"], str(v))

    def test_errores_juntos(self):
        e = self.errores({"tiempo_maximo_por_corrida_min": 0, "tope_negociacion_min": -1, "nucleos_cpu": "a,b",
                          "mostrar_ventana": "si", "configuraciones": [
                              {"nombre": "a", "escenario": "pequeno", "repeticiones": 0},
                              {"nombre": "a", "escenario": "chico", "repeticiones": 1.5, "deadline_ms": 0},
                              {"nombre": "x y", "escenario": "pequeno", "repeticiones": 1},
                              {"nombre": "b", "escenario": "pequeno", "repeticiones": 1, "deadline_ms": 2.5},
                              {"nombre": "c", "escenario": "pequeno", "repeticiones": 1, "deadlin_ms": 5},
                              {"nombre": "d", "escenario": "pequeno", "repeticiones": True},
                              {"nombre": "e", "escenario": "pequeno", "repeticiones": 1, "deadline_ms": "1000"},
                              "no soy un objeto"]})
        for txt in ("tiempo_maximo_por_corrida_min", "tope_negociacion_min debe", "nucleos_cpu", "mostrar_ventana",
                    "'a': 'repeticiones'", "el nombre esta repetido", "'chico' no es pequeno",
                    "configuracion 3: 'nombre'", "'b': 'deadline_ms'", "claves desconocidas ['deadlin_ms']",
                    "'d': 'repeticiones'", "'e': 'deadline_ms'", "configuracion 8: debe ser un objeto"):
            self.assertIn(txt, e)

    def test_estructura_invalida(self):
        self.assertIn("objeto", self.errores([]))
        self.assertIn("al menos una", self.errores({"configuraciones": []}))
        self.assertIn("al menos una", self.errores({}))

    def test_json_roto_y_archivo_inexistente(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write("{ roto")
        try:
            with self.assertRaises(cl.ErrorLote) as e:
                cl.leer_lote(f.name)
            self.assertIn("no es un JSON valido", str(e.exception))
        finally:
            os.unlink(f.name)
        with self.assertRaises(cl.ErrorLote):
            cl.leer_lote("/no/existe.json")

    def test_ancho_de_etiquetas(self):
        _, c = cl.validar_datos(lote([{"nombre": "a", "escenario": "pequeno", "repeticiones": 100}]))
        self.assertEqual((c[0]["etiqueta"], c[-1]["etiqueta"]), ("a_r001", "a_r100"))
        self.assertEqual(len({x["etiqueta"] for x in c}), 100)


class Comandos(unittest.TestCase):
    def cmd(self, conf, **gen):
        g, c = cl.validar_datos(lote([conf], **gen))
        return cl.comando(c[0], g)

    def test_base(self):
        cmd = self.cmd({"nombre": "a", "escenario": "pequeno", "repeticiones": 1})
        self.assertEqual(cmd[:len(cl.java_cmd())], cl.java_cmd())
        for x in ("-Dtds.etiqueta=a_r01", "-Dtds.escenario=datosShovelTrucksPequeno.owl",
                  "-Dtds.sinDialogo=true", "-Dtds.sinVentana=true", "MainContainer"):
            self.assertIn(x, cmd)
        self.assertFalse(any(x.startswith("-Dtds.deadlineMs") or x.startswith("-Dtds.topeNegociacionMin") for x in cmd))

    def test_parametros(self):
        cmd = self.cmd({"nombre": "a", "escenario": "pequeno", "repeticiones": 1, "deadline_ms": 750},
                       tope_negociacion_min=None, nucleos_cpu="2,3", mostrar_ventana=True)
        self.assertEqual(cmd[:3], ["taskset", "-c", "2,3"])
        self.assertIn("-Dtds.deadlineMs=750", cmd)
        self.assertIn("-Dtds.topeNegociacionMin=null", cmd)
        self.assertNotIn("-Dtds.sinVentana=true", cmd)

    def test_tope_por_configuracion_gana(self):
        cmd = self.cmd({"nombre": "a", "escenario": "pequeno", "repeticiones": 1, "tope_negociacion_min": 2.5},
                       tope_negociacion_min=None)
        self.assertIn("-Dtds.topeNegociacionMin=2.5", cmd)

    def test_classpath_usa_separador_del_sistema(self):
        g, _ = cl.validar_datos(lote([{"nombre": "a", "escenario": "pequeno", "repeticiones": 1}]))
        self.assertEqual(len(cl.classpath(g).split(os.pathsep)), 3)


class Auxiliares(unittest.TestCase):
    def test_ultima_traza(self):
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("ruido\n[Negociacion inicial] t+15s  palas 0/5  camiones 3  slots 12  (quieto hace 0s)\n"
                    "x\n[Negociacion inicial] t+30s  palas 2/5  camiones 13  slots 120  (quieto hace 4s)\n"
                    "[Negociacion inicial] t+45s  palas 3/5  camiones 0  slots 0  (sin slots todavia)\n")
        try:
            self.assertEqual(cl.ultima_traza(f.name), (45, 3, 5, 0, 0))
        finally:
            os.unlink(f.name)
        self.assertIsNone(cl.ultima_traza("/no/existe.log"))

    def test_hms(self):
        self.assertEqual(cl.hms(None), "sin estimacion")
        self.assertEqual(cl.hms(65), "1 min 05 s")
        self.assertEqual(cl.hms(3 * 3600 + 600), "3 h 10 min")
        self.assertEqual(cl.hms(-5), "0 min 00 s")

    def test_estimacion(self):
        d = Path(tempfile.mkdtemp())
        try:
            corr = [{"configuracion": k, "etiqueta": "x%d" % i} for i, k in enumerate("aab")]
            p = cl.Progreso(d, corr)
            p.se_saltara = [False, False, False]
            self.assertIsNone(p.restante())                              # sin datos de nada
            p.duraciones = {"a": [100.0]}
            self.assertIsNone(p.restante())                              # falta la configuracion b
            p.duraciones = {"a": [100.0, 200.0], "b": [50.0]}
            p.estados = ["OK", "pendiente", "pendiente"]
            self.assertAlmostEqual(p.restante(), 150 + 50)
            p.estados[1], p.actual, p.t0 = "corriendo", 1, time.time() - 100
            self.assertAlmostEqual(p.restante(), 50 + 50, delta=2)       # a la en curso le quedan ~50 s
            (d / "DETENER").write_text("x")
            self.assertAlmostEqual(p.restante(), 50, delta=2)            # las pendientes ya no cuentan
        finally:
            shutil.rmtree(d)


class Lotes(unittest.TestCase):
    """Lotes completos con el java falso, en una carpeta temporal."""

    def setUp(self):
        self.d = Path(tempfile.mkdtemp(prefix="tds_lote_"))
        shutil.copy(REPO / "correr_lote.py", self.d)
        for owl in cl.ESCENARIOS.values():
            (self.d / owl).write_text("x")
        (self.d / "java_falso.py").write_text(JAVA_FALSO)
        self.env = dict(os.environ, TDS_JAVA_CMD=json.dumps([sys.executable, str(self.d / "java_falso.py")]))

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def lanzar(self, datos, *extra, espera=True):
        (self.d / "lote.json").write_text(json.dumps(datos))
        cmd = [sys.executable, str(self.d / "correr_lote.py"), "lote.json", "--sin-compilar"] + list(extra)
        if not espera:
            return subprocess.Popen(cmd, cwd=self.d, env=self.env, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True)
        return subprocess.run(cmd, cwd=self.d, env=self.env, capture_output=True, text=True, timeout=120)

    def registro(self):
        lotes = sorted((self.d / "corridas" / "_lotes").iterdir())
        return json.loads((lotes[-1] / "lote.json").read_text()), lotes[-1]

    def test_todos_los_desenlaces_y_retomar(self):
        datos = lote([{"nombre": "c_ok", "escenario": "pequeno", "repeticiones": 2},
                      {"nombre": "c_novalida", "escenario": "mediano", "repeticiones": 1},
                      {"nombre": "c_vacio", "escenario": "grande", "repeticiones": 1, "deadline_ms": 1},
                      {"nombre": "c_falla", "escenario": "pequeno", "repeticiones": 1},
                      {"nombre": "c_cuelga", "escenario": "pequeno", "repeticiones": 1}],
                     tiempo_maximo_por_corrida_min=0.05)                       # 3 s
        (self.d / "corridas" / "c_ok_r02").mkdir(parents=True)                # ya hecha: se salta
        r = self.lanzar(datos)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        reg, carpeta = self.registro()
        est = {c["etiqueta"]: c["estado"] for c in reg["corridas"]}
        self.assertEqual(est, {"c_ok_r01": "OK", "c_ok_r02": "saltada", "c_novalida_r01": "NO VALIDA",
                               "c_vacio_r01": "OK", "c_falla_r01": "fallida", "c_cuelga_r01": "colgada"})
        filas = {c["etiqueta"]: c for c in reg["corridas"]}
        self.assertTrue(filas["c_vacio_r01"]["plan_vacio"])
        self.assertEqual(filas["c_falla_r01"]["codigo_salida"], 3)
        self.assertIn("supero", filas["c_cuelga_r01"]["motivo"])
        self.assertLess(filas["c_cuelga_r01"]["duracion_s"], 30)
        for f in ("inicio", "fin", "duracion_s", "codigo_salida", "comando", "log"):
            self.assertIn(f, filas["c_ok_r01"])
        self.assertTrue((carpeta / "c_ok_r01.log").exists())
        self.assertIn("[Negociacion inicial]", (carpeta / "c_cuelga_r01.log").read_text())
        self.assertEqual(reg["experimentos"], datos)
        for linea in ("OK:              2", "NO VALIDAS:      1", "colgadas:        1", "fallidas:        1",
                      "saltadas:        1", "con plan vacio: c_vacio_r01"):
            self.assertIn(linea, r.stdout)
        estado = json.loads((carpeta / "estado.json").read_text())
        self.assertTrue(estado["finalizado"])
        self.assertEqual(estado["terminadas"], estado["total"])
        self.assertIn("lote.log", os.listdir(carpeta))
        # comandos: deadline solo donde se dio, sin ventana ni dialogo
        llamadas = [json.loads(l) for l in (self.d / "llamadas.txt").read_text().splitlines()]
        self.assertEqual(len(llamadas), 5)                                     # la saltada no se lanza
        vacio = next(a for a in llamadas if "-Dtds.etiqueta=c_vacio_r01" in a)
        self.assertIn("-Dtds.deadlineMs=1", vacio)
        self.assertIn("-Dtds.escenario=datosShovelTrucksGrande_original.owl", vacio)
        self.assertTrue(all("-Dtds.sinVentana=true" in a and "-Dtds.sinDialogo=true" in a for a in llamadas))
        self.assertFalse(any(a for a in llamadas if "-Dtds.etiqueta=c_ok_r01" in a and any("deadline" in x for x in a)))
        # retomar: solo se repiten las que no dejaron carpeta (fallida y colgada)
        r2 = self.lanzar(datos)
        reg2, _ = self.registro()
        est2 = {c["etiqueta"]: c["estado"] for c in reg2["corridas"]}
        self.assertEqual(sorted(k for k, v in est2.items() if v != "saltada"), ["c_cuelga_r01", "c_falla_r01"])

    def test_simular_no_ejecuta_nada(self):
        datos = lote([{"nombre": "s_ok", "escenario": "pequeno", "repeticiones": 3}])
        (self.d / "corridas" / "s_ok_r02").mkdir(parents=True)
        r = self.lanzar(datos, "--simular")
        self.assertEqual(r.returncode, 0)
        self.assertIn("SALTARIA", r.stdout)
        self.assertIn("Total: 3 corridas, 2 se correrian, 1 se saltarian.", r.stdout)
        self.assertFalse((self.d / "llamadas.txt").exists())
        self.assertFalse((self.d / "corridas" / "_lotes").exists())
        self.assertFalse((self.d / "bin").exists())

    def test_json_invalido_no_corre_nada(self):
        r = self.lanzar(lote([{"nombre": "a", "escenario": "pequeno", "repeticiones": 0}]))
        self.assertEqual(r.returncode, 2)
        self.assertIn("repeticiones", r.stderr)
        self.assertFalse((self.d / "llamadas.txt").exists())

    def test_detener_antes_de_la_siguiente(self):
        carpeta = self.d / "corridas" / "_lotes" / "manual"
        carpeta.mkdir(parents=True)
        datos = lote([{"nombre": "t_ok", "escenario": "pequeno", "repeticiones": 3}])
        env_lento = dict(self.env, JAVA_FALSO_SEG="2")
        (self.d / "lote.json").write_text(json.dumps(datos))
        p = subprocess.Popen([sys.executable, str(self.d / "correr_lote.py"), "lote.json", "--sin-compilar",
                              "--carpeta-lote", str(carpeta)], cwd=self.d, env=env_lento,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        time.sleep(1)                                                          # la primera esta corriendo
        (carpeta / "DETENER").write_text("x")
        out, _ = p.communicate(timeout=60)
        self.assertEqual(p.returncode, 0)
        estado = json.loads((carpeta / "estado.json").read_text())
        self.assertEqual([c["estado"] for c in estado["corridas"]], ["OK", "no ejecutada", "no ejecutada"])
        self.assertTrue((self.d / "corridas" / "t_ok_r01" / "resumen_plan.json").exists())   # no se corto
        self.assertIn("no ejecutadas:", out)
        self.assertTrue(json.loads((carpeta / "lote.json").read_text())["detenido"])

    @unittest.skipIf(os.name == "nt", "senal SIGINT solo en Linux/Mac")
    def test_ctrl_c_interrumpe(self):
        datos = lote([{"nombre": "i_cuelga", "escenario": "pequeno", "repeticiones": 2}], tiempo_maximo_por_corrida_min=None)
        p = self.lanzar(datos, espera=False)
        time.sleep(2)
        p.send_signal(signal.SIGINT)
        out, _ = p.communicate(timeout=60)
        self.assertEqual(p.returncode, 130)
        self.assertIn("interrumpida", out)
        reg, _ = self.registro()
        self.assertEqual([c["estado"] for c in reg["corridas"]], ["interrumpida"])
        r = subprocess.run(["pgrep", "-f", "tds.etiqueta=i_cuelga"], capture_output=True, text=True)
        self.assertEqual(r.stdout.strip(), "")                                 # no quedo el proceso vivo


if __name__ == "__main__":
    unittest.main()
