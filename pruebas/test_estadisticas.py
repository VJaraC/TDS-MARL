#!/usr/bin/env python3
"""Pruebas de EstadisticasPlanInicial.java con datos sinteticos de resultado conocido.

Arma en una carpeta temporal un plan_inicial.csv, un negotiations.csv y un
messages.csv inventados, ejecuta el arnes Java (pruebas/java/ArnesEstadisticas)
y compara cada estadistica con el valor calculado a mano. No toca el repositorio
ni ejecuta agentes.

Uso (desde la raiz del repo):  python3 -m unittest pruebas.test_estadisticas -v
"""
import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))
import correr_lote  # noqa: E402  (ruta de jgrapht)

PREFIJO = "datosShovelTrucksGrande_originalSinRL"   # escenario por defecto del codigo
MIN = 60_000
CAB_NEG = ("timestamp,agent,inicioNegociacion,finNegociacion,conversationId,content, duracion, ganador, "
           "proposesRecibidas, refusesRecibidas, informRecibidos, cancelRecibidos, t_sim_ms")
CAB_MSG = "timestamp,agent,agentAction,sender,receivers,performative,conversationId,content,t_sim_ms"
CAB_PLAN = "agente,tipo,contraparte,operacion,ubicacion,inicio_ms,fin_ms,material"

CP = None          # classpath del programa + arnes
_TMP = None


def setUpModule():
    """En desarrollo compila src/ y el arnes; en el release usa tds-marl.jar y pruebas/java/arnes.jar."""
    global CP, _TMP
    libs = [str(REPO / "lib" / "jade.jar"), str(correr_lote.JGRAPHT)]
    if correr_lote.modo_desarrollo() and shutil.which("javac"):
        _TMP = Path(tempfile.mkdtemp(prefix="tds_bin_"))
        fuentes = [str(p) for p in (REPO / "src").glob("*.java")] + [str(REPO / "pruebas" / "java" / "ArnesEstadisticas.java")]
        r = subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-nowarn", "-d", str(_TMP),
                            "-cp", os.pathsep.join(libs)] + fuentes, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("no compila:\n" + r.stdout + r.stderr)
        CP = os.pathsep.join([str(_TMP)] + libs)
    else:
        CP = os.pathsep.join([str(correr_lote.JAR_PROGRAMA), str(REPO / "pruebas" / "java" / "arnes.jar")] + libs)


def tearDownModule():
    if _TMP:
        shutil.rmtree(_TMP, ignore_errors=True)


def java(*args, **kw):
    """Ejecuta Java (el del release si existe) con el classpath de las pruebas."""
    cmd = correr_lote.java_cmd() + list(args[:-1]) + ["-cp", CP, args[-1]]
    return cmd


