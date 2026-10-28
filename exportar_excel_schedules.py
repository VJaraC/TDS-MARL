# -*- coding: utf-8 -*-
"""
Genera los 5 Excel de schedules para el profesor, a partir de las carpetas
corridas/<etiqueta>/ que escribe cada simulacion.

  1_plan_sin_incertidumbre.xlsx   el plan original, tal como salio de la negociacion
                                  (sin incertidumbre la ejecucion es identica al plan)
  2_plan_con_incertidumbre.xlsx   ejecucion real sin reprogramacion
  3_right_shift.xlsx              ejecucion real con right-shift en cascada
  4_reparacion_parcial.xlsx       ejecucion real con reparacion parcial
  5_regeneracion_completa.xlsx    ejecucion real con regeneracion completa

Cada Excel trae: Resumen, Camiones, Palas, Destinos, Gantt (minuto a minuto,
0-6 h), Perturbaciones y Problemas (lo que detecta verificar_schedules.py).

Uso:
  python exportar_excel_schedules.py --con mediano_NINGUNO_r1 --rs mediano_CASCADA_r1 ^
         --partial mediano_PARTIAL_r1 --complete mediano_COMPLETE_r1

  --sin   corrida de donde sale el plan sin incertidumbre (por defecto, la de --con)
  --salida carpeta de destino (por defecto entrega_profesor/)
"""
import argparse
import csv
import os
import sys
from collections import defaultdict

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

import verificar_schedules as vs

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.join(AQUI, "corridas")
MIN = 60000
HORIZ = 360            # minutos que muestra el Gantt (turno de 6 h)
TOL = vs.TOL

# Colores: primeros cuatro slots de la paleta categorica de referencia (validados
# para pares adyacentes, que es como se suceden las operaciones en una fila).
# El rojo "critico" queda reservado para problemas y siempre va con un numero.
COLOR = {
    "VIAJE_VACIO":   "2A78D6",   # azul
    "CARGA":         "EB6834",   # naranjo
    "VIAJE_CARGADO": "1BAF7A",   # aqua
    "DESCARGA":      "EDA100",   # amarillo
    "ESPERA":        "C9C8C3",   # gris, con trama
    "FALLA":         "52514E",   # gris oscuro
    "PROBLEMA":      "D03B3B",   # rojo critico (con numero)
    "CLIMA":         "E3E2DE",
}
NOMBRE_OP = {"VIAJE_VACIO": "Viaje vacío", "CARGA": "Carga", "VIAJE_CARGADO": "Viaje cargado",
             "DESCARGA": "Descarga", "ESPERA": "Espera en cola", "FALLA": "Equipo en falla",
             "PROBLEMA": "Más operaciones que capacidad (el número indica cuántas)"}
FILL = {k: PatternFill("solid", fgColor=v) for k, v in COLOR.items()}
FILL["ESPERA"] = PatternFill("lightUp", fgColor="8A8983", bgColor="E3E2DE")
FILL_HEAD = PatternFill("solid", fgColor="EEF2F6")
BOLD = Font(bold=True)
TITULO = Font(bold=True, size=14)
BLANCO = Font(color="FFFFFF", bold=True, size=8)
CHICO = Font(size=8)
FINO = Border(left=Side(style="thin", color="FFFFFF"))

TITULOS = {
    "sin": "Plan sin incertidumbre",
    "con": "Plan con incertidumbre (sin reprogramación)",
    "rs": "Right-shift en cascada",
    "partial": "Reparación parcial",
    "complete": "Regeneración completa",
}
ARCHIVOS = {
    "sin": "1_plan_sin_incertidumbre.xlsx",
    "con": "2_plan_con_incertidumbre.xlsx",
    "rs": "3_right_shift.xlsx",
    "partial": "4_reparacion_parcial.xlsx",
    "complete": "5_regeneracion_completa.xlsx",
}


def hms(ms):
    return vs.hms(ms)


def fnum(x, d=0.0):
    try:
        return float(x)
    except (TypeError, ValueError):
        return d


