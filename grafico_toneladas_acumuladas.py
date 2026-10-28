# -*- coding: utf-8 -*-
"""
Grafico de toneladas acumuladas descargadas por escenario (prompt del profesor):
"el eje x es el tiempo transcurrido, el eje y las toneladas acumuladas; de cada
archivo utiliza la pestaña Camiones y la columna Fin real (para el tiempo)".

Usa los 5 Excel de una entrega. La hoja Camiones de cada Excel tiene la
ejecucion final de la corrida, con toda la historia de lo realizado.
Se cuentan las toneladas de cada DESCARGA en el instante en que termina.

Genera dos figuras en la carpeta de la entrega:
  toneladas_acumuladas.png   lo que pidio el profesor, con una nota del tamano
                             del plan inicial de cada corrida
  porcentaje_del_plan.png    toneladas reales / toneladas de SU PROPIO plan a la
                             misma hora. Hace falta porque cada corrida arma un
                             plan inicial distinto (la negociacion no es
                             determinista): comparar toneladas absolutas mezcla
                             el efecto del metodo con el del plan.

Colores (2026-10-07): validados con OKLab y simulacion de daltonismo. El par
naranja/rojo que se usaba antes no se distinguia ni con vision normal (7,1 < 15);
ahora el caso sin reprogramacion va en gris (referencia neutra). El unico par en
la banda 6-8 (aqua/rojo con deuteranopia) lleva etiqueta al final de la linea.

Uso:
  python grafico_toneladas_acumuladas.py                     (carpeta entrega_profesor_V6)
  python grafico_toneladas_acumuladas.py --carpeta entrega_profesor_V5
"""
import argparse
import csv
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter
from openpyxl import load_workbook

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
AQUI = os.path.dirname(os.path.abspath(__file__))
TURNO_H = 6.0

TINTA = "#0b0b0b"
TINTA_2 = "#52514e"
GRILLA = "#e3e2de"
SERIES = [  # archivo, nombre, color, estilo, ancho
    ("1_plan_sin_incertidumbre.xlsx", "Plan sin incertidumbre", "#2a78d6", "--", 1.8),
    ("2_plan_con_incertidumbre.xlsx", "Sin reprogramación", "#52514e", "-", 2.0),
    ("3_right_shift.xlsx", "Right shift", "#1baf7a", "-", 2.0),
    ("4_reparacion_parcial.xlsx", "Reparación parcial", "#e34948", "-", 2.0),
    ("5_regeneracion_completa.xlsx", "Regeneración completa", "#4a3aa7", "-", 2.0),
]


def miles(v):
    return f"{v:,.0f}".replace(",", ".")


def horas(hms):
    h, m, s = (int(x) for x in str(hms).split(":"))
    return h + m / 60 + s / 3600


def resumen(ruta):
    """Corrida de origen y operaciones del plan original, desde la hoja Resumen."""
    datos = {}
    for fila in load_workbook(ruta, read_only=True)["Resumen"].iter_rows(values_only=True):
        if fila and fila[0]:
            datos[str(fila[0])] = fila[1]
    return datos.get("Corrida de origen"), datos.get("Operaciones del plan original")


def serie(ruta):
    ws = load_workbook(ruta, read_only=True)["Camiones"]
    filas = ws.iter_rows(values_only=True)
    cab = next(filas)
    i_op, i_fin, i_ton = cab.index("Operación"), cab.index("Fin real"), cab.index("Toneladas")
    eventos = sorted((horas(r[i_fin]), float(r[i_ton] or 0)) for r in filas if r[i_op] == "Descarga")
    xs, ys, acum = [0.0], [0.0], 0.0
    for t, ton in eventos:
        acum += ton
        xs.append(t); ys.append(acum)
    return xs, ys


