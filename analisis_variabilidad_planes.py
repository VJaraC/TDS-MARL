# -*- coding: utf-8 -*-
"""
Prueba de variabilidad de los planes iniciales (pedida por el profesor).

Pregunta: si se genera varias veces el plan inicial con la MISMA configuracion,
¿cuanto cambia? Si cambia mucho, los metodos no parten de condiciones iguales y
hay que corregir el diseno (mismo plan para los cuatro metodos). Si cambia poco,
no afecta la comparacion.

Lee kpis_corridas.csv (una fila por corrida) y calcula:
  A. Estadistica descriptiva de los planes: media, desviacion, coeficiente de
     variacion (CV), rango e intervalo de confianza del 95 %.
  B. Correlacion entre el tamano del plan y las toneladas (Pearson y Spearman),
     para saber si la variacion del plan se traslada al resultado.
  C. (opcional) Kruskal-Wallis: ¿los distintos metodos partieron de planes de
     tamano parecido? Se aplica a corridas ya hechas de varios metodos.

No requiere librerias externas (solo Python 3).

Uso:
  python analisis_variabilidad_planes.py --prefijo grande_PLAN
  python analisis_variabilidad_planes.py --prefijo grande_PLAN --kruskal mediano

  --prefijo   : etiqueta comun de las corridas a analizar (ETIQUETA_ESCENARIO).
                Se toman todas las filas cuyo escenario empieza con ese texto.
  --kruskal   : prefijo de escenario para comparar metodos (seccion C).
  --incluir-incompletas : incluye corridas que arrancaron por el tope de tiempo
                (por defecto se excluyen, porque su plan quedo a medias).
"""
import argparse
import csv
import itertools
import math
import os
import statistics as st
import sys
from datetime import datetime

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

AQUI = os.path.dirname(os.path.abspath(__file__))
ARCHIVO_KPIS = os.path.join(AQUI, "kpis_corridas.csv")

# Criterio de decision, fijado ANTES de ver los datos. Son convenciones
# practicas; conviene que el profesor las apruebe.
CV_BAJO = 5.0    # % : por debajo, la variacion se considera baja
CV_ALTO = 10.0   # % : por encima, la variacion se considera grande
ALFA = 0.05

# t de Student bilateral al 95 % segun grados de libertad
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365,
       8: 2.306, 9: 2.262, 10: 2.228, 11: 2.201, 12: 2.179, 13: 2.160,
       14: 2.145, 15: 2.131, 16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093,
       20: 2.086, 25: 2.060, 30: 2.042}


def t95(gl):
    if gl in T95:
        return T95[gl]
    for k in sorted(T95):
        if k >= gl:
            return T95[k]
    return 1.96


# ── Funciones especiales (para p-valores sin scipy) ─────────────────────────
def _betacf(a, b, x):
    MAXIT, EPS, FPMIN = 300, 3e-14, 1e-300
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    d = FPMIN if abs(d) < FPMIN else d
    d = 1.0 / d
    h = d
    for m in range(1, MAXIT + 1):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        d = FPMIN if abs(d) < FPMIN else d
        c = 1.0 + aa / c
        c = FPMIN if abs(c) < FPMIN else c
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        d = FPMIN if abs(d) < FPMIN else d
        c = 1.0 + aa / c
        c = FPMIN if abs(c) < FPMIN else c
        d = 1.0 / d
        dl = d * c
        h *= dl
        if abs(dl - 1.0) < EPS:
            break
    return h


def beta_inc(a, b, x):
    """Beta incompleta regularizada I_x(a,b)."""
    if x <= 0:
        return 0.0
    if x >= 1:
        return 1.0
    lbt = (math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b)
           + a * math.log(x) + b * math.log(1 - x))
    bt = math.exp(lbt)
    if x < (a + 1) / (a + b + 2):
        return bt * _betacf(a, b, x) / a
    return 1.0 - bt * _betacf(b, a, 1 - x) / b


def p_t_bilateral(t, gl):
    return beta_inc(gl / 2.0, 0.5, gl / (gl + t * t))