# ── Carga de datos ──────────────────────────────────────────────────────────
def operaciones(carpeta, clave):
    """Lista de operaciones de camion: dicts con plan y real. Para el plan sin
    incertidumbre, la ejecucion es el plan mismo."""
    if clave == "sin":
        filas = vs.leer(carpeta, "plan_original.csv")
        mat = {}
        if filas and "material" not in filas[0]:
            for r in vs.leer(carpeta, "plan_final.csv"):
                mat[(r["camion"], r["operacion"], r["inicio_ms"])] = r.get("material")
        ops = []
        for r in filas:
            ip, fp = int(r["inicio_ms"]), int(r["fin_ms"])
            m = r.get("material", mat.get((r["camion"], r["operacion"], r["inicio_ms"]), 0))
            ops.append(dict(camion=r["camion"], idx=int(r["idx"]), op=r["operacion"], maquina=r["maquina"],
                            ubic=r["ubicacion"], ip=ip, fp=fp, ir=ip, fr=fp, material=fnum(m)))
        return ops
    ops = []
    for r in vs.leer(carpeta, "ejecucion_real.csv"):
        ops.append(dict(camion=r["camion"], idx=int(r["idx"]), op=r["operacion"], maquina=r["maquina"],
                        ubic=r["ubicacion"], ip=int(r["inicio_plan_ms"]), fp=int(r["fin_plan_ms"]),
                        ir=int(r["inicio_real_ms"]), fr=int(r["fin_real_ms"]), material=fnum(r["material"])))
    return ops


def con_esperas(ops):
    """Agrega a cada operacion la espera previa (llego al recurso y no la atienden)."""
    por = defaultdict(list)
    for o in ops:
        por[o["camion"]].append(o)
    for lst in por.values():
        lst.sort(key=lambda o: (o["ir"], o["idx"]))
        prev = None
        for o in lst:
            o["espera"] = 0
            o["llegada"] = o["ir"]
            if prev is not None and o["op"] in ("CARGA", "DESCARGA") and prev["op"] in vs.VIAJES:
                o["llegada"] = prev["fr"]
                o["espera"] = max(0, o["ir"] - prev["fr"])
            prev = o
    return por


# ── Hojas ───────────────────────────────────────────────────────────────────
def ancho(ws, anchos):
    for i, w in enumerate(anchos, 1):
        ws.column_dimensions[get_column_letter(i)].width = w


def encabezado(ws, fila, cols):
    for j, c in enumerate(cols, 1):
        cel = ws.cell(row=fila, column=j, value=c)
        cel.font = BOLD
        cel.fill = FILL_HEAD
        cel.alignment = Alignment(wrap_text=True, vertical="center")
    ws.freeze_panes = ws.cell(row=fila + 1, column=1)


