#!/usr/bin/env python3
# ─────────────────────────────────────────────────────────────────────────────
# OBSOLETO (nov-2026). Este script era para los EPISODIOS DE REPROGRAMACION, que
# el trabajo de titulo ya no estudia. Desde la rama cierre-schedule-inicial cada
# corrida termina al generarse el schedule inicial y escribe sus propios
# registros (ver DOCUMENTACION_REGISTROS.md y EstadisticasPlanInicial.java).
#
# Ademas, el encabezado de negotiations.csv YA ESTA CORREGIDO: las columnas 9 y
# 10 dicen "proposesRecibidas, refusesRecibidas", en el orden en que se
# escriben. Si se reutiliza este codigo, ya NO hay que invertir ni reinterpretar
# esas columnas al leer (leer_negociaciones las toma por posicion, que sigue
# siendo correcto).
# Se conserva solo como referencia; no se modifico su logica.
# ─────────────────────────────────────────────────────────────────────────────
"""Preprocesador de registros de TDS-MARL.

Lee los CSV que el simulador ya genera y produce, por corrida, un JSON compacto
(solo agregados, nunca filas crudas) para alimentar al componente LLM.

Uso:
  python preprocesar_registros.py manifest
      Lee src/*.java y escribe corridas/<etiqueta>/manifest.json. Llamarlo al
      LANZAR la corrida: los .java se editan entre corridas.

  python preprocesar_registros.py procesar --etiqueta pequeno_NINGUNO_r1
      Escribe corridas/<etiqueta>/registro_llm.json. Si no hay manifest.json,
      reconstruye los parametros desde los datos (origen "reconstruido"; lo
      que no se puede reconstruir queda en null).

Solo biblioteca estandar. No modifica ningun archivo de entrada.
"""
import argparse
import bisect
import csv
import json
import re
import statistics
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

VERSION_ESQUEMA = 1
FMT_TS = "%Y-%m-%d %H:%M:%S.%f"
GAP_EPISODIO_S = 5.0  # filas de liberaciones.csv a menos de 5 s reales = mismo episodio
MARGEN_INICIO_S = 1.0  # adelanto del borde real de cada episodio (ver procesar)


# ───────────────────────── utilidades ─────────────────────────

def num(s):
    """Convierte un campo CSV a int/float/bool/None/str."""
    if s is None:
        return None
    s = s.strip()
    if s == "" or s == "null":
        return None
    if s in ("true", "false"):
        return s == "true"
    try:
        return int(s)
    except ValueError:
        pass
    try:
        return float(s)
    except ValueError:
        return s


def leer_csv(ruta):
    """Lista de dicts (encabezados sin espacios). [] si el archivo no existe."""
    ruta = Path(ruta)
    if not ruta.exists():
        return []
    with open(ruta, newline="", encoding="utf-8", errors="replace") as f:
        lector = csv.reader(f)
        try:
            cab = [c.strip() for c in next(lector)]
        except StopIteration:
            return []
        return [dict(zip(cab, fila)) for fila in lector if fila]


def de_corrida(filas, etiqueta):
    return [f for f in filas if f.get("escenario") == etiqueta]


def ts(s):
    """Timestamp real del simulador -> datetime (acepta con o sin milisegundos)."""
    return datetime.strptime(s if "." in s else s + ".000", FMT_TS)


def hms_a_ms(s):
    """'1:08:12' o '1:8:12' -> ms. None si no calza."""
    m = re.fullmatch(r"(\d+):(\d+):(\d+)", s.strip())
    if not m:
        return None
    h, mi, se = map(int, m.groups())
    return ((h * 60 + mi) * 60 + se) * 1000


def redondear(x, n=1):
    return None if x is None else round(x, n)


def tipo_agente(nombre):
    if nombre.startswith("Truck"):
        return "camion"
    if re.fullmatch(r"PA\d+", nombre):
        return "pala"
    if nombre == "ReschedulingAgent":
        return "rescheduling"
    return "destino"


_RE_CONV = re.compile(r"\S+ CNP-\d+")


def tipo_evento_interno(accion):
    """Normaliza el texto libre de agentAction a un tipo estable.
    'llegaron todas las respuestas. numero de trucks 15...' -> 'llegaron todas las respuestas'
    ' - Negociacion PA05 CNP-123 finalizada. No hubieron...' -> 'Negociacion finalizada'
    'evaluacion multicriterio: costo=...' -> 'evaluacion multicriterio'
    """
    t = _RE_CONV.sub("", accion)
    t = re.split(r"[:.]", t, maxsplit=1)[0]
    t = re.sub(r"\s+", " ", t).strip(" -")
    return t or "(vacio)"


# ───────────────────────── manifest ─────────────────────────

def _literal_java(expr):
    """Evalua un literal Java simple: 5000L, 5_400_000L, 6*60*60*1000, "x", true, 0.25."""
    e = expr.strip()
    if e.startswith('"') and e.endswith('"'):
        return e[1:-1]
    if e in ("true", "false"):
        return e == "true"
    e = e.replace("_", "")
    if re.fullmatch(r"[\d\s*.Ll]+", e):
        prod = 1
        for parte in e.split("*"):
            parte = parte.strip().rstrip("Ll")
            prod *= float(parte) if "." in parte else int(parte)
        return prod
    return None


def _const_java(texto, nombre):
    """Valor de la constante `nombre` (ignora lineas comentadas con //)."""
    m = re.search(r"^[ \t]*public static [\w ]*?\b%s\s*=\s*([^;]+);" % re.escape(nombre),
                  texto, re.MULTILINE)
    return _literal_java(m.group(1)) if m else None


def _ms_a_min(v):
    return None if v is None else v / 60000


