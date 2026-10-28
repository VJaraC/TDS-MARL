# -*- coding: utf-8 -*-
"""
Verificacion de los schedules de una corrida (carpeta corridas/<etiqueta>/,
que escribe CsvSchedulesPorCorrida al terminar cada simulacion).

Responde cuatro preguntas:
  1. ¿Alguna maquina hace dos cosas a la vez?  (camion, pala, destino; en el
     plan y en la ejecucion real). Incluye: ¿algun camion es cargado en una
     ubicacion que no es la de su pala?
  2. ¿Como son las colas?  espera en cada pala y en cada punto de descarga,
     en la ejecucion real.
  3. ¿El borrado al renegociar deja las cuatro copias de la agenda de acuerdo?
     simulador vs TruckAgent, camion vs ShovelAgent, camion vs UnloadingPointAgent.
     Una reserva que queda en la pala o en el destino sin su camion es capacidad
     bloqueada ("reserva fantasma").
  4. Datos de contexto: operaciones fuera del turno y perturbaciones.

Uso:
  python verificar_schedules.py --etiqueta mediano_NINGUNO_r1
  python verificar_schedules.py --todas            (resumen de todas las corridas)

Deja en la carpeta de la corrida: verificacion.txt y violaciones.csv
"""
import argparse
import csv
import os
import statistics as st
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.join(AQUI, "corridas")
FIN_TURNO = 6 * 3600 * 1000
TOL = 1000  # ms de tolerancia: intervalos que se tocan o se pisan menos de 1 s no cuentan

VIAJES = ("VIAJE_VACIO", "VIAJE_CARGADO")


def hms(ms):
    ms = max(0, int(ms))
    return "%d:%02d:%02d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60)


def norm(u):
    return (u or "").replace("#", "").strip()


def leer(carpeta, nombre):
    ruta = os.path.join(carpeta, nombre)
    if not os.path.exists(ruta):
        return []
    with open(ruta, encoding="utf-8", errors="replace") as f:
        return list(csv.DictReader(f))


def max_simultaneos(intervalos):
    """Maximo de intervalos abiertos a la vez y el instante en que ocurre."""
    ev = []
    for a, b in intervalos:
        if b - a <= TOL:
            continue
        ev.append((a + TOL, 1))   # tolerancia: se ignoran solapes < TOL
        ev.append((b, -1))
    ev.sort(key=lambda x: (x[0], x[1]))
    c = m = 0
    t_max = None
    for t, d in ev:
        c += d
        if c > m:
            m, t_max = c, t - TOL
    return m, t_max


def solapes_consecutivos(slots):
    """Pares de operaciones consecutivas de una misma maquina que se pisan."""
    s = sorted(slots, key=lambda x: (x[0], x[1]))
    out = []
    for p, q in zip(s, s[1:]):
        if q[0] < p[1] - TOL:
            out.append((p, q))
    return out


class Informe:
    def __init__(self):
        self.lineas = []
        self.viol = []
        self.resumen = {}

    def p(self, t=""):
        self.lineas.append(t)

    def v(self, chequeo, recurso, camion, inicio_ms, detalle):
        self.viol.append({"chequeo": chequeo, "recurso": recurso, "camion": camion,
                          "inicio": hms(inicio_ms), "detalle": detalle})