def serie_plan(corrida):
    """Toneladas acumuladas segun el PLAN ORIGINAL de la corrida (fin planificado de cada descarga)."""
    ruta = os.path.join(AQUI, "corridas", corrida, "plan_original.csv")
    eventos = sorted((int(r["fin_ms"]) / 3_600_000, float(r["material"] or 0))
                     for r in csv.DictReader(open(ruta, encoding="utf-8")) if r["operacion"] == "DESCARGA")
    xs, ys, acum = [0.0], [0.0], 0.0
    for t, ton in eventos:
        acum += ton
        xs.append(t); ys.append(acum)
    return xs, ys


def valor_en(xs, ys, h):
    v = 0.0
    for x, y in zip(xs, ys):
        if x <= h:
            v = y
        else:
            break
    return v


def etiquetas_finales(ax, puntos, separacion):
    """Etiqueta de texto al final de cada linea, separadas para que no se pisen."""
    puntos = sorted(puntos, key=lambda p: p[1])
    ys = [y for _, y, _ in puntos]
    for _ in range(50):                      # relajacion: separa sin alejarse de su linea
        for i in range(1, len(ys)):
            falta = separacion - (ys[i] - ys[i - 1])
            if falta > 0:
                ys[i - 1] -= falta / 2
                ys[i] += falta / 2
    for (x, _, texto), y in zip(puntos, ys):
        ax.annotate(texto, xy=(x, y), xytext=(6, 0), textcoords="offset points",
                    va="center", ha="left", fontsize=8.5, color=TINTA_2)


def encabezado(fig, titulo, nota):
    fig.text(0.06, 0.965, titulo, fontsize=13, color=TINTA, ha="left", va="top")
    fig.text(0.06, 0.925, nota, fontsize=8.5, color=TINTA_2, ha="left", va="top", linespacing=1.4)


def perturbaciones(ax, corrida, x0):
    """Franjas suaves con las perturbaciones del escenario (eventos.csv de la corrida)."""
    ruta = os.path.join(AQUI, "corridas", corrida or "", "eventos.csv")
    if not corrida or not os.path.exists(ruta):
        return
    nombres = {"LLUVIA_LIGERA": "lluvia", "FALLA_CAMION": "falla camión", "FALLA_PALA": "falla pala",
               "NEBLINA": "neblina", "FALLA_CHANCADOR": "falla chancador", "TORMENTA": "tormenta"}
    ymax = ax.get_ylim()[1]
    for r in csv.DictReader(open(ruta, encoding="utf-8")):
        a, b = int(r["inicio_ms"]) / 3_600_000, int(r["fin_ms"]) / 3_600_000
        if b < x0:
            continue
        ax.axvspan(a, b, color="#efeee9", zorder=0, linewidth=0)
        if (a + b) / 2 < x0:
            continue                       # la etiqueta caeria fuera del eje
        etiqueta = nombres.get(r["tipo"], r["tipo"].lower())
        objetivo = (r.get("objetivo") or "").replace("#", "").replace("_EST", "")
        if r["tipo"] == "FALLA_PALA" and objetivo:
            etiqueta = "falla pala " + objetivo
        elif r["tipo"] == "FALLA_CHANCADOR" and objetivo:
            # desde el 2026-10-15 la falla de descarga puede caer en un botadero
            etiqueta = ("falla chancador " if objetivo.startswith("CS") else "falla botadero ") + objetivo
        ax.text((a + b) / 2, ymax, etiqueta, rotation=90, ha="center",
                va="top", fontsize=7, color="#8a8983", zorder=1)