def manifest_desde_java(repo):
    """Parametros de la corrida leidos de las fuentes Java y del OWL de flota."""
    src = Path(repo) / "src"
    def leer(n):
        p = src / n
        return p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""
    cfg, sim, res = leer("ParametrosConfiguracion.java"), leer("ParametrosSimulador.java"), leer("ReschedulingAgent.java")

    m = re.search(r"METODO_RESCHEDULING\s*=\s*MetodoRescheduling\.(\w+)\s*;", sim)
    flota = _const_java(cfg, "NOMBRE_ARCHIVO_FLOTA")
    valores = {
        "etiqueta": (_const_java(sim, "ETIQUETA_ESCENARIO"), "ParametrosSimulador.java"),
        "flota": (flota, "ParametrosConfiguracion.java"),
        "aprendizaje": (_const_java(cfg, "LEARNING_ENABLED") == "true"
                        if _const_java(cfg, "LEARNING_ENABLED") is not None else None,
                        "ParametrosConfiguracion.java"),
        "DEADLINE_MS": (_const_java(cfg, "DEADLINE_MS"), "ParametrosConfiguracion.java"),
        "turno_ms": (_const_java(cfg, "SEGUNDO_FINAL_HORARIO"), "ParametrosConfiguracion.java"),
        "metodo_rescheduling": (m.group(1) if m else None, "ParametrosSimulador.java"),
        "rescheduling_activo": (_const_java(sim, "RESCHEDULING_ACTIVO"), "ParametrosSimulador.java"),
        "incertidumbres": (_const_java(sim, "INCERTIDUMBRES_ACTIVAS"), "ParametrosSimulador.java"),
        "velocidad": (_const_java(sim, "VELOCIDAD_REPRODUCCION"), "ParametrosSimulador.java"),
        "semilla": (_const_java(sim, "SEMILLA_ALEATORIA"), "ParametrosSimulador.java"),
        "variacion_velocidad": (_const_java(sim, "VARIACION_VELOCIDAD_CAMION"), "ParametrosSimulador.java"),
        "plan_inicial_fijo": (_const_java(sim, "USAR_PLAN_INICIAL_FIJO"), "ParametrosSimulador.java"),
        "umbral_n2_min": (_ms_a_min(_const_java(res, "UMBRAL_NIVEL2")), "ReschedulingAgent.java"),
        "delta_disparo_min": (_ms_a_min(_const_java(res, "DELTA_NUEVO_DISPARO")), "ReschedulingAgent.java"),
        "umbral_regen_indiv_min": (_ms_a_min(_const_java(res, "UMBRAL_REGEN_INDIVIDUAL")), "ReschedulingAgent.java"),
        "fraccion_flota_regen": (_const_java(res, "FRACCION_FLOTA_REGEN"), "ReschedulingAgent.java"),
        "cooldown_regen_sim_min": (_ms_a_min(_const_java(res, "COOLDOWN_REGEN_SIM_MS")), "ReschedulingAgent.java"),
        "max_regen_por_turno": (_const_java(res, "MAX_REGEN_POR_TURNO"), "ReschedulingAgent.java"),
    }
    parametros = {k: {"valor": v, "origen": "fuente_java", "de": de} for k, (v, de) in valores.items()}

    owl = Path(repo) / flota if flota else None
    if owl and owl.exists():
        t = owl.read_text(encoding="utf-8", errors="replace")
        parametros["flota_total"] = {"valor": len(re.findall(r"<\w+:Truck rdf:ID", t)), "origen": "owl", "de": flota}
        parametros["palas"] = {"valor": len(re.findall(r"<\w+:Shovel rdf:ID", t)), "origen": "owl", "de": flota}
    else:
        parametros["flota_total"] = {"valor": None, "origen": "owl", "de": None}
        parametros["palas"] = {"valor": None, "origen": "owl", "de": None}
    parametros["camiones_con_plan"] = {"valor": None, "origen": "fuente_java",
                                       "de": "no esta en las fuentes; lo da kpis_corridas.csv al terminar"}
    try:
        commit = subprocess.run(["git", "-C", str(repo), "rev-parse", "--short", "HEAD"],
                                capture_output=True, text=True, timeout=10).stdout.strip() or None
    except Exception:
        commit = None
    return {"version_esquema": VERSION_ESQUEMA, "generado": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "commit": commit, "parametros": parametros}