def verificar(carpeta):
    I = Informe()
    et = os.path.basename(carpeta)
    plan_ini = leer(carpeta, "plan_original.csv")
    plan_fin = leer(carpeta, "plan_final.csv")
    agentes = leer(carpeta, "agendas_agentes.csv")
    real = leer(carpeta, "ejecucion_real.csv")
    eventos = leer(carpeta, "eventos.csv")
    recursos = leer(carpeta, "recursos.csv")
    if not plan_fin or not agentes:
        I.p("Faltan archivos en %s (¿la corrida termino y exporto?)" % carpeta)
        return I

    ubic_pala = {r["nombre"]: norm(r["ubicacion"]) for r in recursos if r["tipo"] == "PALA"}
    cap_dest = {norm(r["nombre"]): int(float(r["capacidad"])) for r in recursos if r["tipo"] == "DESTINO"}

    I.p("=" * 74)
    I.p("VERIFICACION DE SCHEDULES  -  %s" % et)
    I.p("=" * 74)
    I.p("Camiones con plan: %d   |   operaciones en el plan original: %d   |   en el plan final: %d"
        % (len({r["camion"] for r in plan_fin}), len(plan_ini), len(plan_fin)))

    def estado(n):
        return "OK" if n == 0 else "%d PROBLEMA(S)" % n

    # ── 1. Una maquina, dos cosas a la vez ───────────────────────────────────
    I.p("\n" + "-" * 74)
    I.p("1. ¿ALGUNA MAQUINA HACE DOS COSAS A LA VEZ?")
    I.p("-" * 74)

    def por_camion(filas, ki="inicio_ms", kf="fin_ms"):
        d = defaultdict(list)
        for r in filas:
            d[r["camion"]].append((int(r[ki]), int(r[kf]), r["operacion"], r.get("ubicacion", "")))
        return d

    # 1a/1b/1c camiones
    for nombre, datos, clave in (
            ("Camion - plan final (simulador)", por_camion(plan_fin), "1a"),
            ("Camion - agenda del TruckAgent", {a: [(int(r["inicio_ms"]), int(r["fin_ms"]), r["operacion"], r["ubicacion"])
                                                   for r in agentes if r["agente"] == a]
                                               for a in {r["agente"] for r in agentes if r["tipo"] == "CAMION"}}, "1b"),
            ("Camion - ejecucion real", por_camion(real, "inicio_real_ms", "fin_real_ms"), "1c")):
        n = 0
        for cam, slots in datos.items():
            for p, q in solapes_consecutivos(slots):
                n += 1
                I.v(clave + " " + nombre, cam, cam, q[0],
                    "%s %s-%s se pisa con %s %s-%s" % (p[2], hms(p[0]), hms(p[1]), q[2], hms(q[0]), hms(q[1])))
        I.p("  %-44s %s" % (nombre, estado(n)))
        I.resumen[clave] = n

    # 1d pala individual en su agenda (plan)
    n = 0
    for a in {r["agente"] for r in agentes if r["tipo"] == "PALA"}:
        iv = [(int(r["inicio_ms"]), int(r["fin_ms"]), r["contraparte"]) for r in agentes if r["agente"] == a]
        for p, q in solapes_consecutivos(iv):
            n += 1
            I.v("1d Pala - agenda del ShovelAgent", a, q[2], q[0],
                "%s carga a %s (%s-%s) y a %s (%s-%s) a la vez"
                % (a, p[2], hms(p[0]), hms(p[1]), q[2], hms(q[0]), hms(q[1])))
    I.p("  %-44s %s" % ("Pala - agenda del ShovelAgent (plan)", estado(n)))
    I.resumen["1d"] = n

    # 1e pala individual en la ejecucion real
    n = 0
    cargas_real = defaultdict(list)
    for r in real:
        if r["operacion"] == "CARGA" and r["maquina"]:
            cargas_real[r["maquina"]].append((int(r["inicio_real_ms"]), int(r["fin_real_ms"])))
    for pala, iv in cargas_real.items():
        m, t = max_simultaneos(iv)
        if m > 1:
            n += 1
            I.v("1e Pala - ejecucion real", pala, "", t, "%d camiones cargando a la vez" % m)
    I.p("  %-44s %s" % ("Pala - ejecucion real", estado(n)))
    I.resumen["1e"] = n

    # 1f destino en su agenda (plan) y 1g en la ejecucion real
    for clave, nombre, grupos in (
            ("1f", "Destino - agenda del UnloadingPointAgent", _desc_agentes(agentes)),
            ("1g", "Destino - ejecucion real", _desc_real(real))):
        n = 0
        for dest, iv in grupos.items():
            cap = cap_dest.get(dest, 1)
            m, t = max_simultaneos(iv)
            if m > cap:
                n += 1
                I.v(clave + " " + nombre, dest, "", t,
                    "%d descargas a la vez con capacidad %d" % (m, cap))
        I.p("  %-44s %s" % (nombre, estado(n)))
        I.resumen[clave] = n

    # 1h camion cargado en una ubicacion que no es la de su pala
    n = 0
    for r in plan_fin:
        if r["operacion"] == "CARGA" and r["maquina"] in ubic_pala:
            if norm(r["ubicacion"]) != ubic_pala[r["maquina"]]:
                n += 1
                I.v("1h Camion cargado fuera de su pala", r["maquina"], r["camion"], int(r["inicio_ms"]),
                    "carga en %s pero %s esta en %s" % (r["ubicacion"], r["maquina"], ubic_pala[r["maquina"]]))
    I.p("  %-44s %s" % ("Camion cargado en la ubicacion de otra pala", estado(n)))
    I.resumen["1h"] = n

    # ── 2. Colas en la ejecucion real ────────────────────────────────────────
    I.p("\n" + "-" * 74)
    I.p("2. COLAS EN LA EJECUCION REAL (espera = llega al recurso y aun no lo atienden)")
    I.p("-" * 74)
    esperas = {"CARGA": defaultdict(list), "DESCARGA": defaultdict(list)}
    for cam, slots in por_camion(real, "inicio_real_ms", "fin_real_ms").items():
        s = sorted(slots)
        for prev, act in zip(s, s[1:]):
            if act[2] in esperas and prev[2] in VIAJES:
                rec = act[3]
                # la pala individual sale de la fila real
                esperas[act[2]][rec].append((prev[1], act[0], cam))
    total_espera = {}
    for op, titulo in (("CARGA", "Palas (por ubicacion)"), ("DESCARGA", "Puntos de descarga")):
        I.p("  %s" % titulo)
        I.p("    %-24s %6s %11s %11s %11s %10s" % ("recurso", "llegadas", "espera tot", "espera med",
                                                  "espera max", "cola max"))
        tot = 0.0
        for rec, lst in sorted(esperas[op].items()):
            w = [max(0, b - a) / 60000 for a, b, _ in lst]
            cola, t = max_simultaneos([(a, b) for a, b, _ in lst if b - a > TOL])
            tot += sum(w)
            I.p("    %-24s %6d %9.0f m %9.1f m %9.1f m %6d %s" % (
                norm(rec)[:24], len(w), sum(w), st.mean(w) if w else 0, max(w) if w else 0,
                cola, ("(a las %s)" % hms(t)) if cola > 1 else ""))
        total_espera[op] = tot
        I.p("    %-24s %6s %9.0f m" % ("TOTAL", "", tot))
    I.resumen["espera_pala_min"] = round(total_espera["CARGA"])
    I.resumen["espera_descarga_min"] = round(total_espera["DESCARGA"])

    # colas durante fallas de destino (esperables) vs fuera de ellas
    fallas_dest = [(norm(e["objetivo"]), int(e["inicio_ms"]), int(e["fin_ms"]))
                   for e in eventos if e["tipo"] == "FALLA_CHANCADOR"]
    for dest, a0, b0 in fallas_dest:
        dentro = [max(0, b - a) / 60000 for a, b, _ in esperas["DESCARGA"].get("#" + dest, []) +
                  esperas["DESCARGA"].get(dest, []) if a < b0 and b > a0 and b - a > TOL]
        I.p("  Durante la falla de %s (%s-%s): %d camiones esperaron, %.0f min en total"
            % (dest, hms(a0), hms(b0), len(dentro), sum(dentro)))

    # ── 3. Consistencia entre las cuatro copias ──────────────────────────────
    I.p("\n" + "-" * 74)
    I.p("3. ¿LAS CUATRO COPIAS DE LA AGENDA COINCIDEN?  (prueba del borrado al renegociar)")
    I.p("-" * 74)

    # 3a simulador vs TruckAgent
    sim = defaultdict(list)
    for r in plan_fin:
        sim[r["camion"]].append((r["operacion"], int(r["inicio_ms"]), int(r["fin_ms"]), norm(r["ubicacion"])))
    tru = defaultdict(list)
    for r in agentes:
        if r["tipo"] == "CAMION":
            tru[r["agente"]].append((r["operacion"], int(r["inicio_ms"]), int(r["fin_ms"]), norm(r["ubicacion"])))
    falta_sim = sobra_sim = 0
    for cam in set(sim) | set(tru):
        a, b = _emparejar(sim.get(cam, []), tru.get(cam, []), lambda x: (x[0], x[3]), 1, 2)
        for x in b:
            falta_sim += 1
            I.v("3a Falta en el simulador", cam, cam, x[1],
                "el TruckAgent tiene %s %s-%s en %s y el simulador no" % (x[0], hms(x[1]), hms(x[2]), x[3]))
        for x in a:
            sobra_sim += 1
            I.v("3a Sobra en el simulador", cam, cam, x[1],
                "el simulador tiene %s %s-%s en %s y el TruckAgent no" % (x[0], hms(x[1]), hms(x[2]), x[3]))
    I.p("  %-52s %s" % ("Simulador vs TruckAgent: operaciones que faltan", estado(falta_sim)))
    I.p("  %-52s %s" % ("Simulador vs TruckAgent: operaciones que sobran", estado(sobra_sim)))
    I.resumen["3a_falta"], I.resumen["3a_sobra"] = falta_sim, sobra_sim

    # 3b camion vs pala, 3c camion vs destino
    for clave, op, tipo_ag, rec_de in (
            ("3b", "CARGA", "PALA", lambda r: r["maquina"]),
            ("3c", "DESCARGA", "DESCARGA", lambda r: norm(r["ubicacion"]))):
        lado_cam = defaultdict(list)
        for r in agentes:
            if r["tipo"] == "CAMION" and r["operacion"] == op:
                rec = rec_de({"maquina": r["contraparte"], "ubicacion": r["ubicacion"]})
                lado_cam[(rec, r["agente"])].append((int(r["inicio_ms"]), int(r["fin_ms"])))
        lado_rec = defaultdict(list)
        for r in agentes:
            if r["tipo"] == tipo_ag:
                lado_rec[(r["agente"], r["contraparte"])].append((int(r["inicio_ms"]), int(r["fin_ms"])))
        sin_reserva = fantasma = 0
        for k in set(lado_cam) | set(lado_rec):
            a, b = _emparejar(lado_cam.get(k, []), lado_rec.get(k, []), lambda x: 0, 0, 1)
            for x in a:
                sin_reserva += 1
                I.v(clave + " Sin reserva en el recurso", k[0], k[1], x[0],
                    "el camion tiene %s %s-%s pero %s no lo tiene registrado" % (op, hms(x[0]), hms(x[1]), k[0]))
            for x in b:
                fantasma += 1
                I.v(clave + " Reserva fantasma", k[0], k[1], x[0],
                    "%s guarda %s %s-%s para %s, que ya no la tiene" % (k[0], op, hms(x[0]), hms(x[1]), k[1]))
        nombre = "Camion vs pala" if op == "CARGA" else "Camion vs punto de descarga"
        I.p("  %-52s %s" % (nombre + ": cargas sin reserva" if op == "CARGA"
                             else nombre + ": descargas sin reserva", estado(sin_reserva)))
        I.p("  %-52s %s" % (nombre + ": reservas fantasma", estado(fantasma)))
        I.resumen[clave + "_sin_reserva"], I.resumen[clave + "_fantasma"] = sin_reserva, fantasma

    # ── 4. Contexto ──────────────────────────────────────────────────────────
    I.p("\n" + "-" * 74)
    I.p("4. CONTEXTO")
    I.p("-" * 74)
    fuera = sum(1 for r in plan_fin if int(r["inicio_ms"]) >= FIN_TURNO)
    I.p("  Operaciones del plan final que empiezan despues de las 6 h: %d" % fuera)
    I.p("  Perturbaciones:")
    for e in eventos:
        I.p("    %-16s %-22s %s - %s   factor %s" % (e["tipo"], e["objetivo"] or "(toda la flota)",
                                                  e["inicio"], e["fin"], e["factor_velocidad"]))

    I.p("\nDetalle de cada problema: violaciones.csv (%d filas)" % len(I.viol))
    return I