def hoja_resumen(ws, clave, etiqueta, carpeta, ops, informe, recursos):
    ws.title = "Resumen"
    ws["A1"] = TITULOS[clave]
    ws["A1"].font = TITULO
    plan_ini = vs.leer(carpeta, "plan_original.csv")
    plan_fin = vs.leer(carpeta, "plan_final.csv")
    fin_turno = vs.FIN_TURNO
    ton = sum(o["material"] for o in ops if o["op"] == "DESCARGA" and o["fr"] <= fin_turno)
    cargas = sum(1 for o in ops if o["op"] == "CARGA" and o["ir"] < fin_turno)
    esp_p = sum(o.get("espera", 0) for o in ops if o["op"] == "CARGA") / MIN
    esp_d = sum(o.get("espera", 0) for o in ops if o["op"] == "DESCARGA") / MIN
    filas = [
        ("Corrida de origen", etiqueta),
        ("Qué muestra", "El plan original tal como salió de la negociación. Sin incertidumbre, "
                        "la ejecución es idéntica al plan." if clave == "sin" else
                        "La ejecución real del turno bajo las perturbaciones, con el método indicado."),
        ("Camiones con plan", len({r["camion"] for r in plan_ini})),
        ("Operaciones del plan original", len(plan_ini)),
        ("Operaciones del plan final", len(plan_ini) if clave == "sin" else len(plan_fin)),
        ("Cargas iniciadas en el turno (6 h)", cargas),
        ("Toneladas descargadas en el turno (6 h)", round(ton)),
        ("Espera total de camiones en palas (min)", round(esp_p)),
        ("Espera total de camiones en descarga (min)", round(esp_d)),
        ("Palas / destinos", "%d palas, %d destinos" % (
            sum(1 for r in recursos if r["tipo"] == "PALA"),
            sum(1 for r in recursos if r["tipo"] == "DESTINO"))),
    ]
    for i, (k, v) in enumerate(filas, 3):
        ws.cell(row=i, column=1, value=k).font = BOLD
        ws.cell(row=i, column=2, value=v).alignment = Alignment(wrap_text=True, vertical="top", horizontal="left")
    f = len(filas) + 4
    ws.cell(row=f, column=1, value="Verificación de las agendas").font = TITULO
    chequeos = [
        ("1a", "Un camión hace dos cosas a la vez (plan del simulador)"),
        ("1b", "Un camión hace dos cosas a la vez (agenda del TruckAgent)"),
        ("1c", "Un camión hace dos cosas a la vez (ejecución real)"),
        ("1d", "Una pala carga a dos camiones a la vez (agenda de la pala)"),
        ("1e", "Una pala carga a dos camiones a la vez (ejecución real)"),
        ("1f", "Un destino atiende más camiones que su capacidad (agenda)"),
        ("1g", "Un destino atiende más camiones que su capacidad (ejecución real)"),
        ("1h", "Un camión carga en la ubicación de otra pala"),
        ("3a_falta", "Operaciones del TruckAgent que el simulador no tiene"),
        ("3a_sobra", "Operaciones del simulador que el TruckAgent no tiene"),
        ("3b_sin_reserva", "Cargas del camión sin reserva en la pala"),
        ("3b_fantasma", "Reservas fantasma en palas (camión que ya no la tiene)"),
        ("3c_sin_reserva", "Descargas del camión sin reserva en el destino"),
        ("3c_fantasma", "Reservas fantasma en destinos"),
    ]
    solo_plan = {"1a", "1b", "1d", "1f", "1h", "3a_falta", "3a_sobra", "3b_sin_reserva",
                 "3b_fantasma", "3c_sin_reserva", "3c_fantasma"}
    ws.cell(row=f + 1, column=1, value="Comprobación").font = BOLD
    ws.cell(row=f + 1, column=2, value="Resultado").font = BOLD
    k = f + 2
    for c, desc in chequeos:
        if clave == "sin" and c not in solo_plan:
            continue
        n = informe.resumen.get(c, "-")
        ws.cell(row=k, column=1, value=desc)
        cel = ws.cell(row=k, column=2, value="OK" if n == 0 else ("%s problema(s)" % n))
        if n not in (0, "-"):
            cel.font = Font(bold=True, color=COLOR["PROBLEMA"])
        k += 1
    if clave == "sin":
        ws.cell(row=k + 1, column=1, value="Nota: las comprobaciones de la agenda se hicieron al "
                                          "final de la corrida de origen.").font = CHICO
    ancho(ws, [58, 70])


def hoja_camiones(ws, por):
    ws.title = "Camiones"
    cols = ["Camión", "N°", "Operación", "Pala / destino", "Inicio plan", "Fin plan",
            "Inicio real", "Fin real", "Duración real (min)", "Retraso al terminar (min)",
            "Espera antes (min)", "Toneladas"]
    encabezado(ws, 1, cols)
    f = 2
    for cam in sorted(por, key=lambda c: (len(c), c)):
        for o in por[cam]:
            rec = o["maquina"] if o["op"] == "CARGA" else vs.norm(o["ubic"])
            vals = [cam, o["idx"], NOMBRE_OP[o["op"]], rec, hms(o["ip"]), hms(o["fp"]),
                    hms(o["ir"]), hms(o["fr"]), round((o["fr"] - o["ir"]) / MIN, 1),
                    round((o["fr"] - o["fp"]) / MIN, 1), round(o.get("espera", 0) / MIN, 1),
                    o["material"] if o["op"] in ("CARGA", "DESCARGA") else None]
            for j, v in enumerate(vals, 1):
                ws.cell(row=f, column=j, value=v)
            ws.cell(row=f, column=3).fill = FILL[o["op"]]
            ws.cell(row=f, column=3).font = Font(color="FFFFFF" if o["op"] != "DESCARGA" else "000000")
            f += 1
    ws.auto_filter.ref = "A1:L%d" % (f - 1)
    ancho(ws, [10, 5, 14, 22, 11, 11, 11, 11, 11, 12, 11, 10])


def _simultaneos(lst, i):
    a, b = lst[i]["ir"], lst[i]["fr"]
    return sum(1 for j, o in enumerate(lst) if j != i and o["ir"] < b - TOL and o["fr"] > a + TOL)