def manifest_reconstruido(etiqueta, d):
    """Parametros inferidos de los datos de la corrida. Sin valores por defecto:
    lo que no se puede reconstruir queda en null."""
    def rec(valor, de):
        return {"valor": valor, "origen": "reconstruido", "de": de if valor is not None else None}

    kpi, est = d["kpi"], d["estabilidad"]
    p = {"etiqueta": {"valor": etiqueta, "origen": "argumento", "de": "--etiqueta"}}

    prefijo = d["prefijo"]
    if prefijo:
        m = re.fullmatch(r"(.+?)(SinRL|ConRL)", prefijo)
        p["flota"] = rec(m.group(1) + ".owl" if m else None, "prefijo de archivo '%s'" % prefijo)
        p["aprendizaje"] = rec(m.group(2) == "ConRL" if m else None, "prefijo de archivo '%s'" % prefijo)
    else:
        p["flota"], p["aprendizaje"] = rec(None, None), rec(None, None)

    p["DEADLINE_MS"] = rec(d["deadline_ms"], "messages.csv: contenido de 'Iniciando negociacion'")
    p["turno_ms"] = rec(None, None)

    if est:
        p["metodo_rescheduling"] = rec(est.get("metodo"), "estabilidad.csv:metodo")
    elif d["liberaciones"]:
        p["metodo_rescheduling"] = rec("PARTIAL_REPAIR", "existen filas en liberaciones.csv")
    elif d["regeneraciones"]:
        p["metodo_rescheduling"] = rec("COMPLETE_REGENERATION", "existen filas en regeneraciones.csv")
    elif d["cascadas"]:
        p["metodo_rescheduling"] = rec("RIGHT_SHIFT_CASCADA", "existen filas en cascadas.csv")
    else:
        p["metodo_rescheduling"] = rec(None, None)
    p["rescheduling_activo"] = rec(None, None)

    p["incertidumbres"] = rec(kpi.get("incertidumbre") == "activa" if kpi else None, "kpis_corridas.csv:incertidumbre")
    vel = num(kpi.get("velocidad")) if kpi else (num(est.get("velocidad")) if est else None)
    p["velocidad"] = rec(vel, "kpis_corridas.csv:velocidad" if kpi else "estabilidad.csv:velocidad")
    p["semilla"] = rec(None, None)
    p["variacion_velocidad"] = rec(None, None)

    if kpi and kpi.get("motivo_arranque"):
        p["plan_inicial_fijo"] = rec(kpi["motivo_arranque"] == "plan_inicial_fijo", "kpis_corridas.csv:motivo_arranque")
    elif d["indice"]:
        p["plan_inicial_fijo"] = rec("plan_inicial_fijo" in d["indice"][0].get("motivo", ""), "indice.csv:motivo")
    else:
        p["plan_inicial_fijo"] = rec(None, None)

    lib, reg, cas = d["liberaciones"], d["regeneraciones"], d["cascadas"]
    if lib:
        p["umbral_n2_min"] = rec(num(lib[0]["umbral_min"]), "liberaciones.csv:umbral_min")
        p["delta_disparo_min"] = rec(num(lib[0]["delta_disparo_min"]), "liberaciones.csv:delta_disparo_min")
    elif cas:
        p["umbral_n2_min"] = rec(num(cas[0]["umbral_min"]), "cascadas.csv:umbral_min")
        p["delta_disparo_min"] = rec(num(cas[0]["delta_disparo_min"]), "cascadas.csv:delta_disparo_min")
    elif reg:
        p["umbral_n2_min"] = rec(num(reg[0]["umbral_n2_min"]), "regeneraciones.csv:umbral_n2_min")
        p["delta_disparo_min"] = rec(None, None)
    else:
        p["umbral_n2_min"], p["delta_disparo_min"] = rec(None, None), rec(None, None)
    if reg:
        p["umbral_regen_indiv_min"] = rec(num(reg[0]["umbral_regen_indiv_min"]), "regeneraciones.csv:umbral_regen_indiv_min")
        p["fraccion_flota_regen"] = rec(num(reg[0]["fraccion_flota"]), "regeneraciones.csv:fraccion_flota")
        p["cooldown_regen_sim_min"] = rec(num(reg[0]["cooldown_sim_min"]), "regeneraciones.csv:cooldown_sim_min")
    else:
        for k in ("umbral_regen_indiv_min", "fraccion_flota_regen", "cooldown_regen_sim_min"):
            p[k] = rec(None, None)
    p["max_regen_por_turno"] = rec(None, None)

    if kpi:
        p["flota_total"] = rec(num(kpi.get("flota_total")), "kpis_corridas.csv:flota_total")
        p["camiones_con_plan"] = rec(num(kpi.get("num_camiones")), "kpis_corridas.csv:num_camiones")
        p["palas"] = rec(num(kpi.get("palas_totales")), "kpis_corridas.csv:palas_totales")
    else:
        n_cam = d["agentes_por_tipo"].get("camion")
        n_pal = d["agentes_por_tipo"].get("pala")
        p["flota_total"] = rec(n_cam, "messages.csv: agentes Truck* distintos")
        p["camiones_con_plan"] = rec(None, None)
        p["palas"] = rec(n_pal, "messages.csv: agentes PA* distintos")
    return {"version_esquema": VERSION_ESQUEMA, "generado": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "commit": None, "parametros": p}


# ───────────────────────── episodios ─────────────────────────

def construir_episodios(lib, verif, reg, cas):
    """Episodios de reprogramacion ordenados por inicio real.
    PARTIAL_REPAIR: filas de liberaciones.csv escritas a < 5 s reales = 1 episodio.
    COMPLETE_REGENERATION: 1 fila de regeneraciones.csv = 1 episodio.
    RIGHT_SHIFT_CASCADA: 1 fila de cascadas.csv = 1 episodio."""
    eps = []
    grupo = []
    for f in sorted(lib, key=lambda r: r["timestamp"]):
        if grupo and (ts(f["timestamp"]) - ts(grupo[-1]["timestamp"])).total_seconds() > GAP_EPISODIO_S:
            eps.append(_ep_partial(grupo))
            grupo = []
        grupo.append(f)
    if grupo:
        eps.append(_ep_partial(grupo))
    for f in reg:
        eps.append({"metodo": "COMPLETE_REGENERATION", "inicio_real": f["timestamp"],
                    "motivo": f.get("motivo"), "t0_sim_ms": num(f.get("t0_ms")),
                    "camiones_en_umbral": num(f.get("camiones_en_umbral")),
                    "retraso_max_min": num(f.get("retraso_max_min")),
                    "camiones_liberados": num(f.get("camiones_liberados")),
                    "slots_liberados": num(f.get("slots_liberados")),
                    "palas_afectadas": num(f.get("palas_afectadas")),
                    "destinos_afectados": num(f.get("destinos_afectados")),
                    "computo_real_ms": num(f.get("duracion_ms"))})
    for f in cas:
        eps.append({"metodo": "RIGHT_SHIFT_CASCADA", "inicio_real": f["timestamp"],
                    "trigger": f.get("trigger"), "retraso_disparo_min": num(f.get("delta_inicial_min")),
                    "camiones_afectados": num(f.get("camiones_afectados")),
                    "slots_movidos": num(f.get("slots_movidos")), "iteraciones": num(f.get("iteraciones")),
                    "convergio": num(f.get("convergio")), "slots_perdidos": num(f.get("slots_perdidos")),
                    "computo_real_ms": num(f.get("duracion_ms"))})
    eps.sort(key=lambda e: e["inicio_real"])
    for i, e in enumerate(eps, 1):
        e["n"] = i
    # verificacion_partial_repair.csv: cada fila cae en el episodio cuya ventana real la contiene
    inicios = [e["inicio_real"] for e in eps]
    por_ep = defaultdict(list)
    sin_ep = 0
    for v in verif:
        i = bisect.bisect_right(inicios, v["timestamp"]) - 1
        if i < 0:
            sin_ep += 1
        else:
            por_ep[i].append(v)
    for i, e in enumerate(eps):
        if e["metodo"] != "PARTIAL_REPAIR":
            continue
        vs = por_ep.get(i, [])
        dts = [num(v["dt_real_resolucion_ms"]) for v in vs]
        e["resolucion"] = {"resueltos": len(vs),
                           "huerfanos": sum(1 for v in vs if v.get("huerfano") == "true"),
                           "dt_real_ms_media": redondear(statistics.mean(dts), 0) if dts else None,
                           "dt_real_ms_max": max(dts) if dts else None}
    return eps, sin_ep