def estilo(ax):
    ax.grid(True, color=GRILLA, linewidth=0.8)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color("#b9b8b2")
    ax.tick_params(colors=TINTA_2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--carpeta", default="entrega_profesor_V6")
    a = ap.parse_args()
    carpeta = os.path.join(AQUI, a.carpeta)

    datos = []
    for archivo, nombre, color, estilo_linea, ancho in SERIES:
        ruta = os.path.join(carpeta, archivo)
        if not os.path.exists(ruta):
            print("  falta", archivo); continue
        corrida, ops = resumen(ruta)
        xs, ys = serie(ruta)
        datos.append(dict(archivo=archivo, nombre=nombre, color=color, estilo=estilo_linea, ancho=ancho,
                          corrida=corrida, ops=ops, xs=xs, ys=ys))

    # ── 1. Toneladas acumuladas ────────────────────────────────────────────
    fig, ax = plt.subplots(figsize=(11.5, 6.6), dpi=150)
    finales = []
    for d in datos:
        ax.step(d["xs"], d["ys"], where="post", color=d["color"], linestyle=d["estilo"],
                linewidth=d["ancho"], label="%s (%s t en 6 h)" % (d["nombre"], miles(valor_en(d["xs"], d["ys"], TURNO_H))))
        finales.append((d["xs"][-1], d["ys"][-1], d["nombre"]))
    ax.set_ylim(bottom=0)
    perturbaciones(ax, datos[1]["corrida"] if len(datos) > 1 else None, 0)
    ax.axvline(TURNO_H, color="#8a8983", linewidth=1, linestyle=":")
    ax.text(TURNO_H + 0.03, ax.get_ylim()[1] * 0.02, "fin del turno (6 h)", color=TINTA_2, fontsize=9)
    # 5 series: la leyenda las identifica (sin etiquetas al final, que se pisaban)
    planes = [d for d in datos if d["archivo"] != "1_plan_sin_incertidumbre.xlsx" and d["ops"]]
    nota = ""
    if planes and len({d["ops"] for d in planes}) == 1:
        # plan inicial unico (desde el 14-oct-2026): todas parten del mismo plan
        nota = ("Todas las corridas parten del mismo plan inicial (%s operaciones).\n"
                "El plan sin incertidumbre es ese mismo plan." % miles(planes[0]["ops"]))
    elif planes:
        nota = ("Cada corrida arma su propio plan inicial (operaciones): "
                + "; ".join("%s %s" % (d["nombre"].lower(), miles(d["ops"])) for d in planes)
                + ".\nEl plan sin incertidumbre es el de la corrida sin reprogramación.")
    encabezado(fig, "Toneladas acumuladas descargadas por escenario", nota)
    ax.set_xlabel("Tiempo transcurrido (h)", color=TINTA_2)
    ax.set_ylabel("Toneladas acumuladas (t)", color=TINTA_2)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: miles(v)))
    ax.set_xlim(0, max(d["xs"][-1] for d in datos) + 0.2)
    estilo(ax)
    ax.legend(loc="lower right", bbox_to_anchor=(1.0, 0.07), frameon=True, framealpha=0.95, fontsize=9)
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    salida = os.path.join(carpeta, "toneladas_acumuladas.png")
    fig.savefig(salida, facecolor="white")
    plt.close(fig)
    print("Guardado en", salida)

    # ── 2. Porcentaje de su propio plan ────────────────────────────────────
    fig, ax = plt.subplots(figsize=(11.5, 6.2), dpi=150)
    finales, tabla = [], []
    for d in datos:
        if d["archivo"] == "1_plan_sin_incertidumbre.xlsx" or not d["corrida"]:
            continue
        pxs, pys = serie_plan(d["corrida"])
        ts = [1.5 + i / 60 for i in range(int((TURNO_H - 1.5) * 60) + 1)]   # minuto a minuto, desde 1,5 h
        pct = []
        for t in ts:
            plan = valor_en(pxs, pys, t)
            pct.append(100 * valor_en(d["xs"], d["ys"], t) / plan if plan > 0 else None)
        ax.plot(ts, pct, color=d["color"], linewidth=d["ancho"], label=d["nombre"])
        finales.append((ts[-1], pct[-1], "%s  %.1f %%" % (d["nombre"], pct[-1])))
        tabla.append((d["nombre"], valor_en(pxs, pys, TURNO_H), valor_en(d["xs"], d["ys"], TURNO_H), pct[-1]))
    ax.axhline(100, color="#8a8983", linewidth=1, linestyle=":")
    ax.set_ylim(top=101.5)
    perturbaciones(ax, datos[1]["corrida"] if len(datos) > 1 else None, 1.5)
    ax.text(4.85, 100.3, "100 % = lo que su plan decía a esa hora", color=TINTA_2, fontsize=8.5)
    etiquetas_finales(ax, finales, 0.9)
    encabezado(fig, "Producción real como porcentaje de su propio plan",
               "Toneladas descargadas hasta cada hora, divididas por las que el plan inicial de esa misma corrida\n"
               "tenía previstas para esa hora. Así se comparan los métodos aunque sus planes iniciales sean distintos.")
    ax.set_xlabel("Tiempo transcurrido (h)", color=TINTA_2)
    ax.set_ylabel("Real / plan (%)", color=TINTA_2)
    ax.set_xlim(1.5, TURNO_H + 1.35)
    estilo(ax)
    ax.legend(loc="upper right", bbox_to_anchor=(1.0, 0.93), frameon=True, framealpha=0.95, fontsize=9)
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    salida = os.path.join(carpeta, "porcentaje_del_plan.png")
    fig.savefig(salida, facecolor="white")
    plt.close(fig)
    print("Guardado en", salida)

    # ── 3. Mismo grafico con el estilo del profesor ────────────────────────
    # Copia el formato de su grafico: titulo centrado, marco completo, leyenda
    # con el total de TODA la corrida (incluye lo descargado despues de las
    # 6 h), sin notas ni franjas. Mismos tonos que el suyo (azul punteado,
    # naranja, verde, rojo, morado), ajustados para que se distingan: los suyos
    # confundian azul con morado y, con daltonismo, naranja con verde. Esta
    # combinacion pasa: peor par con daltonismo 9,2 y con vision normal 16,3.
    colores_prof = {
        "1_plan_sin_incertidumbre.xlsx": ("Plan sin incertidumbre", "#2a78d6", "--"),
        "2_plan_con_incertidumbre.xlsx": ("Plan con incertidumbre", "#eb6834", "-"),
        "3_right_shift.xlsx": ("Right shift", "#1baf7a", "-"),
        "4_reparacion_parcial.xlsx": ("Reparación parcial", "#a8282c", "-"),
        "5_regeneracion_completa.xlsx": ("Regeneración completa", "#4a3aa7", "-"),
    }
    fig, ax = plt.subplots(figsize=(11, 6), dpi=150)
    for d in datos:
        nombre, color, linea = colores_prof[d["archivo"]]
        ax.step(d["xs"], d["ys"], where="post", color=color, linestyle=linea, linewidth=2,
                label="%s (%s t)" % (nombre, miles(d["ys"][-1])))
    ax.set_title("Toneladas acumuladas descargadas por escenario", fontsize=13)
    ax.set_xlabel("Tiempo transcurrido (h)")
    ax.set_ylabel("Toneladas acumuladas (t)")
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: miles(v)))
    ax.set_xlim(0, int(max(d["xs"][-1] for d in datos)) + 1.2)
    ax.set_ylim(0, max(d["ys"][-1] for d in datos) * 1.05)
    ax.grid(True, color="#e3e2de", linewidth=0.8)
    ax.legend(loc="upper left", frameon=True, framealpha=0.95)
    fig.tight_layout()
    salida = os.path.join(carpeta, "toneladas_estilo_profesor.png")
    fig.savefig(salida, facecolor="white")
    plt.close(fig)
    print("Guardado en", salida)

    print("\n%-24s %12s %12s %10s" % ("escenario", "plan 6 h", "real 6 h", "% plan"))
    for n, p, r, pc in tabla:
        print("%-24s %12s %12s %9.1f%%" % (n, miles(p), miles(r), pc))


if __name__ == "__main__":
    main()