def _desc_agentes(agentes):
    g = defaultdict(list)
    for r in agentes:
        if r["tipo"] == "DESCARGA":
            g[norm(r["ubicacion"]) or norm(r["agente"])].append((int(r["inicio_ms"]), int(r["fin_ms"])))
    return g


def _desc_real(real):
    g = defaultdict(list)
    for r in real:
        if r["operacion"] == "DESCARGA":
            g[norm(r["ubicacion"])].append((int(r["inicio_real_ms"]), int(r["fin_real_ms"])))
    return g


def _emparejar(xs, ys, clave, i_ini, i_fin):
    """Empareja dos listas por clave y hora de inicio (±TOL). Devuelve lo que
    sobra de cada lado."""
    libres = list(ys)
    sobran_x = []
    for x in xs:
        hallado = None
        for j, y in enumerate(libres):
            if clave(x) == clave(y) and abs(x[i_ini] - y[i_ini]) <= TOL and abs(x[i_fin] - y[i_fin]) <= TOL:
                hallado = j
                break
        if hallado is None:
            sobran_x.append(x)
        else:
            libres.pop(hallado)
    return sobran_x, libres


def guardar(carpeta, I):
    with open(os.path.join(carpeta, "verificacion.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(I.lineas) + "\n")
    with open(os.path.join(carpeta, "violaciones.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["chequeo", "recurso", "camion", "inicio", "detalle"])
        w.writeheader()
        w.writerows(I.viol)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--etiqueta")
    g.add_argument("--todas", action="store_true")
    a = ap.parse_args()
    if not os.path.isdir(RAIZ):
        sys.exit("No existe la carpeta %s: primero hay que correr una simulacion con la version nueva." % RAIZ)

    if a.etiqueta:
        carpeta = os.path.join(RAIZ, a.etiqueta)
        if not os.path.isdir(carpeta):
            sys.exit("No existe %s" % carpeta)
        I = verificar(carpeta)
        guardar(carpeta, I)
        print("\n".join(I.lineas))
        print("\nGuardado en corridas/%s/verificacion.txt y violaciones.csv" % a.etiqueta)
        return

    cols = ["1a", "1b", "1c", "1d", "1e", "1f", "1g", "1h", "3a_falta", "3a_sobra",
            "3b_sin_reserva", "3b_fantasma", "3c_sin_reserva", "3c_fantasma",
            "espera_pala_min", "espera_descarga_min"]
    print("RESUMEN DE TODAS LAS CORRIDAS (0 = sin problemas; esperas en minutos)")
    print("%-24s " % "corrida" + " ".join("%6s" % c[:6] for c in cols))
    for et in sorted(os.listdir(RAIZ)):
        carpeta = os.path.join(RAIZ, et)
        if not os.path.isdir(carpeta):
            continue
        I = verificar(carpeta)
        guardar(carpeta, I)
        print("%-24s " % et[:24] + " ".join("%6s" % I.resumen.get(c, "-") for c in cols))
    print("\n1a-1c camion hace dos cosas a la vez | 1d-1e pala | 1f-1g destino sobre su capacidad |"
          " 1h cargado en otra ubicacion\n3a simulador vs TruckAgent | 3b camion vs pala | 3c camion vs destino")


if __name__ == "__main__":
    main()