def _ep_partial(grupo):
    return {"metodo": "PARTIAL_REPAIR", "inicio_real": grupo[0]["timestamp"],
            "trigger": grupo[0]["trigger"], "trigger_de": "primera fila del grupo en liberaciones.csv",
            "retraso_disparo_min": num(grupo[0]["delta_inicial_min"]),
            "camiones_liberados": len(grupo),
            "slots_liberados": sum(num(g["slots_liberados"]) or 0 for g in grupo),
            "corte_sim_ms_min": min(num(g["corte_ms"]) for g in grupo),
            "corte_sim_ms_max": max(num(g["corte_ms"]) for g in grupo)}


# ───────────────────────── fases ─────────────────────────

class Fases:
    """Asigna cada fila a 'inicial', 'episodio_k' o 'final'.
    Con t_sim_ms: compara hora simulada. Sin ella: ventanas de tiempo real."""

    def __init__(self, inicios, corte_final, modo):
        self.inicios = inicios          # clave de inicio de cada episodio (ordenada)
        self.corte_final = corte_final  # clave de la ultima negociacion cerrada del ultimo episodio (o None)
        self.modo = modo                # "simulado" | "aproximado"

    def de(self, clave):
        if not self.inicios:
            return "inicial"
        k = bisect.bisect_right(self.inicios, clave)
        if k == 0:
            return "inicial"
        if k == len(self.inicios) and self.corte_final is not None and clave > self.corte_final:
            return "final"
        return "episodio_%d" % k


def orden_fases(n_ep):
    return ["inicial"] + ["episodio_%d" % i for i in range(1, n_ep + 1)] + ["final"]


# ───────────────────────── mensajes ─────────────────────────

def primera_pasada_mensajes(ruta, inicios_reales):
    """Para el modo simulado: t_sim de la primera fila escrita en o despues del inicio
    real de cada episodio. Tambien detecta si existe la columna t_sim_ms."""
    tiene_sim = False
    claves = [None] * len(inicios_reales)
    pendiente = 0
    with open(ruta, newline="", encoding="utf-8", errors="replace") as f:
        lector = csv.reader(f)
        cab = [c.strip() for c in next(lector, [])]
        tiene_sim = len(cab) >= 9 and cab[8] == "t_sim_ms"
        if not tiene_sim or not inicios_reales:
            return tiene_sim, claves
        for fila in lector:
            while pendiente < len(inicios_reales) and fila[0] >= inicios_reales[pendiente]:
                claves[pendiente] = int(fila[8])
                pendiente += 1
            if pendiente >= len(inicios_reales):
                break
    return tiene_sim, claves


def procesar_mensajes(ruta, fases, tiene_sim):
    perf = defaultdict(lambda: [0, 0])           # performativa -> [send, receive]
    agente = defaultdict(lambda: [0, 0, 0])      # agente -> [send, receive, interno]
    internos = Counter()
    motivos_refuse = Counter()
    por_fase = defaultdict(lambda: {"n": 0, "send": Counter(), "min": None, "max": None, "smin": None, "smax": None})
    obs = defaultdict(lambda: [0, 0])            # camion -> [n, acum_max_ms]
    resumenes_pr = []                            # filas resumen PARTIAL_REPAIR
    deadline = None
    total = 0
    ts_min = ts_max = None
    with open(ruta, newline="", encoding="utf-8", errors="replace") as f:
        lector = csv.reader(f)
        next(lector, None)
        for fila in lector:
            if len(fila) < 8:
                continue
            total += 1
            t, ag, accion, _sender, receptores, pf, _conv, contenido = fila[:8]
            tsim = int(fila[8]) if tiene_sim and len(fila) > 8 else None
            if ts_min is None:
                ts_min = t
            ts_max = t
            if accion == "SEND" or accion.startswith("SEND "):
                perf[pf][0] += 1
                agente[ag][0] += 1
                direccion = 0
                if pf == "REFUSE":
                    motivos_refuse[contenido.rsplit(": ", 1)[-1]] += 1
            elif accion == "RECEIVE":
                perf[pf][1] += 1
                agente[ag][1] += 1
                direccion = 1
            else:
                tipo = tipo_evento_interno(accion)
                internos[tipo] += 1
                agente[ag][2] += 1
                direccion = 2
                if tipo == "Iniciando negociacion" and deadline is None:
                    m = re.search(r"\((\d+) ms\)", contenido)
                    if m:
                        deadline = int(m.group(1))
                elif tipo == "RESCHEDULE_OMITIDO":
                    m = re.search(r"acum=(\d+:\d+:\d+)", contenido)
                    o = obs[receptores]
                    o[0] += 1
                    if m:
                        o[1] = max(o[1], hms_a_ms(m.group(1)) or 0)
                elif tipo == "PARTIAL_REPAIR":
                    resumenes_pr.append((t, receptores, contenido))
            fase = por_fase[fases.de(tsim if fases.modo == "simulado" else t)]
            fase["n"] += 1
            if direccion == 0:
                fase["send"][pf] += 1
            if fase["min"] is None:
                fase["min"] = t
            fase["max"] = t
            if tsim is not None and tsim >= 0:
                if fase["smin"] is None:
                    fase["smin"] = tsim
                fase["smax"] = tsim
    return {"total": total, "perf": perf, "agente": agente, "internos": internos,
            "motivos_refuse": motivos_refuse, "por_fase": por_fase, "obs": obs,
            "resumenes_pr": resumenes_pr, "deadline": deadline, "ts_min": ts_min, "ts_max": ts_max}