def gamma_inc_q(a, x):
    """Q(a,x) = 1 - P(a,x), gamma incompleta regularizada superior."""
    if x <= 0:
        return 1.0
    if x < a + 1:
        ap, s = a, 1.0 / a
        d = s
        for _ in range(500):
            ap += 1
            d *= x / ap
            s += d
            if abs(d) < abs(s) * 3e-14:
                break
        return 1.0 - s * math.exp(-x + a * math.log(x) - math.lgamma(a))
    b = x + 1 - a
    c = 1.0 / 1e-300
    d = 1.0 / b
    h = d
    for i in range(1, 500):
        an = -i * (i - a)
        b += 2
        d = an * d + b
        d = 1e-300 if abs(d) < 1e-300 else d
        c = b + an / c
        c = 1e-300 if abs(c) < 1e-300 else c
        d = 1.0 / d
        dl = d * c
        h *= dl
        if abs(dl - 1) < 3e-14:
            break
    return math.exp(-x + a * math.log(x) - math.lgamma(a)) * h


# ── Estadisticos ────────────────────────────────────────────────────────────
def rangos(v):
    orden = sorted(range(len(v)), key=lambda i: v[i])
    r = [0.0] * len(v)
    i = 0
    while i < len(v):
        j = i
        while j + 1 < len(v) and v[orden[j + 1]] == v[orden[i]]:
            j += 1
        prom = (i + j) / 2.0 + 1
        for k in range(i, j + 1):
            r[orden[k]] = prom
        i = j + 1
    return r


def pearson(x, y):
    mx, my = st.mean(x), st.mean(y)
    sxy = sum((a - mx) * (b - my) for a, b in zip(x, y))
    sx = math.sqrt(sum((a - mx) ** 2 for a in x))
    sy = math.sqrt(sum((b - my) ** 2 for b in y))
    if sx == 0 or sy == 0:
        return float("nan"), float("nan")
    r = sxy / (sx * sy)
    n = len(x)
    if abs(r) >= 1:
        return r, 0.0
    t = r * math.sqrt((n - 2) / (1 - r * r))
    return r, p_t_bilateral(t, n - 2)


def spearman_exacto(x, y):
    """rho de Spearman con p exacto por permutaciones (n <= 9) o aproximado."""
    rx, ry = rangos(x), rangos(y)
    rho, _ = pearson(rx, ry)
    n = len(x)
    if n <= 9:
        extremos, total = 0, 0
        for perm in itertools.permutations(ry):
            rp, _ = pearson(rx, list(perm))
            total += 1
            if abs(rp) >= abs(rho) - 1e-12:
                extremos += 1
        return rho, extremos / total
    t = rho * math.sqrt((n - 2) / (1 - rho * rho)) if abs(rho) < 1 else float("inf")
    return rho, p_t_bilateral(t, n - 2) if math.isfinite(t) else 0.0


def kruskal_wallis(grupos):
    todos = [v for g in grupos.values() for v in g]
    N = len(todos)
    r = rangos(todos)
    H, i = 0.0, 0
    for g in grupos.values():
        rg = r[i:i + len(g)]
        i += len(g)
        H += sum(rg) ** 2 / len(g)
    H = 12.0 / (N * (N + 1)) * H - 3 * (N + 1)
    # correccion por empates
    cuenta = {}
    for v in todos:
        cuenta[v] = cuenta.get(v, 0) + 1
    emp = sum(t ** 3 - t for t in cuenta.values())
    if emp:
        H /= 1 - emp / (N ** 3 - N)
    gl = len(grupos) - 1
    return H, gl, gamma_inc_q(gl / 2.0, H / 2.0)


def descriptiva(v):
    n = len(v)
    m = st.mean(v)
    de = st.stdev(v) if n > 1 else 0.0
    h = t95(n - 1) * de / math.sqrt(n) if n > 1 else 0.0
    return {"n": n, "media": m, "de": de, "cv": 100 * de / m if m else float("nan"),
            "min": min(v), "max": max(v),
            "rango_pct": 100 * (max(v) - min(v)) / m if m else float("nan"),
            "ic_inf": m - h, "ic_sup": m + h}