def hoja_palas(ws, ops, ubic_pala):
    ws.title = "Palas"
    cols = ["Pala", "Ubicación", "Camión", "Llega a la pala", "Inicio carga", "Fin carga",
            "Espera del camión (min)", "Otras cargas a la vez en esta pala"]
    encabezado(ws, 1, cols)
    por = defaultdict(list)
    for o in ops:
        if o["op"] == "CARGA":
            por[o["maquina"]].append(o)
    f = 2
    for pala in sorted(por):
        lst = sorted(por[pala], key=lambda o: o["ir"])
        for i, o in enumerate(lst):
            s = _simultaneos(lst, i)
            vals = [pala, ubic_pala.get(pala, vs.norm(o["ubic"])), o["camion"], hms(o["llegada"]),
                    hms(o["ir"]), hms(o["fr"]), round(o["espera"] / MIN, 1), s]
            for j, v in enumerate(vals, 1):
                ws.cell(row=f, column=j, value=v)
            if s > 0:
                ws.cell(row=f, column=8).fill = FILL["PROBLEMA"]
                ws.cell(row=f, column=8).font = Font(color="FFFFFF", bold=True)
            f += 1
    ws.auto_filter.ref = "A1:H%d" % max(1, f - 1)
    ancho(ws, [9, 22, 10, 13, 13, 13, 13, 16])


def hoja_destinos(ws, ops, cap):
    ws.title = "Destinos"
    cols = ["Destino", "Capacidad", "Camión", "Llega al destino", "Inicio descarga", "Fin descarga",
            "Espera del camión (min)", "Descargas a la vez (incluida esta)"]
    encabezado(ws, 1, cols)
    por = defaultdict(list)
    for o in ops:
        if o["op"] == "DESCARGA":
            por[vs.norm(o["ubic"])].append(o)
    f = 2
    for d in sorted(por):
        lst = sorted(por[d], key=lambda o: o["ir"])
        c = cap.get(d, 1)
        for i, o in enumerate(lst):
            s = _simultaneos(lst, i) + 1
            vals = [d, c, o["camion"], hms(o["llegada"]), hms(o["ir"]), hms(o["fr"]),
                    round(o["espera"] / MIN, 1), s]
            for j, v in enumerate(vals, 1):
                ws.cell(row=f, column=j, value=v)
            if s > c:
                ws.cell(row=f, column=8).fill = FILL["PROBLEMA"]
                ws.cell(row=f, column=8).font = Font(color="FFFFFF", bold=True)
            f += 1
    ws.auto_filter.ref = "A1:H%d" % max(1, f - 1)
    ancho(ws, [22, 10, 10, 13, 13, 13, 13, 16])