def conteo_directo_mensajes(ruta):
    """Conteo independiente (sin el modulo csv ni la logica de agregacion) para la
    verificacion cruzada: total de filas y filas por valor de la columna performative."""
    total = 0
    por_perf = Counter()
    with open(ruta, "rb") as f:
        f.readline()
        for linea in f:
            if not linea.strip():
                continue
            total += 1
            campos = linea.split(b",")
            accion = campos[2]
            if accion == b"RECEIVE" or accion == b"SEND" or accion.startswith(b"SEND "):
                por_perf[campos[5].decode("utf-8", "replace")] += 1
    return total, por_perf


# ───────────────────────── negociaciones ─────────────────────────

def leer_negociaciones(ruta):
    """Lee negotiations.csv POR POSICION, ignorando el encabezado: el archivo rotula
    las columnas 9 y 10 como 'refusesRecibidas, proposesRecibidas', pero el codigo
    (CsvNegotiationsLogger) escribe numProposes y luego numRefuses."""
    filas = []
    if not ruta or not Path(ruta).exists():
        return filas, False
    with open(ruta, newline="", encoding="utf-8", errors="replace") as f:
        lector = csv.reader(f)
        cab = [c.strip() for c in next(lector, [])]
        tiene_sim = len(cab) >= 13 and cab[12] == "t_sim_ms"
        for r in lector:
            if len(r) < 12:
                continue
            filas.append({"ts": r[0], "pala": r[1], "motivo": r[5].strip(), "dur": int(r[6]),
                          "ganador": None if r[7].strip() == "null" else r[7].strip(),
                          "proposes": int(r[8]), "refuses": int(r[9]),
                          "informs": int(r[10]), "cancels": int(r[11]),
                          "tsim": int(r[12]) if tiene_sim and len(r) > 12 else None})
    return filas, tiene_sim


def resumen_negociaciones(filas):
    if not filas:
        return {"total": 0}
    durs = [f["dur"] for f in filas]
    con_ganador = [f for f in filas if f["informs"] > 0 and f["ganador"]]
    return {"total": len(filas),
            "con_ganador": len(con_ganador),
            "tasa_exito_pct": redondear(100 * len(con_ganador) / len(filas), 2),
            "por_motivo_cierre": dict(Counter(f["motivo"] for f in filas).most_common()),
            "proposes_total": sum(f["proposes"] for f in filas),
            "refuses_total": sum(f["refuses"] for f in filas),
            "proposes_por_negociacion": {str(k): v for k, v in sorted(Counter(f["proposes"] for f in filas).items())},
            "duracion_real_ms": {"media": redondear(statistics.mean(durs), 0), "mediana": statistics.median(durs),
                                 "min": min(durs), "max": max(durs), "suma": sum(durs)}}


# ───────────────────────── armado del JSON ─────────────────────────

