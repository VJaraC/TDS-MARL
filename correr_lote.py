#!/usr/bin/env python3
"""Lanza un LOTE de corridas de TDS-MARL definido en un archivo JSON.

Cada corrida termina sola al generarse el schedule inicial y deja sus
resultados en corridas/<etiqueta>/ (ver DOCUMENTACION_REGISTROS.md). Este
script las lanza una tras otra, sin recompilar entre corridas: los parametros
viajan como propiedades del sistema (-Dtds.etiqueta, -Dtds.escenario,
-Dtds.deadlineMs, -Dtds.topeNegociacionMin, -Dtds.sinDialogo).

Uso:
  python3 correr_lote.py experimentos.json            lanza el lote
  python3 correr_lote.py experimentos.json --simular  muestra que haria, sin ejecutar nada
  python3 correr_lote.py experimentos.json --sin-compilar   usa bin/ tal como esta

Retomar: relanzar el mismo comando; las corridas cuya carpeta
corridas/<etiqueta>/ ya existe se saltan.
Detener despues de la corrida en curso: crear el archivo DETENER en la carpeta
del lote (corridas/_lotes/<fecha_hora>/DETENER); lo hace la interfaz.

Solo biblioteca estandar. Ver COMO_CORRER.md. La interfaz grafica
(interfaz_lotes.py) solo escribe el JSON y lanza este script.
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parent
ESCENARIOS = {
    "pequeno": "datosShovelTrucksPequeno.owl",
    "mediano": "datosShovelTrucksMediano.owl",
    "grande": "datosShovelTrucksGrande_original.owl",
}
TIEMPO_MAXIMO_POR_DEFECTO_MIN = 360  # 6 h: solo un seguro contra corridas colgadas
JGRAPHT = Path.home() / ".m2" / "repository" / "org" / "jgrapht" / "jgrapht-core" / "1.0.1" / "jgrapht-core-1.0.1.jar"
NOMBRE_VALIDO = re.compile(r"^[A-Za-z0-9_.-]+$")
# Traza que imprime VentanaSimulacion cada 15 s durante la negociacion inicial.
TRAZA = re.compile(r"\[Negociacion inicial\] t\+(\d+)s\s+palas (\d+)/(\d+)\s+camiones (\d+)\s+slots (\d+)")

# Parametros por configuracion -> propiedad del sistema. Para agregar uno nuevo
# (cuando Java lo acepte), basta una linea aqui y su validacion en validar_datos.
PARAMETROS = {
    "deadline_ms": "tds.deadlineMs",
}


class ErrorLote(Exception):
    pass


# ───────────────────────── lectura y validacion ─────────────────────────

def es_entero_positivo(v):
    return isinstance(v, int) and not isinstance(v, bool) and v > 0


def es_numero_positivo(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0


def resolver_escenario(valor):
    if not isinstance(valor, str) or not valor.strip():
        raise ErrorLote("el escenario debe ser pequeno, mediano, grande o el nombre de un .owl")
    archivo = ESCENARIOS.get(valor.strip().lower(), valor.strip())
    if not (REPO / archivo).is_file():
        raise ErrorLote("el escenario '%s' no es pequeno, mediano ni grande, y no existe el archivo %s"
                        % (valor, REPO / archivo))
    return archivo


def leer_lote(ruta):
    """Lee y valida el JSON. Devuelve (opciones generales, lista de corridas)."""
    try:
        with open(ruta, encoding="utf-8") as f:
            datos = json.load(f)
    except FileNotFoundError:
        raise ErrorLote("no existe el archivo %s" % ruta)
    except json.JSONDecodeError as e:
        raise ErrorLote("%s no es un JSON valido: %s" % (ruta, e))
    return validar_datos(datos)


def validar_datos(datos):
    """Valida el contenido del JSON (ya leido). Lo usa tambien interfaz_lotes.py.
    Devuelve (opciones generales, lista de corridas) o lanza ErrorLote con TODOS los errores."""
    if not isinstance(datos, dict):
        raise ErrorLote("el JSON debe ser un objeto con la clave 'configuraciones'")
    errores = []
    gen = {}
    # tiempo maximo: clave ausente = 360 min; null = sin limite
    tmax = datos.get("tiempo_maximo_por_corrida_min", TIEMPO_MAXIMO_POR_DEFECTO_MIN)
    if tmax is not None and not es_numero_positivo(tmax):
        errores.append("tiempo_maximo_por_corrida_min debe ser un numero de minutos > 0 o null (sin limite)")
    gen["tiempo_maximo_por_corrida_min"] = tmax
    # tope de la negociacion: clave ausente = valor del codigo (90 min); null = sin tope
    if "tope_negociacion_min" in datos:
        tope = datos["tope_negociacion_min"]
        if tope is not None and not es_numero_positivo(tope):
            errores.append("tope_negociacion_min debe ser un numero de minutos > 0 o null (sin tope)")
        gen["tope_negociacion_min"] = tope
    nuc = datos.get("nucleos_cpu")
    if nuc is not None:
        nuc = str(nuc).replace(" ", "")
        if not re.match(r"^\d+(-\d+)?(,\d+(-\d+)?)*$", nuc):
            errores.append("nucleos_cpu debe ser como \"2,3\" o \"0-3\", o null")
    gen["nucleos_cpu"] = nuc or None
    mv = datos.get("mostrar_ventana", False)
    if not isinstance(mv, bool):
        errores.append("mostrar_ventana debe ser true o false")
    gen["mostrar_ventana"] = mv is True
    jg = datos.get("jgrapht_jar")
    gen["jgrapht_jar"] = Path(jg).expanduser() if jg else JGRAPHT

    confs = datos.get("configuraciones")
    if not isinstance(confs, list) or not confs:
        errores.append("'configuraciones' debe ser una lista con al menos una configuracion")
        confs = []
    corridas, vistos = [], set()
    for i, c in enumerate(confs, 1):
        donde = "configuracion %d" % i
        if not isinstance(c, dict):
            errores.append("%s: debe ser un objeto {...}" % donde)
            continue
        nombre = c.get("nombre")
        if not isinstance(nombre, str) or not NOMBRE_VALIDO.match(nombre or ""):
            errores.append("%s: 'nombre' es obligatorio y solo puede tener letras, numeros, _ . -" % donde)
            continue
        donde = "configuracion '%s'" % nombre
        if nombre in vistos:
            errores.append("%s: el nombre esta repetido" % donde)
        vistos.add(nombre)
        try:
            escenario = resolver_escenario(c.get("escenario"))
        except ErrorLote as e:
            errores.append("%s: %s" % (donde, e))
            escenario = None
        rep = c.get("repeticiones")
        if not es_entero_positivo(rep):
            errores.append("%s: 'repeticiones' debe ser un entero >= 1" % donde)
            rep = 0
        props = {}
        for clave, prop in PARAMETROS.items():
            v = c.get(clave)
            if v is None:
                continue  # por defecto: el valor del codigo
            if clave == "deadline_ms" and not es_entero_positivo(v):
                errores.append("%s: 'deadline_ms' debe ser un entero > 0 (en ms) o quedar vacio" % donde)
                continue
            props[prop] = str(v)
        if "tope_negociacion_min" in c:  # permite cambiarlo en una configuracion
            t = c["tope_negociacion_min"]
            if t is not None and not es_numero_positivo(t):
                errores.append("%s: 'tope_negociacion_min' debe ser > 0 o null" % donde)
            else:
                props["tds.topeNegociacionMin"] = "null" if t is None else str(t)
        elif "tope_negociacion_min" in gen:
            t = gen["tope_negociacion_min"]
            props["tds.topeNegociacionMin"] = "null" if t is None else str(t)
        desconocidas = set(c) - {"nombre", "escenario", "repeticiones", "tope_negociacion_min"} - set(PARAMETROS)
        if desconocidas:
            errores.append("%s: claves desconocidas %s" % (donde, sorted(desconocidas)))
        ancho = max(2, len(str(rep)))
        for r in range(1, rep + 1):
            corridas.append({"configuracion": nombre, "repeticion": r,
                             "etiqueta": "%s_r%0*d" % (nombre, ancho, r),
                             "escenario": escenario, "propiedades": dict(props)})
    if errores:
        raise ErrorLote("el archivo de experimentos tiene errores:\n  - " + "\n  - ".join(errores))
    return gen, corridas


# ───────────────────────── ejecucion ─────────────────────────

def classpath(gen):
    return os.pathsep.join([str(REPO / "bin"), str(REPO / "lib" / "jade.jar"), str(gen["jgrapht_jar"])])


def comando(c, gen):
    cmd = []
    if gen["nucleos_cpu"]:
        cmd += ["taskset", "-c", gen["nucleos_cpu"]]
    cmd += ["java", "-Dfile.encoding=UTF-8",
            "-Dtds.etiqueta=" + c["etiqueta"], "-Dtds.escenario=" + c["escenario"]]
    cmd += ["-D%s=%s" % kv for kv in sorted(c["propiedades"].items())]
    cmd += ["-Dtds.sinDialogo=true"]
    if not gen.get("mostrar_ventana"):
        cmd += ["-Dtds.sinVentana=true"]   # no se muestra la mina (ver COMO_CORRER.md)
    cmd += ["-cp", classpath(gen), "MainContainer"]
    return cmd


def compilar(gen, escribir):
    if not gen["jgrapht_jar"].is_file():
        raise ErrorLote("no se encontro jgrapht en %s (indica la ruta con \"jgrapht_jar\" en el JSON)"
                        % gen["jgrapht_jar"])
    fuentes = sorted(glob.glob(str(REPO / "src" / "*.java")))
    (REPO / "bin").mkdir(exist_ok=True)
    cp = os.pathsep.join([str(REPO / "lib" / "jade.jar"), str(gen["jgrapht_jar"])])
    escribir("Compilando %d archivos con javac --release 8 en bin/ ..." % len(fuentes))
    r = subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-nowarn", "-d", str(REPO / "bin"),
                        "-cp", cp] + fuentes, cwd=REPO, capture_output=True, text=True)
    if r.returncode != 0:
        raise ErrorLote("la compilacion fallo:\n" + r.stdout + r.stderr)


def estado_de(etiqueta):
    """OK / NO VALIDA segun resumen_plan.json; None si no existe."""
    f = REPO / "corridas" / etiqueta / "resumen_plan.json"
    if not f.is_file():
        return None, False
    try:
        d = json.loads(f.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None, False
    return ("OK" if d.get("corrida_valida") else "NO VALIDA"), bool(d.get("plan_vacio"))


def terminar(proc):
    proc.terminate()
    try:
        proc.wait(15)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def ahora():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def escribir_json(ruta, datos):
    tmp = ruta.with_name(ruta.name + ".tmp")
    tmp.write_text(json.dumps(datos, indent=2, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, ruta)


def ultima_traza(log):
    """Ultima traza de progreso del log de la corrida: (t, palas llenas, palas, camiones, slots) o None."""
    try:
        with open(log, "rb") as f:
            f.seek(0, os.SEEK_END)
            n = f.tell()
            f.seek(max(0, n - 16384))
            texto = f.read().decode("utf-8", "replace")
    except OSError:
        return None
    m = None
    for m in TRAZA.finditer(texto):
        pass
    return tuple(int(x) for x in m.groups()) if m else None


def hms(seg):
    if seg is None:
        return "sin estimacion"
    seg = int(max(0, seg))
    h, r = divmod(seg, 3600)
    return "%d h %02d min" % (h, r // 60) if h else "%d min %02d s" % divmod(r, 60)


class Progreso:
    """Estado vivo del lote: lo escribe en estado.json (para la interfaz) y en
    una linea de la terminal que se actualiza (si la salida es una terminal)."""

    def __init__(self, dir_lote, corridas):
        self.dir = dir_lote
        self.corridas = corridas
        self.estados = ["pendiente"] * len(corridas)
        self.duraciones = {}          # configuracion -> [segundos] de corridas terminadas (OK / NO VALIDA)
        self.actual = None            # indice de la corrida en curso
        self.t0 = None
        self.traza = None
        self.terminal = sys.stdout.isatty()
        self.linea_visible = False
        self.ultimo = 0.0
        self.finalizado = False
        # las que ya existen al empezar se saltaran: no cuentan en la estimacion
        self.se_saltara = [(REPO / "corridas" / c["etiqueta"]).exists() for c in corridas]

    def restante(self):
        """Segundos estimados para terminar el lote, o None (sin estimacion)."""
        total = 0.0
        detener = (self.dir / "DETENER").exists()   # las pendientes ya no se correran
        for i, c in enumerate(self.corridas):
            pendiente = self.estados[i] == "pendiente" and not self.se_saltara[i] and not detener
            en_curso = i == self.actual
            if not (pendiente or en_curso):
                continue
            durs = self.duraciones.get(c["configuracion"])
            if not durs:
                return None
            media = sum(durs) / len(durs)
            total += max(0.0, media - (time.time() - self.t0)) if en_curso else media
        return total

    def datos(self):
        terminadas = sum(e not in ("pendiente", "corriendo") for e in self.estados)
        d = {"carpeta_lote": str(self.dir), "total": len(self.corridas), "terminadas": terminadas,
             "finalizado": self.finalizado, "detener_solicitado": (self.dir / "DETENER").exists(),
             "restante_estimado_s": self.restante(), "actual": None,
             "corridas": [{"etiqueta": c["etiqueta"], "configuracion": c["configuracion"], "estado": e}
                          for c, e in zip(self.corridas, self.estados)]}
        r = d["restante_estimado_s"]
        d["restante_estimado"] = hms(r) if r is not None else "sin estimacion"
        if self.actual is not None:
            a = {"indice": self.actual + 1, "etiqueta": self.corridas[self.actual]["etiqueta"],
                 "transcurrido_s": round(time.time() - self.t0, 1)}
            if self.traza:
                _, llenas, palas, camiones, slots = self.traza
                a.update(palas_llenas=llenas, palas_total=palas, camiones=camiones, slots=slots)
            d["actual"] = a
        return d

    def actualizar(self, log=None, forzar=False):
        if log is not None:
            t = ultima_traza(log)
            if t:
                self.traza = t
        if not forzar and time.time() - self.ultimo < 2:
            return
        self.ultimo = time.time()
        d = self.datos()
        try:
            escribir_json(self.dir / "estado.json", d)
        except OSError:
            pass
        if self.terminal and d["actual"]:
            a = d["actual"]
            palas = ("palas %d/%d  slots %d" % (a["palas_llenas"], a["palas_total"], a["slots"])
                     if "palas_total" in a else "esperando el primer slot")
            linea = "  [%d/%d] %s  %s  %s  | lote %d/%d terminadas, resta %s" % (
                a["indice"], d["total"], a["etiqueta"], palas, hms(a["transcurrido_s"]),
                d["terminadas"], d["total"], d["restante_estimado"])
            sys.stdout.write("\r\033[K" + linea[:200])
            sys.stdout.flush()
            self.linea_visible = True

    def limpiar_linea(self):
        if self.linea_visible:
            sys.stdout.write("\r\033[K")
            sys.stdout.flush()
            self.linea_visible = False


def correr(ruta_json, gen, corridas, dir_lote=None, compilar_antes=True):
    if dir_lote is None:
        dir_lote = REPO / "corridas" / "_lotes" / datetime.now().strftime("%Y%m%d_%H%M%S")
    dir_lote = Path(dir_lote)
    dir_lote.mkdir(parents=True, exist_ok=True)
    prog = Progreso(dir_lote, corridas)
    archivo_log = open(dir_lote / "lote.log", "a", encoding="utf-8")

    def escribir(msg):
        prog.limpiar_linea()
        print(msg, flush=True)
        archivo_log.write(msg + "\n")
        archivo_log.flush()

    if compilar_antes:
        compilar(gen, escribir)
    if gen["nucleos_cpu"] and not shutil.which("taskset"):
        escribir("AVISO: 'taskset' no esta disponible en este sistema; se ignora nucleos_cpu.")
        gen["nucleos_cpu"] = None
    with open(ruta_json, encoding="utf-8") as f:
        registro = {"archivo": str(Path(ruta_json).resolve()), "experimentos": json.load(f),
                    "inicio": ahora(), "fin": None, "corridas": []}
    escribir_json(dir_lote / "lote.json", registro)
    tmax = gen["tiempo_maximo_por_corrida_min"]
    total = len(corridas)
    escribir("Lote: %d corridas. Registro en %s" % (total, dir_lote))
    escribir("Tiempo maximo por corrida: %s" % ("sin limite" if tmax is None else "%g min" % tmax))
    prog.actualizar(forzar=True)

    for n, c in enumerate(corridas, 1):
        i = n - 1
        et = c["etiqueta"]
        if (dir_lote / "DETENER").exists():
            escribir("Se pidio detener el lote: no se lanzan mas corridas.")
            for j in range(i, total):
                prog.estados[j] = "no ejecutada"
            break
        fila = {"etiqueta": et, "configuracion": c["configuracion"], "repeticion": c["repeticion"],
                "escenario": c["escenario"], "propiedades": c["propiedades"]}
        registro["corridas"].append(fila)
        if (REPO / "corridas" / et).exists():
            fila.update(estado="saltada", motivo="corridas/%s ya existe" % et)
            prog.estados[i] = "saltada"
            escribir("[%d/%d] %s: saltada (ya existe)" % (n, total, et))
            escribir_json(dir_lote / "lote.json", registro)
            prog.actualizar(forzar=True)
            continue
        cmd = comando(c, gen)
        log = dir_lote / (et + ".log")
        fila.update(comando=cmd, log=log.name, inicio=ahora())
        escribir("[%d/%d] %s: corriendo ... (log: %s)" % (n, total, et, log))
        escribir_json(dir_lote / "lote.json", registro)
        prog.estados[i] = "corriendo"
        prog.actual, prog.t0, prog.traza = i, time.time(), None
        prog.actualizar(forzar=True)
        t0 = time.time()
        with open(log, "w", encoding="utf-8", errors="replace") as salida:
            proc = subprocess.Popen(cmd, cwd=REPO, stdout=salida, stderr=subprocess.STDOUT)
            colgada = False
            try:
                while proc.poll() is None:
                    if tmax is not None and time.time() - t0 > tmax * 60:
                        colgada = True
                        terminar(proc)
                        break
                    prog.actualizar(log)
                    time.sleep(1)
            except KeyboardInterrupt:
                terminar(proc)
                fila.update(estado="interrumpida", fin=ahora(), duracion_s=round(time.time() - t0, 1),
                            codigo_salida=proc.returncode)
                prog.estados[i] = "interrumpida"
                prog.actual = None
                registro["fin"] = ahora()
                escribir_json(dir_lote / "lote.json", registro)
                prog.finalizado = True
                prog.actualizar(forzar=True)
                escribir("\nLote interrumpido. %s quedo como 'interrumpida'." % et)
                if (REPO / "corridas" / et).exists():
                    escribir("OJO: corridas/%s/ quedo creada a medias; muevela para que se repita al retomar." % et)
                sys.exit(130)
        dur = time.time() - t0
        fila.update(fin=ahora(), duracion_s=round(dur, 1), codigo_salida=proc.returncode)
        if colgada:
            fila["estado"] = "colgada"
            fila["motivo"] = "supero %g min; el proceso se termino" % tmax
        else:
            est, vacio = estado_de(et)
            fila["estado"] = est or "fallida"
            if vacio:
                fila["plan_vacio"] = True
            if est is None:
                fila["motivo"] = "el proceso termino (codigo %s) sin dejar resumen_plan.json; ver el log" % proc.returncode
            else:
                prog.duraciones.setdefault(c["configuracion"], []).append(dur)
        prog.estados[i] = fila["estado"]
        prog.actual = None
        escribir("[%d/%d] %s: %s en %.1f min%s" % (n, total, et, fila["estado"].upper(), dur / 60,
                 " (plan vacio)" if fila.get("plan_vacio") else ""))
        escribir_json(dir_lote / "lote.json", registro)
        prog.actualizar(forzar=True)

    registro["fin"] = ahora()
    registro["detenido"] = (dir_lote / "DETENER").exists()
    escribir_json(dir_lote / "lote.json", registro)
    prog.finalizado = True
    prog.actualizar(forzar=True)
    for linea in resumen(registro["corridas"], prog, dir_lote):
        escribir(linea)
    archivo_log.close()


def resumen(filas, prog, dir_lote):
    grupos = {}
    for f in filas:
        grupos.setdefault(f.get("estado"), []).append(f["etiqueta"])
    no_ejec = [c["etiqueta"] for c, e in zip(prog.corridas, prog.estados) if e == "no ejecutada"]
    lineas = ["", "===== Resumen del lote ====="]
    for est, nombre in (("OK", "OK"), ("NO VALIDA", "NO VALIDAS"), ("colgada", "colgadas"),
                        ("fallida", "fallidas"), ("saltada", "saltadas")):
        lista = grupos.get(est, [])
        lineas.append("  %-14s %3d%s" % (nombre + ":", len(lista),
                                          ("  " + ", ".join(lista)) if lista and est != "OK" else ""))
    if no_ejec:
        lineas.append("  %-14s %3d  (se detuvo el lote)" % ("no ejecutadas:", len(no_ejec)))
    vacios = [f["etiqueta"] for f in filas if f.get("plan_vacio")]
    if vacios:
        lineas.append("  con plan vacio: %s" % ", ".join(vacios))
    lineas.append("Registro: %s" % (dir_lote / "lote.json"))
    return lineas


def simular(gen, corridas):
    print("SIMULACION: no se compila ni se ejecuta nada.")
    tmax = gen["tiempo_maximo_por_corrida_min"]
    print("Tiempo maximo por corrida: %s" % ("sin limite" if tmax is None else "%g min" % tmax))
    print("Nucleos de CPU: %s" % (gen["nucleos_cpu"] or "sin fijar"))
    print("Ventana de la simulacion: %s" % ("visible" if gen["mostrar_ventana"] else "oculta"))
    a_correr = 0
    for n, c in enumerate(corridas, 1):
        existe = (REPO / "corridas" / c["etiqueta"]).exists()
        a_correr += not existe
        print("\n[%d/%d] %s -> %s" % (n, len(corridas), c["etiqueta"],
                                    "SALTARIA (corridas/%s ya existe)" % c["etiqueta"] if existe else "correria"))
        if not existe:
            print("   " + " ".join(comando(c, gen)))
    print("\nTotal: %d corridas, %d se correrian, %d se saltarian." % (len(corridas), a_correr, len(corridas) - a_correr))


def main():
    p = argparse.ArgumentParser(description="Lanza un lote de corridas de TDS-MARL (ver COMO_CORRER.md).")
    p.add_argument("experimentos", help="archivo JSON con las configuraciones")
    p.add_argument("--simular", action="store_true", help="muestra que corridas haria y con que comando, sin ejecutar")
    p.add_argument("--sin-compilar", action="store_true", help="no compila; usa bin/ tal como esta")
    p.add_argument("--carpeta-lote", help="carpeta del registro del lote (por defecto corridas/_lotes/<fecha_hora>)")
    a = p.parse_args()
    try:
        gen, corridas = leer_lote(a.experimentos)
        if a.simular:
            simular(gen, corridas)
            return
        correr(a.experimentos, gen, corridas, a.carpeta_lote, compilar_antes=not a.sin_compilar)
    except ErrorLote as e:
        print("ERROR: %s" % e, file=sys.stderr, flush=True)
        sys.exit(2)


if __name__ == "__main__":
    main()