def hoja_gantt(ws, por, ops, eventos, cap, ubic_pala, clave):
    ws.title = "Gantt"
    C0 = 2  # primera columna de minutos
    ws["A1"] = "Gantt minuto a minuto, 0:00 a 6:00. " + (
        "Plan original (sin incertidumbre)." if clave == "sin" else "Ejecución real.")
    ws["A1"].font = BOLD
    # leyenda
    col = C0
    for k in ("VIAJE_VACIO", "CARGA", "VIAJE_CARGADO", "DESCARGA", "ESPERA", "FALLA", "PROBLEMA"):
        ws.cell(row=2, column=col).fill = FILL[k]
        ws.cell(row=2, column=col + 1, value=NOMBRE_OP[k]).font = CHICO
        col += 22 if k != "PROBLEMA" else 1
    # eje de tiempo
    for m in range(HORIZ):
        c = ws.cell(row=3, column=C0 + m)
        if m % 60 == 0:
            c.value = "%d:00" % (m // 60)
            c.font = Font(bold=True, size=8)
        elif m % 10 == 0:
            c.value = m % 60
            c.font = CHICO
        ws.column_dimensions[get_column_letter(C0 + m)].width = 1.4
    ws.column_dimensions["A"].width = 26
    fila = 4

    def minutos(a, b):
        return range(max(0, a // MIN), min(HORIZ, -(-b // MIN)))

    # perturbaciones
    for e in eventos:
        ws.cell(row=fila, column=1, value="%s %s" % (e["tipo"].replace("_", " ").title(),
                                                    vs.norm(e["objetivo"]))).font = CHICO
        es_falla = e["tipo"].startswith("FALLA")
        for m in minutos(int(e["inicio_ms"]), int(e["fin_ms"])):
            ws.cell(row=fila, column=C0 + m).fill = FILL["FALLA"] if es_falla else FILL["CLIMA"]
        fila += 1
    fila += 1

    fallas = defaultdict(list)
    for e in eventos:
        if e["tipo"].startswith("FALLA"):
            fallas[vs.norm(e["objetivo"])].append((int(e["inicio_ms"]), int(e["fin_ms"])))

    def en_falla(nombre, m):
        t = m * MIN + MIN // 2
        return any(a <= t < b for a, b in fallas.get(vs.norm(nombre), []))

    prio = {"CARGA": 4, "DESCARGA": 4, "ESPERA": 3, "VIAJE_CARGADO": 2, "VIAJE_VACIO": 2}
    ws.cell(row=fila, column=1, value="CAMIONES").font = BOLD
    fila += 1
    for cam in sorted(por, key=lambda c: (len(c), c)):
        ws.cell(row=fila, column=1, value=cam).font = CHICO
        celda = {}
        for o in por[cam]:
            if o.get("espera", 0) > TOL:
                for m in minutos(o["llegada"], o["ir"]):
                    if prio["ESPERA"] >= prio.get(celda.get(m), 0):
                        celda[m] = "ESPERA"
            for m in minutos(o["ir"], o["fr"]):
                if prio[o["op"]] >= prio.get(celda.get(m), 0):
                    celda[m] = o["op"]
        for m, k in celda.items():
            ws.cell(row=fila, column=C0 + m).fill = FILL[k]
        for m in range(HORIZ):
            if en_falla(cam, m):
                ws.cell(row=fila, column=C0 + m).fill = FILL["FALLA"]
        fila += 1
    fila += 1

    def fila_recurso(nombre, intervalos, capacidad, color_ok):
        cuenta = [0] * HORIZ
        for a, b in intervalos:
            for m in minutos(a, b):
                cuenta[m] += 1
        for m in range(HORIZ):
            c = ws.cell(row=fila, column=C0 + m)
            if en_falla(nombre, m) and cuenta[m] == 0:
                c.fill = FILL["FALLA"]
            elif cuenta[m] > capacidad:
                c.fill = FILL["PROBLEMA"]
                c.value = cuenta[m]
                c.font = BLANCO
            elif cuenta[m] > 0:
                c.fill = FILL[color_ok]
                if cuenta[m] > 1:
                    c.value = cuenta[m]
                    c.font = CHICO

    ws.cell(row=fila, column=1, value="PALAS (número = camiones a la vez)").font = BOLD
    fila += 1
    cargas = defaultdict(list)
    for o in ops:
        if o["op"] == "CARGA":
            cargas[o["maquina"]].append((o["ir"], o["fr"]))
    for pala in sorted(set(cargas) | set(ubic_pala)):
        ws.cell(row=fila, column=1, value="%s (%s)" % (pala, ubic_pala.get(pala, ""))).font = CHICO
        fila_recurso(pala, cargas.get(pala, []), 1, "CARGA")
        fila += 1
    fila += 1
    ws.cell(row=fila, column=1, value="DESTINOS (número = descargas a la vez)").font = BOLD
    fila += 1
    desc = defaultdict(list)
    for o in ops:
        if o["op"] == "DESCARGA":
            desc[vs.norm(o["ubic"])].append((o["ir"], o["fr"]))
    for d in sorted(set(desc) | set(cap)):
        ws.cell(row=fila, column=1, value="%s (cap. %d)" % (d, cap.get(d, 1))).font = CHICO
        fila_recurso(d, desc.get(d, []), cap.get(d, 1), "DESCARGA")
        fila += 1
    ws.freeze_panes = ws.cell(row=4, column=C0)


def hoja_eventos(ws, eventos):
    ws.title = "Perturbaciones"
    encabezado(ws, 1, ["Tipo", "Equipo afectado", "Inicio", "Fin", "Duración (min)", "Factor de velocidad"])
    for i, e in enumerate(eventos, 2):
        a, b = int(e["inicio_ms"]), int(e["fin_ms"])
        for j, v in enumerate([e["tipo"].replace("_", " ").title(), vs.norm(e["objetivo"]) or "Toda la flota",
                               hms(a), hms(b), round((b - a) / MIN), fnum(e["factor_velocidad"])], 1):
            ws.cell(row=i, column=j, value=v)
    ancho(ws, [20, 24, 10, 10, 14, 18])


def hoja_problemas(ws, informe, clave):
    ws.title = "Problemas"
    encabezado(ws, 1, ["Comprobación", "Recurso", "Camión", "Hora", "Detalle"])
    viol = informe.viol
    if clave == "sin":
        viol = [v for v in viol if v["chequeo"][:2] in ("1a", "1b", "1d", "1f", "1h", "3a", "3b", "3c")]
    if not viol:
        ws.cell(row=2, column=1, value="Sin problemas detectados.")
    for i, v in enumerate(viol, 2):
        for j, k in enumerate(["chequeo", "recurso", "camion", "inicio", "detalle"], 1):
            ws.cell(row=i, column=j, value=v[k])
    ancho(ws, [38, 14, 10, 9, 90])


# ── Principal ───────────────────────────────────────────────────────────────
def generar(clave, etiqueta, salida):
    carpeta = os.path.join(RAIZ, etiqueta)
    if not os.path.isdir(carpeta):
        print("  !! no existe corridas/%s, se omite %s" % (etiqueta, ARCHIVOS[clave]))
        return
    ops = operaciones(carpeta, clave)
    por = con_esperas(ops)
    recursos = vs.leer(carpeta, "recursos.csv")
    eventos = vs.leer(carpeta, "eventos.csv")
    ubic_pala = {r["nombre"]: vs.norm(r["ubicacion"]) for r in recursos if r["tipo"] == "PALA"}
    cap = {vs.norm(r["nombre"]): int(fnum(r["capacidad"], 1)) for r in recursos if r["tipo"] == "DESTINO"}
    informe = vs.verificar(carpeta)

    wb = Workbook()
    hoja_resumen(wb.active, clave, etiqueta, carpeta, ops, informe, recursos)
    hoja_camiones(wb.create_sheet(), por)
    hoja_palas(wb.create_sheet(), ops, ubic_pala)
    hoja_destinos(wb.create_sheet(), ops, cap)
    hoja_gantt(wb.create_sheet(), por, ops, eventos, cap, ubic_pala, clave)
    hoja_eventos(wb.create_sheet(), eventos)
    hoja_problemas(wb.create_sheet(), informe, clave)
    ruta = os.path.join(salida, ARCHIVOS[clave])
    wb.save(ruta)
    print("  %-34s <- corridas/%s" % (ARCHIVOS[clave], etiqueta))
    if clave in ("rs", "partial", "complete"):
        generar_schedules(clave, carpeta, salida)


# ── Un schedule por reprogramacion (lo pidio el profesor) ──────────────────
FILL_NUEVA = PatternFill("solid", fgColor="D9EAD3")
FILL_ELIMINADA = PatternFill("solid", fgColor="F4CCCC")


def _clave_slot(r):
    return (r["camion"], r["operacion"], r["inicio_ms"], r["fin_ms"], vs.norm(r["ubicacion"]))


def generar_schedules(clave, carpeta, salida):
    """Un Excel con una hoja por schedule (plan inicial y despues de cada
    reprogramacion). Cada hoja marca en verde las asignaciones nuevas y agrega
    al final, en rojo, las que estaban en el schedule anterior y desaparecieron."""
    dsch = os.path.join(carpeta, "schedules_por_reprogramacion")
    if not os.path.isdir(dsch):
        return
    indice = vs.leer(dsch, "indice.csv")
    if len(indice) < 2:
        return
    wb = Workbook()
    wi = wb.active
    wi.title = "Indice"
    wi["A1"] = "Schedules de la corrida: plan inicial y uno después de cada reprogramación"
    wi["A1"].font = TITULO
    wi["A2"] = ("Cada hoja S01, S02, ... es el plan en ese momento. En verde, asignaciones nuevas respecto "
                "del schedule anterior; al final de cada hoja, en rojo, las que se eliminaron.")
    wi["A2"].font = CHICO
    encabezado(wi, 4, ["Schedule", "Motivo", "Reloj de simulación", "Operaciones", "Cargas",
                       "Cargas nuevas", "Cargas eliminadas"])
    anterior = None
    for k, fila in enumerate(indice):
        n = int(fila["numero"])
        actual = vs.leer(dsch, "schedule_%02d.csv" % n)
        cargas = {_clave_slot(r) for r in actual if r["operacion"] == "CARGA"}
        claves_act = {_clave_slot(r) for r in actual}
        nuevas = eliminadas = 0
        if anterior is not None:
            cargas_ant = {_clave_slot(r) for r in anterior if r["operacion"] == "CARGA"}
            nuevas, eliminadas = len(cargas - cargas_ant), len(cargas_ant - cargas)
        for j, v in enumerate(["S%02d" % n, fila["motivo"], fila["reloj"], int(fila["operaciones"]),
                               len(cargas), nuevas if anterior is not None else "-",
                               eliminadas if anterior is not None else "-"], 1):
            wi.cell(row=5 + k, column=j, value=v)

        ws = wb.create_sheet(("S%02d %s" % (n, fila["reloj"]))[:31].replace(":", "h", 1).replace(":", "m"))
        ws["A1"] = "Schedule %02d - %s - reloj %s" % (n, fila["motivo"], fila["reloj"])
        ws["A1"].font = BOLD
        encabezado(ws, 2, ["Camión", "Operación", "Pala / destino", "Inicio", "Fin", "Estado"])
        claves_ant = {_clave_slot(r) for r in anterior} if anterior is not None else None
        filas = sorted(actual, key=lambda r: (int(r["camion"][5:]) if r["camion"][5:].isdigit() else 0,
                                              int(r["inicio_ms"])))
        f = 3
        for r in filas:
            rec = r["maquina"] if r["operacion"] == "CARGA" else vs.norm(r["ubicacion"])
            nueva = claves_ant is not None and _clave_slot(r) not in claves_ant
            for j, v in enumerate([r["camion"], NOMBRE_OP[r["operacion"]], rec, r["inicio"], r["fin"],
                                   "nueva" if nueva else ""], 1):
                c = ws.cell(row=f, column=j, value=v)
                if nueva:
                    c.fill = FILL_NUEVA
            f += 1
        if anterior is not None:
            for r in sorted([r for r in anterior if _clave_slot(r) not in claves_act],
                            key=lambda r: (r["camion"], int(r["inicio_ms"]))):
                rec = r["maquina"] if r["operacion"] == "CARGA" else vs.norm(r["ubicacion"])
                for j, v in enumerate([r["camion"], NOMBRE_OP[r["operacion"]], rec, r["inicio"], r["fin"],
                                       "eliminada"], 1):
                    ws.cell(row=f, column=j, value=v).fill = FILL_ELIMINADA
                f += 1
        ws.auto_filter.ref = "A2:F%d" % max(2, f - 1)
        ancho(ws, [10, 14, 22, 10, 10, 11])
        anterior = actual
    ancho(wi, [10, 40, 18, 12, 10, 14, 16])
    nombre = ARCHIVOS[clave].replace(".xlsx", "_schedules.xlsx")
    wb.save(os.path.join(salida, nombre))
    print("  %-34s <- %d schedules" % (nombre, len(indice)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sin")
    ap.add_argument("--con")
    ap.add_argument("--rs")
    ap.add_argument("--partial")
    ap.add_argument("--complete")
    ap.add_argument("--salida", default=os.path.join(AQUI, "entrega_profesor"))
    a = ap.parse_args()
    sel = {"sin": a.sin or a.con, "con": a.con, "rs": a.rs, "partial": a.partial, "complete": a.complete}
    if not any(sel.values()):
        ap.error("indica al menos una corrida (--con, --rs, --partial, --complete)")
    os.makedirs(a.salida, exist_ok=True)
    print("Generando en %s" % a.salida)
    for clave in ("sin", "con", "rs", "partial", "complete"):
        if sel[clave]:
            generar(clave, sel[clave], a.salida)
    planes = {}
    for clave, et in sel.items():
        if et and clave != "sin":
            p = vs.leer(os.path.join(RAIZ, et), "plan_original.csv")
            planes[et] = len(p)
    if len(set(planes.values())) > 1:
        print("\n  Aviso: las corridas no parten del mismo plan original (%s)."
              % ", ".join("%s: %d op." % kv for kv in planes.items()))
        print("  Hasta tener el plan unico, cada Excel se compara contra su propio plan original.")


if __name__ == "__main__":
    main()