def procesar(args):
    datos = Path(args.datos).resolve()
    et = args.etiqueta
    dir_corrida = datos / "corridas" / et
    salida = Path(args.salida).resolve() if args.salida else dir_corrida
    advertencias = []

    # --- KPIs y descarte de etiquetas repetidas ---
    filas_kpi = de_corrida(leer_csv(datos / "kpis_corridas.csv"), et)
    if len(filas_kpi) > 1:
        sys.exit("DESCARTADA: la etiqueta '%s' tiene %d filas en kpis_corridas.csv. "
                 "Las etiquetas repetidas no se desenredan." % (et, len(filas_kpi)))
    kpi = filas_kpi[0] if filas_kpi else None
    filas_est = de_corrida(leer_csv(datos / "estabilidad.csv"), et)
    est = filas_est[-1] if filas_est else None

    # --- archivos por corrida ---
    reg_dir = dir_corrida / "registros_negociacion"
    def buscar(sufijo, explicito):
        if explicito:
            return Path(explicito)
        c = sorted(reg_dir.glob("*" + sufijo)) if reg_dir.exists() else []
        return c[0] if c else None
    ruta_msg = buscar("messages.csv", args.mensajes)
    ruta_neg = buscar("negotiations.csv", args.negociaciones)
    prefijo = None
    for r, suf in ((ruta_msg, "messages.csv"), (ruta_neg, "negotiations.csv")):
        if r is not None and r.name.endswith(suf):
            prefijo = r.name[:-len(suf)]
            break
    if ruta_msg is None or not ruta_msg.exists():
        advertencias.append("sin messages.csv")
        ruta_msg = None

    lib = de_corrida(leer_csv(datos / "liberaciones.csv"), et)
    verif = de_corrida(leer_csv(datos / "verificacion_partial_repair.csv"), et)
    reg = de_corrida(leer_csv(datos / "regeneraciones.csv"), et)
    cas = de_corrida(leer_csv(datos / "cascadas.csv"), et)
    eventos = leer_csv(dir_corrida / "eventos.csv")
    indice = leer_csv(dir_corrida / "schedules_por_reprogramacion" / "indice.csv")
    palas_util = de_corrida(leer_csv(datos / "utilizacion_palas.csv"), et)
    solapes = de_corrida(leer_csv(datos / "solapes_pala.csv"), et)

    completa = kpi is not None and (dir_corrida / "plan_final.csv").exists()
    if kpi is None:
        advertencias.append("sin fila en kpis_corridas.csv: corrida incompleta, KPIs no disponibles")
    if not eventos:
        advertencias.append("sin eventos.csv")

    # --- episodios y fases ---
    episodios, verif_sin_ep = construir_episodios(lib, verif, reg, cas)
    # Los INFORM de liberacion salen unos ms ANTES de que se escriba la fila de
    # liberaciones.csv: se adelanta el borde 1 s real para que caigan en su episodio.
    inicios_reales = [(ts(e["inicio_real"]) - timedelta(seconds=MARGEN_INICIO_S)).strftime(FMT_TS)[:-3]
                      for e in episodios]
    negs, neg_sim = leer_negociaciones(ruta_neg)
    if ruta_neg is None or not negs:
        advertencias.append("sin negotiations.csv (no hubo negociaciones registradas)")

    tiene_sim, claves_sim = (False, [])
    if ruta_msg:
        tiene_sim, claves_sim = primera_pasada_mensajes(ruta_msg, inicios_reales)
    modo = "simulado" if tiene_sim and (not negs or neg_sim) and all(c is not None for c in claves_sim) else "aproximado"
    if modo == "simulado":
        inicios = claves_sim
        clave_neg = lambda f: f["tsim"]
    else:
        inicios = inicios_reales
        clave_neg = lambda f: f["ts"]
    corte_final = None
    if episodios and negs:
        tras_ultimo = [clave_neg(f) for f in negs if clave_neg(f) >= inicios[-1]]
        corte_final = max(tras_ultimo) if tras_ultimo else None
    fases = Fases(inicios, corte_final, modo)

    # --- mensajes ---
    M = procesar_mensajes(ruta_msg, fases, tiene_sim) if ruta_msg else None

    # --- negociaciones por fase / pala / episodio ---
    neg_por_fase = defaultdict(list)
    for f in negs:
        neg_por_fase[fases.de(clave_neg(f))].append(f)
    neg_por_pala = defaultdict(list)
    for f in negs:
        neg_por_pala[f["pala"]].append(f)

    # --- resumen PARTIAL_REPAIR de messages.csv -> episodio ---
    if M:
        for t, receptores, contenido in M["resumenes_pr"]:
            i = bisect.bisect_right(inicios_reales, t) - 1
            if i < 0 or episodios[i]["metodo"] != "PARTIAL_REPAIR":
                continue
            e = episodios[i]
            m = re.search(r"trigger=(\S+)", receptores)
            if m:
                e["trigger"], e["trigger_de"] = m.group(1), "fila resumen PARTIAL_REPAIR de messages.csv"
            m = re.search(r"palas=\[([^\]]*)\]", receptores)
            if m:
                e["palas"] = [x.strip() for x in m.group(1).split(";") if x.strip()] or [m.group(1)]
            m = re.search(r"destinos=\[([^\]]*)\]", receptores)
            if m:
                e["destinos"] = [x.strip() for x in m.group(1).split(";") if x.strip()] or [m.group(1)]
            m = re.search(r"indirectos=(\d+)", contenido)
            if m:
                e["camiones_indirectos"] = int(m.group(1))

    # --- reloj simulado y actividad de cada episodio ---
    for i, e in enumerate(episodios):
        nombre = "episodio_%d" % (i + 1)
        if modo == "simulado":
            e["reloj_sim_ms"], e["reloj_de"] = claves_sim[i], "t_sim_ms"
        elif i + 1 < len(indice):
            e["reloj_sim_ms"], e["reloj_de"] = num(indice[i + 1]["reloj_ms"]), "indice.csv (aproximado)"
        else:
            e["reloj_sim_ms"], e["reloj_de"] = None, None
        ns = neg_por_fase.get(nombre, [])
        e["negociaciones"] = len(ns)
        e["negociaciones_con_ganador"] = sum(1 for f in ns if f["informs"] > 0 and f["ganador"])
        if M and nombre in M["por_fase"]:
            pf = M["por_fase"][nombre]
            e["mensajes"] = pf["n"]
            e["duracion_real_s"] = redondear((ts(pf["max"]) - ts(pf["min"])).total_seconds(), 1)

    # --- JSON ---
    d_manifest = {"kpi": kpi, "estabilidad": est, "prefijo": prefijo, "liberaciones": lib,
                  "regeneraciones": reg, "cascadas": cas, "indice": indice,
                  "deadline_ms": M["deadline"] if M else None,
                  "agentes_por_tipo": Counter(tipo_agente(a) for a in M["agente"]) if M else {}}
    ruta_manifest = dir_corrida / "manifest.json"
    if ruta_manifest.exists():
        manifest = json.loads(ruta_manifest.read_text(encoding="utf-8"))
        origen_manifest = "manifest.json"
    else:
        manifest = manifest_reconstruido(et, d_manifest)
        origen_manifest = "reconstruido"
        salida.mkdir(parents=True, exist_ok=True)
        (salida / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    P = manifest["parametros"]
    if any(v["origen"] == "reconstruido" for v in P.values()):
        origen_manifest = "reconstruido"
    else:
        origen_manifest = "fuente_java"

    reg_json = {
        "version_esquema": VERSION_ESQUEMA,
        "corrida": {"etiqueta": et, "completa": completa,
                    "inicio_real": M["ts_min"] if M else None,
                    "fin_real": (kpi["timestamp"] if kpi else (M["ts_max"] if M else None)),
                    "advertencias": advertencias},
        "parametros": {k: v["valor"] for k, v in P.items()},
        "parametros_origen": {"manifest": origen_manifest,
                              "reconstruidos": {k: v["de"] for k, v in P.items()
                                                if v["origen"] == "reconstruido" and v["valor"] is not None},
                              "nulos": [k for k, v in P.items() if v["valor"] is None]},
    }

    # KPIs (solo si la corrida termino)
    if kpi:
        omitir = {"timestamp", "escenario", "makespan_hhmmss", "makespan_real_hhmmss"}
        k = {c: num(v) for c, v in kpi.items() if c not in omitir}
        if est:
            k["estabilidad"] = {c: num(v) for c, v in est.items()
                                if c not in ("timestamp", "escenario", "metodo", "velocidad")}
        if palas_util:
            k["palas_cols"] = ["cargas", "toneladas", "utilizacion_pct", "cola_max", "espera_media_min"]
            k["palas"] = {p["pala"]: [num(p[c]) for c in k["palas_cols"]] for p in palas_util}
        if len(solapes) == 1:
            k["solapes_pala"] = {c: num(v) for c, v in solapes[0].items() if c != "escenario"}
        elif len(solapes) > 1:
            advertencias.append("solapes_pala.csv tiene %d filas para la etiqueta: se omite" % len(solapes))
        reg_json["kpis"] = k
    else:
        reg_json["kpis"] = None

    reg_json["eventos_cols"] = ["tipo", "objetivo", "inicio_ms", "fin_ms", "factor_velocidad"]
    reg_json["eventos"] = [[num(e[c]) for c in reg_json["eventos_cols"]] for e in eventos]
    reg_json["planes"] = [{"n": num(i["numero"]), "motivo": i["motivo"], "reloj_ms": num(i["reloj_ms"]),
                           "operaciones": num(i["operaciones"])} for i in indice]

    # fases
    notas_fase = ("inicial = antes del primer episodio; episodio_k = desde su disparo hasta el siguiente; "
                  "final = despues de la ultima negociacion cerrada del ultimo episodio.")
    if any(e["metodo"] == "COMPLETE_REGENERATION" for e in episodios):
        notas_fase += (" En COMPLETE_REGENERATION la delimitacion es aproximada: no existe un registro "
                       "de resolucion por camion que marque el cierre del episodio.")
    reg_json["fases"] = {"tiempo": modo, "nota": notas_fase}

    # comunicacion
    if M:
        tipos = defaultdict(lambda: [0, 0, 0])
        for a, c in M["agente"].items():
            for i in range(3):
                tipos[tipo_agente(a)][i] += c[i]
        fases_json = []
        for nombre in orden_fases(len(episodios)):
            if nombre not in M["por_fase"]:
                continue
            pf = M["por_fase"][nombre]
            ns = neg_por_fase.get(nombre, [])
            fila = {"fase": nombre, "desde_real": pf["min"], "hasta_real": pf["max"],
                    "mensajes": pf["n"], "send": dict(pf["send"].most_common()),
                    "negociaciones": len(ns),
                    "negociaciones_con_ganador": sum(1 for f in ns if f["informs"] > 0 and f["ganador"])}
            if tiene_sim:
                fila["desde_sim_ms"], fila["hasta_sim_ms"] = pf["smin"], pf["smax"]
            fases_json.append(fila)
        reg_json["comunicacion"] = {
            "total_filas": M["total"],
            "nota": "send/receive = filas SEND y RECEIVE de messages.csv; un CFP es 1 fila SEND "
                    "y N filas RECEIVE. interno = filas de traza que no son mensajes ACL.",
            "por_performativa_cols": ["send", "receive"],
            "por_performativa": {p: c for p, c in sorted(M["perf"].items(), key=lambda x: -sum(x[1]))},
            "internos": dict(M["internos"].most_common()),
            "por_tipo_agente_cols": ["send", "receive", "interno"],
            "por_tipo_agente": dict(tipos),
            "por_agente": {a: M["agente"][a] for a in sorted(M["agente"], key=lambda x: (tipo_agente(x), len(x), x))},
            "motivos_refuse": dict(M["motivos_refuse"].most_common()),
            "por_fase": fases_json,
        }
        if M["obs"]:
            reg_json["observaciones_sin_reprogramar"] = {
                "total": sum(o[0] for o in M["obs"].values()),
                "camiones": len(M["obs"]),
                "retraso_acum_max_min": redondear(max(o[1] for o in M["obs"].values()) / 60000, 1),
                "por_camion_cols": ["n", "retraso_acum_max_min"],
                "por_camion": {c: [o[0], redondear(o[1] / 60000, 1)]
                               for c, o in sorted(M["obs"].items(), key=lambda x: (len(x[0]), x[0]))}}
    else:
        reg_json["comunicacion"] = None

    # negociaciones
    rn = resumen_negociaciones(negs)
    if negs:
        rn["nota"] = ("proposes/refuses leidos por posicion: el encabezado del CSV los rotula invertidos.")
        rn["por_pala_cols"] = ["total", "con_ganador", "proposes"]
        rn["por_pala"] = {p: [len(fs), sum(1 for f in fs if f["informs"] > 0 and f["ganador"]),
                              sum(f["proposes"] for f in fs)] for p, fs in sorted(neg_por_pala.items())}
        rn["ganadores"] = dict(Counter(f["ganador"] for f in negs if f["informs"] > 0 and f["ganador"]).most_common())
    reg_json["negociaciones"] = rn
    reg_json["episodios"] = episodios

    # --- verificacion cruzada: totales del JSON contra conteos directos de los CSV ---
    chequeos = []
    def chequear(nombre, en_json, en_csv):
        chequeos.append([nombre, en_json, en_csv, en_json == en_csv])
    if M:
        total_d, perf_d = conteo_directo_mensajes(ruta_msg)
        c = reg_json["comunicacion"]
        chequear("messages: filas", c["total_filas"], total_d)
        chequear("messages: send+receive+interno", sum(sum(v) for v in c["por_agente"].values()), total_d)
        chequear("messages: suma por_fase", sum(f["mensajes"] for f in c["por_fase"]), total_d)
        chequear("messages: filas SEND/RECEIVE por performativa",
                 {p: sum(v) for p, v in c["por_performativa"].items()}, dict(perf_d))
        chequear("messages: suma internos", sum(c["internos"].values()), total_d - sum(perf_d.values()))
    if negs:
        with open(ruta_neg, "rb") as f:
            lineas_neg = sum(1 for l in f if l.strip()) - 1
        chequear("negotiations: filas", rn["total"], lineas_neg)
        chequear("negotiations: suma por_fase", sum(len(v) for v in neg_por_fase.values()), lineas_neg)
        chequear("negotiations: suma por_pala", sum(v[0] for v in rn["por_pala"].values()), lineas_neg)
        if M:
            chequear("negotiations.proposes vs messages RECEIVE PROPOSE", rn["proposes_total"], M["perf"]["PROPOSE"][1])
            chequear("negotiations.refuses vs messages RECEIVE REFUSE", rn["refuses_total"], M["perf"]["REFUSE"][1])
            chequear("negotiations: filas vs messages 'Iniciando negociacion' ya cerradas",
                     rn["total"] <= M["internos"].get("Iniciando negociacion", 0), True)
    pr = [e for e in episodios if e["metodo"] == "PARTIAL_REPAIR"]
    if lib or verif:
        chequear("liberaciones: filas", sum(e["camiones_liberados"] for e in pr), len(lib))
        chequear("verificacion_partial_repair: filas",
                 sum(e["resolucion"]["resueltos"] for e in pr) + verif_sin_ep, len(verif))
    if reg:
        chequear("regeneraciones: filas", sum(1 for e in episodios if e["metodo"] == "COMPLETE_REGENERATION"), len(reg))
    if cas:
        chequear("cascadas: filas", sum(1 for e in episodios if e["metodo"] == "RIGHT_SHIFT_CASCADA"), len(cas))
    if eventos:
        chequear("eventos: filas", len(reg_json["eventos"]), len(eventos))
    if kpi and est:
        chequear("kpis.slots_plan_inicial vs estabilidad.slots_base", num(kpi.get("slots_plan_inicial")), num(est.get("slots_base")))
    todo_ok = all(c[3] for c in chequeos)
    reg_json["verificacion"] = {"ok": todo_ok, "cols": ["chequeo", "json", "csv", "ok"],
                                "chequeos": chequeos if not todo_ok else [[c[0], c[3]] for c in chequeos]}
    if todo_ok:
        reg_json["verificacion"]["cols"] = ["chequeo", "ok"]

    salida.mkdir(parents=True, exist_ok=True)
    texto = json.dumps(reg_json, ensure_ascii=False, separators=(",", ":"),
                       indent=(1 if args.legible else None))
    destino = salida / "registro_llm.json"
    destino.write_text(texto, encoding="utf-8")

    print("%s -> %s" % (et, destino))
    print("  completa=%s  fases=%s  episodios=%d  mensajes=%s  negociaciones=%d"
          % (completa, modo, len(episodios), M["total"] if M else 0, len(negs)))
    print("  manifest: %s" % origen_manifest)
    print("  tamano: %d caracteres" % len(texto))
    for adv in advertencias:
        print("  aviso: " + adv)
    for c in chequeos:
        print("  [%s] %s" % ("OK" if c[3] else "FALLA", c[0]) + ("" if c[3] else "  json=%s csv=%s" % (c[1], c[2])))
    return 0 if todo_ok else 1


def cmd_manifest(args):
    repo = Path(args.repo).resolve()
    m = manifest_desde_java(repo)
    et = m["parametros"]["etiqueta"]["valor"]
    if not et:
        sys.exit("No se pudo leer ETIQUETA_ESCENARIO de src/ParametrosSimulador.java")
    destino = repo / "corridas" / et / "manifest.json"
    if destino.exists() and not args.reemplazar:
        sys.exit("Ya existe %s: la etiqueta debe ser unica por corrida (use --reemplazar si es la misma corrida)." % destino)
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(json.dumps(m, ensure_ascii=False, indent=1), encoding="utf-8")
    print("manifest -> %s" % destino)
    nulos = [k for k, v in m["parametros"].items() if v["valor"] is None]
    if nulos:
        print("  en null: " + ", ".join(nulos))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("manifest", help="escribe corridas/<etiqueta>/manifest.json desde src/*.java")
    a.add_argument("--repo", default=str(Path(__file__).resolve().parent))
    a.add_argument("--reemplazar", action="store_true")
    a.set_defaults(func=cmd_manifest)
    b = sub.add_parser("procesar", help="escribe corridas/<etiqueta>/registro_llm.json")
    b.add_argument("--etiqueta", required=True)
    b.add_argument("--datos", default=str(Path(__file__).resolve().parent),
                   help="carpeta con los CSV acumulativos y corridas/ (por defecto, la del script)")
    b.add_argument("--salida", help="carpeta de salida (por defecto <datos>/corridas/<etiqueta>)")
    b.add_argument("--mensajes", help="ruta a messages.csv si no esta en corridas/<etiqueta>/registros_negociacion")
    b.add_argument("--negociaciones", help="ruta a negotiations.csv, idem")
    b.add_argument("--legible", action="store_true", help="JSON con sangria (mas grande)")
    b.set_defaults(func=procesar)
    args = ap.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
