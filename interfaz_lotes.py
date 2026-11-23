#!/usr/bin/env python3
"""Ventana para armar y lanzar lotes de corridas de TDS-MARL.

NO reemplaza a correr_lote.py: solo edita el archivo JSON de experimentos (que
sigue siendo la fuente de verdad) y lanza correr_lote.py con ese archivo. El
progreso se lee de corridas/_lotes/<fecha_hora>/estado.json, que escribe el
script. Ver COMO_CORRER.md.

Uso:  python3 interfaz_lotes.py [experimentos.json]
Solo biblioteca estandar (tkinter; en Ubuntu: sudo apt install python3-tk).
"""
import json
import os
import subprocess
import sys
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

import correr_lote

REPO = correr_lote.REPO

# Columnas de la tabla de configuraciones. Para sumar un parametro nuevo basta
# agregar una entrada (y aceptarlo en correr_lote.PARAMETROS):
#   clave del JSON, titulo, tipo ("texto" | "opciones" | "entero" | "entero_opcional"), opciones, ancho
COLUMNAS = [
    ("nombre", "Nombre", "texto", None, 180),
    ("escenario", "Escenario", "opciones", ["pequeno", "mediano", "grande"], 110),
    ("repeticiones", "Repeticiones", "entero", None, 100),
    ("deadline_ms", "deadline_ms (vacío = por defecto)", "entero_opcional", None, 220),
]
TOPE_OPCIONES = ["por defecto del código (90 min)", "sin tope", "minutos:"]