# ── Lectura ─────────────────────────────────────────────────────────────────
def leer_kpis():
    if not os.path.exists(ARCHIVO_KPIS):
        sys.exit("No se encontro " + ARCHIVO_KPIS)
    with open(ARCHIVO_KPIS, encoding="utf-8", errors="replace") as f:
        filas = list(csv.DictReader(f))
    # Si una etiqueta se repite (corrida exportada dos veces), queda la ultima.
    por_etiqueta = {}
    for r in filas:
        por_etiqueta[r["escenario"]] = r
    return list(por_etiqueta.values())


def num(r, col):
    try:
        return float(r.get(col, "") or "nan")
    except ValueError:
        return float("nan")


def es_completa(r):
    return (r.get("motivo_arranque", "") == "negociacion_completa"
            and num(r, "palas_completas") >= num(r, "palas_totales"))


def metodo_de(escenario):
    for m in ("NINGUNO", "CASCADA", "RIGHT", "PARTIAL", "COMPLETE"):
        if "_" + m in escenario:
            return "CASCADA" if m == "RIGHT" else m
    return "?"


# ── Informe ─────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--prefijo", required=True)
    ap.add_argument("--kruskal", default=None)
    ap.add_argument("--incluir-incompletas", action="store_true")
    a = ap.parse_args()

    filas = leer_kpis()
    sel = sorted([r for r in filas if r["escenario"].startswith(a.prefijo)],
                 key=lambda r: r["timestamp"])
    out = []
    p = out.append

    p("=" * 72)
    p("PRUEBA DE VARIABILIDAD DE LOS PLANES INICIALES")
    p("Corridas con prefijo '%s'  -  %s" % (a.prefijo, datetime.now().strftime("%Y-%m-%d %H:%M")))
    p("=" * 72)
    if not sel:
        p("No hay corridas con ese prefijo en kpis_corridas.csv.")
        print("\n".join(out))
        return

    p("\nCorridas encontradas:")
    p("  %-24s %8s %6s %9s %11s  %s" % ("escenario", "operac.", "ciclos",
                                       "camiones", "ton (6 h)", "arranque"))
    for r in sel:
        marca = "" if es_completa(r) else "   <-- PLAN INCOMPLETO (tope de tiempo)"
        p("  %-24s %8.0f %6.0f %9.0f %11.0f  %s%s" % (
            r["escenario"][:24], num(r, "slots_plan_inicial"),
            num(r, "slots_plan_inicial") / 4, num(r, "camiones_plan_inicial"),
            num(r, "material_en_turno"), r.get("motivo_arranque", ""), marca))

    usadas = sel if a.incluir_incompletas else [r for r in sel if es_completa(r)]
    excl = len(sel) - len(usadas)
    if excl:
        p("\n  Se excluyen %d corrida(s) con plan incompleto (usar "
          "--incluir-incompletas para considerarlas)." % excl)
    if len(usadas) < 3:
        p("\nSe necesitan al menos 3 corridas validas para la prueba (hay %d)." % len(usadas))
        print("\n".join(out))
        return

    ops = [num(r, "slots_plan_inicial") for r in usadas]
    cam = [num(r, "camiones_plan_inicial") for r in usadas]
    ton = [num(r, "material_en_turno") for r in usadas]

    # A. Descriptiva
    p("\n" + "-" * 72)
    p("A. CUANTO VARIAN LOS PLANES  (n = %d)" % len(usadas))
    p("-" * 72)
    p("  %-30s %9s %8s %7s %15s %9s %20s" % ("medida", "media", "desv.", "CV %",
                                           "min - max", "rango %", "IC 95 % de la media"))
    for nombre, v in (("Operaciones del plan inicial", ops),
                      ("Ciclos (operaciones / 4)", [x / 4 for x in ops]),
                      ("Camiones con asignacion", cam),
                      ("Toneladas en el turno (6 h)", ton)):
        d = descriptiva(v)
        p("  %-30s %9.1f %8.1f %7.1f %7.0f - %-7.0f %9.1f   [%8.1f ; %8.1f]" % (
            nombre, d["media"], d["de"], d["cv"],
            d["min"], d["max"], d["rango_pct"], d["ic_inf"], d["ic_sup"]))
    cv_plan = descriptiva(ops)["cv"]

    # B. Correlacion
    p("\n" + "-" * 72)
    p("B. ¿LA VARIACION DEL PLAN SE TRASLADA A LAS TONELADAS?")
    p("-" * 72)
    r, pr = pearson(ops, ton)
    rho, ps = spearman_exacto(ops, ton)
    p("  Pearson   r   = %+.3f   r2 = %.3f   p = %.4f" % (r, r * r, pr))
    p("  Spearman  rho = %+.3f              p = %.4f (exacto)" % (rho, ps))
    p("  -> El tamano del plan explica el %.1f %% de la variacion de las toneladas." % (100 * r * r))
    q = [t / o for t, o in zip(ton, ops)]
    p("  -> Si se divide por el tamano del plan (toneladas por operacion), el CV")
    p("     baja de %.1f %% a %.1f %% (media %.1f t/op)." % (
        descriptiva(ton)["cv"], descriptiva(q)["cv"], st.mean(q)))
    corr_sig = pr < ALFA
    if len(usadas) <= 5:
        p("  Nota: con n = %d, Spearman exacto no puede bajar de p = %.3f; "
          "se usa Pearson para decidir." % (len(usadas), 2 / math.factorial(len(usadas))))

    # Decision
    p("\n" + "-" * 72)
    p("DECISION  (criterio fijado antes de ver los datos)")
    p("-" * 72)
    p("  Baja     : CV < %.0f %%  y correlacion no significativa" % CV_BAJO)
    p("  Grande   : CV > %.0f %%  o correlacion significativa (p < %.2f)" % (CV_ALTO, ALFA))
    p("  Moderada : cualquier otro caso")
    if cv_plan > CV_ALTO or corr_sig:
        veredicto = ("GRANDE: la variacion del plan inicial afecta los resultados. "
                     "Conviene que los cuatro metodos partan del mismo plan.")
    elif cv_plan < CV_BAJO and not corr_sig:
        veredicto = "BAJA: la variacion del plan inicial no deberia afectar la comparacion."
    else:
        veredicto = "MODERADA: decidir con el profesor."
    p("\n  CV del plan = %.1f %%  |  correlacion %s (p = %.4f)" % (
        cv_plan, "significativa" if corr_sig else "no significativa", pr))
    p("  RESULTADO: " + veredicto)

    # C. Kruskal-Wallis entre metodos
    if a.kruskal:
        grupos = {}
        for fr in filas:
            if fr["escenario"].startswith(a.kruskal) and (a.incluir_incompletas or es_completa(fr)):
                grupos.setdefault(metodo_de(fr["escenario"]), []).append(num(fr, "slots_plan_inicial"))
        grupos = {k: v for k, v in grupos.items() if len(v) >= 2}
        p("\n" + "-" * 72)
        p("C. ¿LOS METODOS PARTIERON DE PLANES PARECIDOS?  (escenario '%s')" % a.kruskal)
        p("-" * 72)
        if len(grupos) < 2:
            p("  Se necesitan al menos 2 metodos con 2 o mas corridas cada uno.")
        else:
            for k, v in grupos.items():
                p("  %-10s n=%2d  media %7.0f operaciones  (rango %.0f - %.0f)" % (
                    k, len(v), st.mean(v), min(v), max(v)))
            H, gl, pk = kruskal_wallis(grupos)
            p("\n  Kruskal-Wallis  H = %.3f   gl = %d   p = %.4f" % (H, gl, pk))
            p("  -> " + ("Los metodos NO partieron de planes comparables."
                         if pk < ALFA else
                         "No hay evidencia de que los metodos partieran de planes distintos."))

    texto = "\n".join(out)
    print(texto)
    nombre = "variabilidad_planes_%s.txt" % a.prefijo
    with open(os.path.join(AQUI, nombre), "w", encoding="utf-8") as f:
        f.write(texto + "\n")
    print("\nInforme guardado en: " + nombre)


if __name__ == "__main__":
    main()