def epoch(texto):
    return int(datetime.strptime(texto, "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000)


BASE = epoch("2030-01-01 00:00:00.000")
T_CAPTURA = BASE + 10_000


def ts(seg):
    return datetime.fromtimestamp((BASE + seg * 1000) / 1000).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def carga(camion, pala, ini, fin, t):
    return [camion, "CAMION", pala, "CARGA", "#U", ini * MIN, fin * MIN, t]


def descarga(camion, fin, t):
    return [camion, "CAMION", "", "DESCARGA", "#D", (fin - 1) * MIN, fin * MIN, t]


PLAN = [
    carga("Truck0", "PA01", 0, 10, 100), carga("Truck1", "PA01", 5, 15, 100), carga("Truck0", "PA01", 20, 30, 100),
    carga("Truck2", "PA02", 355, 365, 200),                        # cruza el fin del turno
    carga("Truck3", "PA04", 0, 10, 50), carga("Truck3", "PA04", 2, 8, 50), carga("Truck1", "PA04", 4, 6, 50),
    descarga("Truck0", 40, 100), descarga("Truck2", 370, 200),
    ["PA01", "PALA", "Truck0", "CARGA", "#U", 0, 10 * MIN, 100],    # agenda de pala: no cuenta
]

NEG = [
    [ts(0), "PA01", BASE + 1000, BASE + 4000, "PA01 CNP-1", "recibi inform. ganador acepto", 3000, "Truck0", 3, 2, 1, 0, -1],
    [ts(0), "PA02", BASE + 2000, BASE + 7000, "PA02 CNP-2", "No hubieron propuestas", 5000, "null", 0, 5, 0, 0, -1],
    [ts(0), "PA02", BASE + 8000, BASE + 9000, "PA02 CNP-3", "deadline cumplido. No hubieron propuestas", 1000, "null", 2, 3, 0, 0, -1],
    [ts(0), "PA03", BASE + 3000, BASE + 6000, "PA03 CNP-4", "recibi Disconfirm", 3000, "Truck1", 1, 0, 0, 1, -1],
    [ts(0), "PA03", BASE + 3000, BASE + 6500, "PA03 CNP-4", "recibi refuse despues de request", 3500, "Truck1", 1, 0, 0, 1, -1],
    [ts(0), "PA01", BASE + 100, BASE + 200, "PA01 CNP-9", "recibi inform. ganador acepto", 100, "Truck0", 1, 0, 1, 0, 5000],
    [ts(0), "PA01", BASE + 9500, BASE + 12000, "PA01 CNP-8", "recibi inform. ganador acepto", 2500, "Truck0", 1, 0, 1, 0, -1],
]


def msg(seg, agente, accion, emisor, receptores, perf, tsim=-1):
    return [ts(seg), agente, accion, emisor, receptores, perf, "conv", "contenido", tsim]


MSG = [
    msg(1, "PA01", "SEND", "PA01", " Truck0 Truck1 Truck2", "CFP"),
    msg(1, "Truck0", "RECEIVE", "PA01", "sin receivers", "CFP"),
    msg(1, "Truck1", "RECEIVE", "PA01", "sin receivers", "CFP"),
    msg(1, "Truck2", "RECEIVE", "PA01", "sin receivers", "CFP"),
    msg(2, "Truck0", "SEND", "Truck0", "", "REFUSE"),
    msg(2, "Truck1", "SEND", "Truck1", "", "REFUSE"),
    msg(2, "Truck2", "SEND", "Truck2", "", "REFUSE"),
    msg(2, "PA01", "RECEIVE", "Truck0", "sin receivers", "REFUSE"),
    msg(2, "PA01", "RECEIVE", "Truck1", "sin receivers", "REFUSE"),
    msg(2, "PA01", "RECEIVE", "Truck2", "sin receivers", "REFUSE"),
    msg(3, "PA01", "SEND", "PA01", "", "REJECT-PROPOSAL"),
    msg(3, "Truck0", "SEND", "Truck0", "", "REQUEST"),
    msg(3, "CS03", "AGREE descarga (pendiente INFORM)", "sin sender", "Truck0", "AGREE"),
    msg(3, "Truck0", "RECEIVE", "CS03", "sin receivers", "AGREE"),
    msg(4, "Truck0", "SEND", "Truck0", "", "INFORM"),
    msg(4, "CS03", "INFORM recibido - slot registrado", "sin sender", "Truck0", "INFORM"),
    msg(4, "PA01", " Iniciando negociacion", "sin sender", "sin receptores", "sin performative"),
    msg(5, "PA01", "SEND", "PA01", " Truck0", "CFP", tsim=5000),
    msg(15, "PA01", "SEND", "PA01", " Truck0", "CFP"),
]


def leer_csv(ruta):
    with open(ruta, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def escribir_csv(ruta, cabecera, filas):
    with open(ruta, "w", encoding="utf-8", newline="") as f:
        f.write(cabecera + "\n")
        for r in filas:
            f.write(",".join(str(x) for x in r) + "\n")


class Base(unittest.TestCase):
    plan, neg, msgs = PLAN, NEG, MSG
    palas = "PA01,PA02,PA03,PA04,PA05"
    camiones = 5
    motivo = "negociacion_completa"
    resumen_previo = None

    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp(prefix="tds_est_"))
        corrida = cls.dir / "corridas" / "x"
        corrida.mkdir(parents=True)
        escribir_csv(corrida / "plan_inicial.csv", CAB_PLAN, cls.plan)
        escribir_csv(cls.dir / (PREFIJO + "negotiations.csv"), CAB_NEG, cls.neg)
        with open(cls.dir / (PREFIJO + "negotiations.csv"), "a") as f:
            f.write("linea,rota\n")
        escribir_csv(cls.dir / (PREFIJO + "messages.csv"), CAB_MSG, cls.msgs)
        if cls.resumen_previo:
            (cls.dir / "corridas" / "resumen_corridas.csv").write_text(cls.resumen_previo, encoding="utf-8")
        r = subprocess.run(java("ArnesEstadisticas") + ["corridas/x", cls.motivo, str(T_CAPTURA),
                            str(T_CAPTURA - 500), str(cls.camiones), cls.palas],
                           cwd=cls.dir, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(r.stdout + r.stderr)
        cls.res = json.loads((corrida / "resumen_plan.json").read_text(encoding="utf-8"))
        cls.cfg = json.loads((corrida / "configuracion.json").read_text(encoding="utf-8"))
        cls.corrida = corrida

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, ignore_errors=True)

    def pala(self, nombre):
        return next(p for p in self.res["palas"] if p["pala"] == nombre)


class PlanNormal(Base):
    def test_produccion(self):
        r = self.res
        self.assertEqual(r["ciclos_planificados"], 7)
        self.assertEqual(r["toneladas_planificadas"], 650)
        self.assertEqual(r["ciclos_planificados_en_turno"], 6)
        self.assertEqual(r["toneladas_planificadas_en_turno"], 450)
        self.assertEqual(r["toneladas_descargadas_en_turno"], 100)
        self.assertEqual(r["camiones_con_trabajo"], 4)
        self.assertEqual(r["flota_total"], 5)
        self.assertEqual(r["pct_flota_con_trabajo"], 80)
        self.assertFalse(r["plan_vacio"])
        self.assertTrue(r["corrida_valida"])
        self.assertEqual(r["tareas_camiones"], 9)
        self.assertEqual(r["filas_plan_inicial"], 10)

    def test_union_y_solapes(self):
        p1 = self.pala("PA01")
        self.assertEqual((p1["cargas"], p1["tiempo_ocupado_min"], p1["tiempo_ocioso_min"]), (3, 25, 335))
        self.assertEqual((p1["solapes"], p1["minutos_solapados"]), (1, 5))
        p4 = self.pala("PA04")                       # tres cargas anidadas
        self.assertEqual((p4["tiempo_ocupado_min"], p4["solapes"], p4["minutos_solapados"]), (10, 3, 10))

    def test_recorte_al_turno(self):
        p2 = self.pala("PA02")
        self.assertEqual((p2["tiempo_ocupado_min"], p2["tiempo_ocioso_min"]), (5, 355))
        self.assertGreaterEqual(min(p["tiempo_ocioso_min"] for p in self.res["palas"]), 0)

    def test_palas_sin_cargas_y_promedios(self):
        for nombre in ("PA03", "PA05"):
            p = self.pala(nombre)
            self.assertEqual((p["cargas"], p["tiempo_ocupado_min"], p["uso_pct"]), (0, 0, 0))
        self.assertEqual(len(self.res["palas"]), 5)
        self.assertEqual(self.res["tiempo_ocupado_promedio_min"], 8)
        self.assertEqual(self.res["uso_promedio_palas_pct"], 2.22)
        self.assertEqual((self.res["solapes_total"], self.res["minutos_solapados_total"]), (4, 15))

    def test_negociaciones(self):
        r = self.res
        self.assertEqual(r["negociaciones_total"], 5)
        self.assertEqual(r["negociaciones_con_ganador"], 1)
        self.assertEqual(r["negociaciones_sin_propuestas"], 2)
        self.assertEqual(r["negociaciones_con_0_propuestas_recibidas"], 1)
        self.assertEqual(r["duracion_negociacion_inicial_ms"], 8000)
        self.assertEqual(r["espera_copias_ms"], 500)
        self.assertEqual(r["duracion_hasta_captura_ms"], 9000)
        self.assertEqual(r["conversationid_repetidos"], 1)
        adv = " ".join(r["advertencias"])
        self.assertIn("conversationId aparecen mas de una vez", adv)
        self.assertIn("no se pudieron leer", adv)

    def test_negociacion_inicial_csv(self):
        filas = leer_csv(self.corrida / "negociacion_inicial.csv")
        self.assertEqual(len(filas), 5)
        self.assertEqual(list(filas[0].keys()), ["pala", "inicio", "fin", "duracion_ms", "propuestas", "rechazos",
                                                 "ganador", "acepto", "motivo_cierre", "id_conversacion"])
        self.assertEqual((filas[0]["acepto"], filas[0]["propuestas"], filas[0]["rechazos"]), ("true", "3", "2"))
        self.assertEqual(filas[0]["inicio"], ts(1))
        self.assertEqual(filas[1]["ganador"], "")
        self.assertEqual((filas[3]["ganador"], filas[3]["acepto"]), ("Truck1", "false"))

    def test_mensajes(self):
        r = self.res
        self.assertEqual(r["mensajes_total"], 9)
        self.assertEqual(r["mensajes_por_tipo"], {"AGREE": 1, "CFP": 3, "INFORM": 1, "REFUSE": 3, "REQUEST": 1})
        self.assertEqual(r["envios_total"], 7)
        cc = r["control_cruzado_mensajes"]
        self.assertEqual((cc["entregas_lado_emisor"], cc["entregas_lado_receptor"], cc["diferencia"]), (9, 9, 0))
        self.assertEqual(cc["filas_send_camion_sin_receptores_contadas_como_1"], 5)
        self.assertEqual(cc["filas_send_pala_sin_receptores_contadas_como_0"], 1)
        self.assertEqual(r["filas_messages_no_mensaje"], 1)
        self.assertIn("posteriores a la captura", " ".join(r["advertencias"]))

    def test_configuracion(self):
        c = self.cfg
        for k in ("llm_modelo", "llm_recomendacion", "llm_latencia_ms"):
            self.assertIsNone(c[k])
        self.assertEqual((c["deadline_ms"], c["tope_negociacion_min"]), (5000, 90))
        self.assertEqual(c["origen_parametros"]["deadline_ms"], "codigo")
        self.assertEqual(c["pesos_seleccion_pala"]["w_capacidad"], 1000)
        self.assertEqual(c["paso_barrido_ms"], 60000)
        self.assertFalse(c["sin_ventana"])

    def test_textos_y_fila_resumen(self):
        self.assertIn("Corrida VALIDA", (self.corrida / "resumen_plan.txt").read_text(encoding="utf-8"))
        filas = leer_csv(self.dir / "corridas" / "resumen_corridas.csv")
        self.assertEqual(len(filas), 1)
        self.assertEqual((filas[0]["ciclos_planificados"], filas[0]["plan_vacio"]), ("7", "false"))


class ControlCruzadoNoCalza(Base):
    """Falta una fila RECEIVE: el control cruzado debe avisar con las dos cifras."""
    msgs = [m for i, m in enumerate(MSG) if i != 1]

    def test_diferencia(self):
        cc = self.res["control_cruzado_mensajes"]
        self.assertEqual((cc["entregas_lado_emisor"], cc["entregas_lado_receptor"], cc["diferencia"]), (9, 8, 1))
        self.assertIn("9 entregas contadas desde el emisor vs 8 desde el receptor (diferencia 1)",
                      " ".join(self.res["advertencias"]))


class PlanVacioYNoValida(Base):
    plan = []
    motivo = "respaldo_estabilidad"
    resumen_previo = "etiqueta,deadline_ms,mensajes_total\nviejo,4000,123\n"

    def test_plan_vacio(self):
        r = self.res
        self.assertTrue(r["plan_vacio"])
        self.assertFalse(r["corrida_valida"])
        self.assertEqual((r["ciclos_planificados"], r["toneladas_planificadas"], r["camiones_con_trabajo"]), (0, 0, 0))
        self.assertEqual(r["uso_promedio_palas_pct"], 0)
        self.assertTrue(all(p["tiempo_ocioso_min"] == 360 for p in r["palas"]))
        txt = (self.corrida / "resumen_plan.txt").read_text(encoding="utf-8")
        self.assertIn("CORRIDA NO VALIDA", txt)
        self.assertIn("PLAN VACIO", txt)

    def test_encabezado_antiguo_se_actualiza_sin_perder_filas(self):
        filas = leer_csv(self.dir / "corridas" / "resumen_corridas.csv")
        self.assertEqual(len(filas), 2)
        self.assertEqual((filas[0]["etiqueta"], filas[0]["deadline_ms"], filas[0]["mensajes_total"]), ("viejo", "4000", "123"))
        self.assertEqual((filas[0]["plan_vacio"], filas[1]["plan_vacio"]), ("", "true"))


if __name__ == "__main__":
    unittest.main()


class ParametrosJava(unittest.TestCase):
    """ConfiguracionCorrida: valores invalidos y etiqueta repetida impiden arrancar,
    antes de borrar archivos o crear agentes (Java termina sin levantar JADE)."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="tds_cfg_"))
        (self.dir / "datosShovelTrucksPequeno.owl").write_text("x")
        (self.dir / (PREFIJO + "messages.csv")).write_text("no se debe borrar")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def java(self, *props):
        return subprocess.run(java("-Dtds.sinDialogo=true", *props, "MainContainer"),
                              cwd=self.dir, capture_output=True, text=True, timeout=60)

    def test_invalidos_no_arrancan(self):
        casos = [("-Dtds.deadlineMs=abc", "DEADLINE_MS debe ser un numero entero positivo"),
                 ("-Dtds.deadlineMs=0", "DEADLINE_MS"), ("-Dtds.deadlineMs=-5", "DEADLINE_MS"),
                 ("-Dtds.deadlineMs=1.5", "DEADLINE_MS"), ("-Dtds.deadlineMs=", "DEADLINE_MS"),
                 ("-Dtds.escenario=chico", "no es un nombre corto conocido"),
                 ("-Dtds.escenario=mediano", "no existe"),                       # no hay ese .owl en la carpeta
                 ("-Dtds.topeNegociacionMin=0", "el tope debe ser"), ("-Dtds.topeNegociacionMin=x", "el tope debe ser"),
                 ("-Dtds.etiqueta=a/b", "la etiqueta no puede"), ("-Dtds.etiqueta=a,b", "la etiqueta no puede"),
                 ("-Dtds.etiqueta=", "la etiqueta no puede")]
        for prop, texto in casos:
            r = self.java("-Dtds.escenario=pequeno", "-Dtds.etiqueta=e1", prop)
            self.assertEqual(r.returncode, 2, prop + "\n" + r.stdout + r.stderr)
            self.assertIn(texto, r.stderr, prop)
        self.assertEqual((self.dir / (PREFIJO + "messages.csv")).read_text(), "no se debe borrar")
        self.assertFalse((self.dir / "corridas").exists())

    def test_varios_errores_a_la_vez(self):
        r = self.java("-Dtds.escenario=pequeno", "-Dtds.deadlineMs=-1", "-Dtds.topeNegociacionMin=-2", "-Dtds.etiqueta=a/b")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stderr.count("\n - "), 3)

    def test_etiqueta_repetida_no_arranca(self):
        (self.dir / "corridas" / "usada").mkdir(parents=True)
        r = self.java("-Dtds.escenario=pequeno", "-Dtds.etiqueta=usada")
        self.assertEqual(r.returncode, 1)
        self.assertIn("ya existe", r.stderr)
        self.assertEqual((self.dir / (PREFIJO + "messages.csv")).read_text(), "no se debe borrar")

    def test_valores_validos_se_aceptan(self):
        # 'sin tope' y un deadline enorme son validos: la configuracion se acepta. Para no levantar
        # JADE (ni el aviso del firewall en Windows), la etiqueta ya existe y la corrida termina ahi.
        (self.dir / "corridas" / "v1").mkdir(parents=True)
        r = self.java("-Dtds.sinVentana=true", "-Dtds.escenario=pequeno", "-Dtds.etiqueta=v1",
                      "-Dtds.deadlineMs=999999999", "-Dtds.topeNegociacionMin=null")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)          # etiqueta usada: no arranca
        self.assertIn("[ConfiguracionCorrida] etiqueta=v1", r.stdout)
        self.assertIn("deadline_ms=999999999", r.stdout)
        self.assertIn("tope_negociacion=sin tope", r.stdout)
        self.assertIn("(sin ventana)", r.stdout)