class Interfaz:
    def __init__(self, raiz, archivo=None):
        self.raiz = raiz
        self.archivo = None
        self.proc = None
        self.dir_lote = None
        self.pos_salida = 0
        raiz.title("TDS-MARL - lotes de corridas")
        raiz.geometry("980x760")
        raiz.protocol("WM_DELETE_WINDOW", self.al_cerrar)
        self.construir()
        if archivo:
            self.cargar(Path(archivo))
        else:
            self.nueva_fila({"nombre": "base", "escenario": "pequeno", "repeticiones": 1})
        self.raiz.after(1000, self.sondear)

    # ── construccion ──────────────────────────────────────────────────
    def construir(self):
        r = self.raiz
        barra = ttk.Frame(r, padding=6)
        barra.pack(fill="x")
        for texto, fn in (("Cargar JSON", self.pedir_cargar), ("Guardar JSON", self.guardar),
                          ("Guardar como...", self.guardar_como), ("Simular", self.simular),
                          ("Lanzar", self.lanzar)):
            b = ttk.Button(barra, text=texto, command=fn)
            b.pack(side="left", padx=2)
            if texto == "Lanzar":
                self.b_lanzar = b
        self.b_detener = ttk.Button(barra, text="Detener tras la corrida en curso", command=self.detener,
                                    state="disabled")
        self.b_detener.pack(side="left", padx=12)
        self.l_archivo = ttk.Label(barra, text="(sin archivo)")
        self.l_archivo.pack(side="right")

        gen = ttk.LabelFrame(r, text="Opciones generales", padding=6)
        gen.pack(fill="x", padx=6)
        ttk.Label(gen, text="Tiempo máximo por corrida (min):").grid(row=0, column=0, sticky="w")
        self.v_tmax = tk.StringVar(value="360")
        self.e_tmax = ttk.Entry(gen, textvariable=self.v_tmax, width=8)
        self.e_tmax.grid(row=0, column=1, sticky="w")
        self.v_sin_limite = tk.BooleanVar(value=False)
        ttk.Checkbutton(gen, text="sin límite", variable=self.v_sin_limite,
                        command=self.refrescar_opciones).grid(row=0, column=2, sticky="w")
        ttk.Label(gen, text="  (solo un seguro contra corridas colgadas)").grid(row=0, column=3, sticky="w")
        ttk.Label(gen, text="Tope de la negociación:").grid(row=1, column=0, sticky="w")
        self.v_tope_modo = tk.StringVar(value=TOPE_OPCIONES[1])
        cb = ttk.Combobox(gen, textvariable=self.v_tope_modo, values=TOPE_OPCIONES, state="readonly", width=30)
        cb.grid(row=1, column=1, columnspan=2, sticky="w")
        cb.bind("<<ComboboxSelected>>", lambda e: self.refrescar_opciones())
        self.v_tope = tk.StringVar(value="")
        self.e_tope = ttk.Entry(gen, textvariable=self.v_tope, width=8)
        self.e_tope.grid(row=1, column=3, sticky="w")
        ttk.Label(gen, text="Núcleos de CPU (vacío = sin fijar, ej. 2,3):").grid(row=2, column=0, sticky="w")
        self.v_nucleos = tk.StringVar(value="")
        ttk.Entry(gen, textvariable=self.v_nucleos, width=10).grid(row=2, column=1, sticky="w")
        self.v_ventana = tk.BooleanVar(value=False)
        ttk.Checkbutton(gen, text="Mostrar la ventana de la simulación (la mina)",
                        variable=self.v_ventana).grid(row=3, column=0, columnspan=3, sticky="w")
        self.refrescar_opciones()

        tab = ttk.LabelFrame(r, text="Configuraciones (doble clic en una celda para editarla)", padding=6)
        tab.pack(fill="both", expand=False, padx=6, pady=4)
        self.tabla = ttk.Treeview(tab, columns=[c[0] for c in COLUMNAS], show="headings", height=7,
                                  selectmode="browse")
        for clave, titulo, _, _, ancho in COLUMNAS:
            self.tabla.heading(clave, text=titulo)
            self.tabla.column(clave, width=ancho, anchor="w")
        self.tabla.pack(side="left", fill="both", expand=True)
        self.tabla.bind("<Double-1>", self.editar_celda)
        bot = ttk.Frame(tab)
        bot.pack(side="left", fill="y", padx=6)
        for texto, fn in (("Agregar", lambda: self.nueva_fila()), ("Duplicar", self.duplicar),
                          ("Quitar", self.quitar), ("Subir", lambda: self.mover(-1)), ("Bajar", lambda: self.mover(1))):
            ttk.Button(bot, text=texto, command=fn, width=10).pack(pady=2)

        prog = ttk.LabelFrame(r, text="Progreso", padding=6)
        prog.pack(fill="x", padx=6)
        self.l_lote = ttk.Label(prog, text="Lote: sin lanzar")
        self.l_lote.grid(row=0, column=0, sticky="w")
        self.pb_lote = ttk.Progressbar(prog, length=420)
        self.pb_lote.grid(row=0, column=1, sticky="w", padx=6)
        self.l_corrida = ttk.Label(prog, text="Corrida actual: -")
        self.l_corrida.grid(row=1, column=0, sticky="w")
        self.pb_corrida = ttk.Progressbar(prog, length=420)
        self.pb_corrida.grid(row=1, column=1, sticky="w", padx=6)
        self.l_detalle = ttk.Label(prog, text="")
        self.l_detalle.grid(row=2, column=0, columnspan=2, sticky="w")

        abajo = ttk.Panedwindow(r, orient="horizontal")
        abajo.pack(fill="both", expand=True, padx=6, pady=4)
        f1 = ttk.LabelFrame(abajo, text="Corridas", padding=4)
        self.t_corridas = ttk.Treeview(f1, columns=("etiqueta", "estado"), show="headings")
        self.t_corridas.heading("etiqueta", text="Etiqueta")
        self.t_corridas.heading("estado", text="Estado")
        self.t_corridas.column("etiqueta", width=200)
        self.t_corridas.column("estado", width=100)
        self.t_corridas.pack(fill="both", expand=True)
        abajo.add(f1, weight=1)
        f2 = ttk.LabelFrame(abajo, text="Registro en vivo", padding=4)
        self.texto = tk.Text(f2, height=12, wrap="none", font=("TkFixedFont", 9))
        sb = ttk.Scrollbar(f2, command=self.texto.yview)
        self.texto.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.texto.pack(fill="both", expand=True)
        abajo.add(f2, weight=2)

    def refrescar_opciones(self):
        self.e_tmax.configure(state="disabled" if self.v_sin_limite.get() else "normal")
        self.e_tope.configure(state="normal" if self.v_tope_modo.get() == TOPE_OPCIONES[2] else "disabled")

    # ── tabla ─────────────────────────────────────────────────────────
    def nueva_fila(self, valores=None):
        valores = valores or {"nombre": "config%d" % (len(self.tabla.get_children()) + 1),
                              "escenario": "pequeno", "repeticiones": 1}
        fila = ["" if valores.get(c[0]) is None else str(valores.get(c[0])) for c in COLUMNAS]
        iid = self.tabla.insert("", "end", values=fila)
        self.tabla.selection_set(iid)
        return iid

    def seleccion(self):
        s = self.tabla.selection()
        return s[0] if s else None

    def duplicar(self):
        iid = self.seleccion()
        if not iid:
            return
        vals = list(self.tabla.item(iid, "values"))
        vals[0] = vals[0] + "_copia"
        nuevo = self.tabla.insert("", self.tabla.index(iid) + 1, values=vals)
        self.tabla.selection_set(nuevo)

    def quitar(self):
        iid = self.seleccion()
        if iid:
            self.tabla.delete(iid)

    def mover(self, delta):
        iid = self.seleccion()
        if iid:
            self.tabla.move(iid, "", max(0, self.tabla.index(iid) + delta))

    def editar_celda(self, evento):
        iid = self.tabla.identify_row(evento.y)
        col = self.tabla.identify_column(evento.x)
        if not iid or not col:
            return
        n = int(col[1:]) - 1
        clave, _, tipo, opciones, _ = COLUMNAS[n]
        x, y, w, h = self.tabla.bbox(iid, col)
        valor = tk.StringVar(value=self.tabla.item(iid, "values")[n])
        if tipo == "opciones":
            ed = ttk.Combobox(self.tabla, textvariable=valor, values=opciones)
        else:
            ed = ttk.Entry(self.tabla, textvariable=valor)
        ed.place(x=x, y=y, width=w, height=h)
        ed.focus_set()

        def fijar(_=None):
            vals = list(self.tabla.item(iid, "values"))
            vals[n] = valor.get().strip()
            self.tabla.item(iid, values=vals)
            ed.destroy()
        ed.bind("<Return>", fijar)
        ed.bind("<FocusOut>", fijar)
        ed.bind("<Escape>", lambda e: ed.destroy())
        if tipo == "opciones":
            ed.bind("<<ComboboxSelected>>", fijar)

    # ── JSON <-> ventana ──────────────────────────────────────────────
    def a_datos(self):
        """Contenido de la ventana como dict del JSON. Lanza ValueError con los errores de formato."""
        errores = []

        def numero(texto, nombre, entero=False):
            try:
                v = int(texto) if entero else float(texto)
                return int(v) if not entero and float(v).is_integer() else v
            except ValueError:
                errores.append("%s: '%s' no es un número%s" % (nombre, texto, " entero" if entero else ""))
                return None

        d = {}
        d["tiempo_maximo_por_corrida_min"] = (None if self.v_sin_limite.get()
                                              else numero(self.v_tmax.get().strip(), "Tiempo máximo"))
        modo = self.v_tope_modo.get()
        if modo == TOPE_OPCIONES[1]:
            d["tope_negociacion_min"] = None
        elif modo == TOPE_OPCIONES[2]:
            d["tope_negociacion_min"] = numero(self.v_tope.get().strip(), "Tope de la negociación")
        nuc = self.v_nucleos.get().strip()
        d["nucleos_cpu"] = nuc or None
        if self.v_ventana.get():
            d["mostrar_ventana"] = True
        confs = []
        for k, iid in enumerate(self.tabla.get_children(), 1):
            vals = self.tabla.item(iid, "values")
            c = {}
            for (clave, titulo, tipo, _, _), v in zip(COLUMNAS, vals):
                v = str(v).strip()
                if tipo in ("texto", "opciones"):
                    c[clave] = v
                elif v == "" and tipo == "entero_opcional":
                    continue
                else:
                    c[clave] = numero(v, "fila %d, %s" % (k, titulo), entero=True)
            confs.append(c)
        d["configuraciones"] = confs
        if errores:
            raise ValueError("\n".join(errores))
        return d

    def validar(self):
        """Devuelve el dict listo para guardar, o None (y muestra los errores)."""
        try:
            d = self.a_datos()
            correr_lote.validar_datos(d)
            return d
        except (ValueError, correr_lote.ErrorLote) as e:
            messagebox.showerror("Hay errores", str(e))
            return None

    def desde_datos(self, d):
        tmax = d.get("tiempo_maximo_por_corrida_min", correr_lote.TIEMPO_MAXIMO_POR_DEFECTO_MIN)
        self.v_sin_limite.set(tmax is None)
        self.v_tmax.set("" if tmax is None else str(tmax))
        if "tope_negociacion_min" not in d:
            self.v_tope_modo.set(TOPE_OPCIONES[0])
        elif d["tope_negociacion_min"] is None:
            self.v_tope_modo.set(TOPE_OPCIONES[1])
        else:
            self.v_tope_modo.set(TOPE_OPCIONES[2])
            self.v_tope.set(str(d["tope_negociacion_min"]))
        self.v_nucleos.set(d.get("nucleos_cpu") or "")
        self.v_ventana.set(d.get("mostrar_ventana") is True)
        self.refrescar_opciones()
        for iid in self.tabla.get_children():
            self.tabla.delete(iid)
        for c in d.get("configuraciones", []):
            self.nueva_fila(c)

    def pedir_cargar(self):
        f = filedialog.askopenfilename(initialdir=REPO, filetypes=[("JSON", "*.json")])
        if f:
            self.cargar(Path(f))

    def cargar(self, ruta):
        try:
            d = json.loads(Path(ruta).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            messagebox.showerror("No se pudo cargar", str(e))
            return
        self.desde_datos(d)
        self.archivo = Path(ruta)
        self.l_archivo.configure(text=str(self.archivo))

    def guardar(self):
        if not self.archivo:
            return self.guardar_como()
        d = self.validar()
        if d is None:
            return False
        self.archivo.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        self.l_archivo.configure(text=str(self.archivo))
        return True

    def guardar_como(self):
        if self.validar() is None:
            return False
        f = filedialog.asksaveasfilename(initialdir=REPO, defaultextension=".json", filetypes=[("JSON", "*.json")])
        if not f:
            return False
        self.archivo = Path(f)
        return self.guardar()

    # ── simular y lanzar ──────────────────────────────────────────────
    def simular(self):
        if not self.guardar():
            return
        r = subprocess.run([sys.executable, str(REPO / "correr_lote.py"), str(self.archivo), "--simular"],
                           cwd=REPO, capture_output=True, text=True)
        v = tk.Toplevel(self.raiz)
        v.title("Simulación (no se ejecutó nada)")
        t = tk.Text(v, wrap="none", width=140, height=35, font=("TkFixedFont", 9))
        t.pack(fill="both", expand=True)
        t.insert("end", r.stdout + r.stderr)
        t.configure(state="disabled")

    def lanzar(self):
        if self.corriendo():
            return
        if not self.guardar():
            return
        gen, corridas = correr_lote.validar_datos(json.loads(self.archivo.read_text(encoding="utf-8")))
        if not messagebox.askyesno("Lanzar", "Se lanzarán %d corridas definidas en\n%s\n\n¿Continuar?"
                                   % (len(corridas), self.archivo)):
            return
        self.dir_lote = REPO / "corridas" / "_lotes" / datetime.now().strftime("%Y%m%d_%H%M%S")
        self.dir_lote.mkdir(parents=True, exist_ok=True)
        salida = open(self.dir_lote / "salida_script.txt", "w", encoding="utf-8")
        opciones = {}
        if os.name == "nt":
            opciones["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008  # DETACHED_PROCESS
        else:
            opciones["start_new_session"] = True  # sigue corriendo si se cierra la ventana
        self.proc = subprocess.Popen([sys.executable, "-u", str(REPO / "correr_lote.py"), str(self.archivo),
                                      "--carpeta-lote", str(self.dir_lote)],
                                     cwd=REPO, stdout=salida, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                     **opciones)
        salida.close()
        self.pos_salida = 0
        self.texto.delete("1.0", "end")
        for iid in self.t_corridas.get_children():
            self.t_corridas.delete(iid)
        for c in corridas:
            self.t_corridas.insert("", "end", iid=c["etiqueta"], values=(c["etiqueta"], "pendiente"))
        self.b_lanzar.configure(state="disabled")
        self.b_detener.configure(state="normal", text="Detener tras la corrida en curso")
        self.l_lote.configure(text="Lote: 0 de %d (compilando...)" % len(corridas))

    def corriendo(self):
        return self.proc is not None and self.proc.poll() is None

    def detener(self):
        if self.dir_lote:
            (self.dir_lote / "DETENER").write_text("detener tras la corrida en curso\n", encoding="utf-8")
            self.b_detener.configure(state="disabled", text="Se detendrá al terminar la corrida en curso")

    # ── progreso ──────────────────────────────────────────────────────
    def sondear(self):
        try:
            if self.dir_lote:
                self.leer_salida()
                self.leer_estado()
                if self.proc is not None and self.proc.poll() is not None:
                    self.leer_salida()
                    self.b_lanzar.configure(state="normal")
                    self.b_detener.configure(state="disabled")
                    self.l_corrida.configure(text="Corrida actual: - (lote terminado, código %s)"
                                             % self.proc.returncode)
                    self.pb_corrida["value"] = 0
                    self.proc = None
        finally:
            self.raiz.after(1000, self.sondear)

    def leer_salida(self):
        f = self.dir_lote / "salida_script.txt"
        try:
            with open(f, encoding="utf-8", errors="replace") as h:
                h.seek(self.pos_salida)
                nuevo = h.read()
                self.pos_salida = h.tell()
        except OSError:
            return
        if nuevo:
            self.texto.insert("end", nuevo)
            self.texto.see("end")

    def leer_estado(self):
        try:
            e = json.loads((self.dir_lote / "estado.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        total = max(1, e["total"])
        self.pb_lote["value"] = 100.0 * e["terminadas"] / total
        a = e.get("actual")
        self.l_lote.configure(text="Lote: %d de %d terminadas   |   resta: %s%s" % (
            e["terminadas"], e["total"], e["restante_estimado"],
            "   |   SE DETENDRÁ tras la corrida en curso" if e.get("detener_solicitado") else ""))
        if a:
            self.l_corrida.configure(text="Corrida %d de %d: %s" % (a["indice"], e["total"], a["etiqueta"]))
            if "palas_total" in a:
                self.pb_corrida["value"] = 100.0 * a["palas_llenas"] / max(1, a["palas_total"])
                self.l_detalle.configure(text="Palas con turno lleno: %d de %d   |   slots asignados: %d   |   "
                                         "transcurrido: %s" % (a["palas_llenas"], a["palas_total"], a["slots"],
                                                               correr_lote.hms(a["transcurrido_s"])))
            else:
                self.pb_corrida["value"] = 0
                self.l_detalle.configure(text="Esperando el primer slot   |   transcurrido: %s"
                                         % correr_lote.hms(a["transcurrido_s"]))
        elif e.get("finalizado"):
            self.l_detalle.configure(text="Lote finalizado. Registro: %s" % self.dir_lote)
        for c in e["corridas"]:
            if self.t_corridas.exists(c["etiqueta"]):
                self.t_corridas.item(c["etiqueta"], values=(c["etiqueta"], c["estado"]))

    def al_cerrar(self):
        if not self.corriendo():
            self.raiz.destroy()
            return
        r = messagebox.askyesnocancel(
            "Hay un lote corriendo",
            "Hay un lote corriendo.\n\n"
            "Sí: pedir que se detenga después de la corrida en curso y cerrar la ventana.\n"
            "No: cerrar la ventana y dejar el lote corriendo en segundo plano.\n"
            "Cancelar: volver.\n\n"
            "En ningún caso se interrumpe la corrida en curso.")
        if r is None:
            return
        if r:
            self.detener()
        self.raiz.destroy()


def main():
    raiz = tk.Tk()
    Interfaz(raiz, sys.argv[1] if len(sys.argv) > 1 else None)
    raiz.mainloop()


if __name__ == "__main__":
    main()
