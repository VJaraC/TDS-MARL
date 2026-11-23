import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Guarda y verifica el PLAN INICIAL UNICO (reunion con el profesor, 14-oct-2026).
 *
 * Al tomarse la fotografia del plan inicial (PlanBase.capturar) deja en la
 * carpeta de la corrida:
 *   plan_inicial.csv               el plan en el formato que carga PlanInicialFijo
 *   plan_inicial_verificacion.txt  resumen del plan y resultado de la verificacion
 * Ademas:
 *   - si la corrida NEGOCIO su plan, el plan quedo limpio y el escenario
 *     todavia no tiene plan unico, lo guarda en planes_iniciales/ como el plan
 *     unico del escenario. Si ya tiene uno, NO lo reemplaza: eso se hace a mano
 *     con el main de abajo, para que una corrida de prueba no cambie el plan
 *     de las replicas sin que nadie lo note;
 *   - si la corrida CARGO el plan unico, comprueba que partio exactamente de el.
 *
 * Verificacion (lo mismo que revisa verificar_schedules.py sobre el plan final):
 *   1. la copia del simulador es igual a la agenda de cada camion;
 *   2. cada carga del camion esta en la agenda de su pala, y al reves;
 *   3. cada descarga del camion esta en la agenda de su destino, y al reves;
 *   4. ningun camion hace dos cosas a la vez, y sus tareas forman ciclos
 *      completos (viaje vacio, carga, viaje cargado, descarga);
 *   5. ninguna pala carga a dos camiones a la vez;
 *   6. ningun destino recibe mas descargas a la vez que su capacidad.
 *
 * Para tomar el plan de una corrida ya hecha:
 *   java -cp bin GuardarPlanInicial <etiqueta> [--reemplazar]
 * Usa corridas/<etiqueta>/plan_inicial.csv, o, en las corridas anteriores al
 * 14-oct-2026, la fotografia schedules_por_reprogramacion/*_01.csv. Lo guarda
 * como plan de la flota de ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA.
 * Solo lee y escribe archivos.
 */
public final class GuardarPlanInicial {

    private static final long TURNO_MS = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO;
    private static final String[] CICLO = {"VIAJE_VACIO", "CARGA", "VIAJE_CARGADO", "DESCARGA"};
    private static final String CAMION = RegistroAgendasAgentes.CAMION;
    private static final String PALA = RegistroAgendasAgentes.PALA;
    private static final String DESCARGA = RegistroAgendasAgentes.DESCARGA;

    private GuardarPlanInicial() {}

    // ── Durante la corrida ──────────────────────────────────────────────────

    /** Lo llama PlanBase.capturar, con el plan inicial recien fotografiado. */
    static void alCapturar(String etiqueta, String motivo) {
        try {
            List<PlanInicialFijo.Fila> plan = desdeAgentes();
            List<PlanInicialFijo.Fila> sim = desdeSimulador();
            List<String> problemas = verificar(plan, sim, capacidadesDelSimulador());

            File dir = new File("corridas", etiqueta);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("no se pudo crear " + dir);
            escribir(new File(dir, "plan_inicial.csv"), plan);

            List<String> lineas = new ArrayList<>();
            lineas.add("Plan inicial de la corrida " + etiqueta + " (" + motivo + ")");
            lineas.addAll(resumen(plan));
            lineas.addAll(textoProblemas(problemas));

            File fijo = new File(PlanInicialFijo.archivo());
            String conclusion;
            if (PlanInicialFijo.usar()) {
                List<String> dif = comparar(PlanInicialFijo.leer(fijo), plan);
                conclusion = dif.isEmpty()
                        ? "Partio exactamente del plan unico " + fijo.getPath() + "."
                        : "ATENCION: NO partio exactamente del plan unico " + fijo.getPath() + ":";
                lineas.add(conclusion);
                lineas.addAll(dif);
            } else if (!"negociacion_completa".equals(motivo)) {
                conclusion = "No se guarda como plan unico: la negociacion inicial no termino (" + motivo + ").";
                lineas.add(conclusion);
            } else if (plan.isEmpty()) {
                // Negociacion sin ningun slot (VentanaSimulacion.evaluarCierreSinSlots).
                conclusion = "No se guarda como plan unico: el plan esta vacio.";
                lineas.add(conclusion);
            } else if (!problemas.isEmpty()) {
                conclusion = "No se guarda como plan unico: el plan tiene problemas.";
                lineas.add(conclusion);
            } else if (fijo.exists()) {
                conclusion = "El escenario ya tiene plan unico (" + fijo.getPath() + "); no se reemplaza."
                        + " Para usar este: java -cp bin GuardarPlanInicial " + etiqueta + " --reemplazar";
                lineas.add(conclusion);
            } else {
                guardarComoPlanUnico(plan, etiqueta, motivo);
                conclusion = "Guardado como plan unico del escenario: " + fijo.getPath() + ".";
                lineas.add(conclusion);
            }
            escribirTexto(new File(dir, "plan_inicial_verificacion.txt"), lineas);
            System.out.println("[GuardarPlanInicial] " + (problemas.isEmpty() ? "plan limpio. " : "PLAN CON "
                    + problemas.size() + " PROBLEMAS (ver plan_inicial_verificacion.txt). ") + conclusion);
        } catch (Exception e) {
            System.err.println("[GuardarPlanInicial] no se pudo guardar o verificar el plan inicial: " + e);
        }
    }

    private static List<PlanInicialFijo.Fila> desdeAgentes() {
        List<PlanInicialFijo.Fila> filas = new ArrayList<>();
        for (Map.Entry<String, RegistroAgendasAgentes.Entrada> e : RegistroAgendasAgentes.getAgendas().entrySet()) {
            synchronized (e.getValue().schedule) {
                for (Slot s : e.getValue().schedule.getSlots()) {
                    filas.add(new PlanInicialFijo.Fila(e.getKey(), e.getValue().tipo, s.getMaquina(),
                            s.getOperacion().name(), s.getUbicacion(), s.getHoraInicio(), s.getHoraFin(),
                            s.getCantidadMaterial()));
                }
            }
        }
        ordenar(filas);
        return filas;
    }

    private static List<PlanInicialFijo.Fila> desdeSimulador() {
        List<PlanInicialFijo.Fila> filas = new ArrayList<>();
        for (Map.Entry<String, Schedule> e : RegistroSimulacion.getInstancia().getAgendasCamiones().entrySet()) {
            synchronized (e.getValue()) {
                for (Slot s : e.getValue().getSlots()) {
                    filas.add(new PlanInicialFijo.Fila(e.getKey(), CAMION, s.getMaquina(),
                            s.getOperacion().name(), s.getUbicacion(), s.getHoraInicio(), s.getHoraFin(),
                            s.getCantidadMaterial()));
                }
            }
        }
        return filas;
    }

    private static Map<String, Integer> capacidadesDelSimulador() {
        Map<String, Integer> cap = new HashMap<>();
        for (Map.Entry<String, Integer> e : RegistroSimulacion.getInstancia().getCapacidadDestinos().entrySet()) {
            cap.put(sinAlmohadilla(e.getKey()), e.getValue());
        }
        return cap;
    }

    // ── Desde la consola, con una corrida ya hecha ─────────────────────────

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Uso: java -cp bin GuardarPlanInicial <etiqueta de la corrida> [--reemplazar]");
            System.exit(2);
        }
        String etiqueta = args[0];
        boolean reemplazar = Arrays.asList(args).contains("--reemplazar");
        File dir = new File("corridas", etiqueta);
        File foto = new File(dir, "schedules_por_reprogramacion");
        String motivo = motivoDelIndice(new File(foto, "indice.csv"));

        List<PlanInicialFijo.Fila> plan;
        List<PlanInicialFijo.Fila> sim = null;
        File simulador = new File(foto, "schedule_01.csv");
        if (simulador.exists()) sim = leerCopiaSimulador(simulador);
        File nuevo = new File(dir, "plan_inicial.csv");
        if (nuevo.exists()) {
            plan = PlanInicialFijo.leer(nuevo);
        } else {
            plan = reconstruirDeLaFotografia(new File(foto, "agendas_agentes_01.csv"), sim);
        }
        ordenar(plan);

        List<String> problemas = verificar(plan, sim, leerCapacidades(new File(dir, "recursos.csv")));
        if (!motivo.contains("negociacion_completa") && !motivo.contains("plan_inicial_fijo")) {
            problemas.add("la corrida no arranco con la negociacion inicial completa (" + motivo + ")");
        }
        System.out.println("Plan inicial de la corrida " + etiqueta + " (" + motivo + ")");
        for (String l : resumen(plan)) System.out.println(l);
        for (String l : textoProblemas(problemas)) System.out.println(l);
        if (!problemas.isEmpty()) {
            System.out.println("NO se guardo: el plan tiene problemas.");
            System.exit(1);
        }
        File fijo = new File(PlanInicialFijo.archivo());
        if (fijo.exists() && !reemplazar) {
            System.out.println("NO se guardo: " + fijo.getPath() + " ya existe (usa --reemplazar).");
            System.exit(1);
        }
        guardarComoPlanUnico(plan, etiqueta, motivo);
        System.out.println("Guardado en " + fijo.getPath() + " (" + plan.size() + " filas).");
    }

    /** Corridas anteriores al 14-oct-2026: agendas de los agentes + material de la copia del simulador. */
    private static List<PlanInicialFijo.Fila> reconstruirDeLaFotografia(
            File agendas, List<PlanInicialFijo.Fila> sim) throws IOException {
        Map<String, Double> material = new HashMap<>();
        Map<String, Double> capacidadCamion = new HashMap<>();
        if (sim != null) {
            for (PlanInicialFijo.Fila s : sim) {
                material.put(s.agente + "|" + s.operacion + "|" + s.inicio + "|" + s.fin, s.material);
                if ("CARGA".equals(s.operacion)) capacidadCamion.put(s.agente, s.material);
            }
        }
        List<PlanInicialFijo.Fila> filas = new ArrayList<>();
        for (Map<String, String> r : leerCsv(agendas)) {
            long ini = Long.parseLong(r.get("inicio_ms")), fin = Long.parseLong(r.get("fin_ms"));
            String agente = r.get("agente"), tipo = r.get("tipo"), op = r.get("operacion");
            String contraparte = r.get("contraparte");
            double m;
            if (CAMION.equals(tipo)) {
                Double x = material.get(agente + "|" + op + "|" + ini + "|" + fin);
                m = x != null ? x : "VIAJE_VACIO".equals(op) ? 0.0 : capacidadCamion.getOrDefault(agente, 0.0);
            } else {
                m = capacidadCamion.getOrDefault(contraparte, 0.0);
            }
            filas.add(new PlanInicialFijo.Fila(agente, tipo, contraparte, op, r.get("ubicacion"), ini, fin, m));
        }
        return filas;
    }

    private static List<PlanInicialFijo.Fila> leerCopiaSimulador(File f) throws IOException {
        List<PlanInicialFijo.Fila> filas = new ArrayList<>();
        for (Map<String, String> r : leerCsv(f)) {
            filas.add(new PlanInicialFijo.Fila(r.get("camion"), CAMION, r.get("maquina"), r.get("operacion"),
                    r.get("ubicacion"), Long.parseLong(r.get("inicio_ms")), Long.parseLong(r.get("fin_ms")),
                    Double.parseDouble(r.get("material"))));
        }
        return filas;
    }

    private static Map<String, Integer> leerCapacidades(File recursos) throws IOException {
        Map<String, Integer> cap = new HashMap<>();
        for (Map<String, String> r : leerCsv(recursos)) {
            if ("DESTINO".equals(r.get("tipo"))) {
                cap.put(sinAlmohadilla(r.get("nombre")), Integer.parseInt(r.get("capacidad")));
            }
        }
        return cap;
    }

    private static String motivoDelIndice(File indice) throws IOException {
        if (!indice.exists()) return "sin indice";
        List<Map<String, String>> filas = leerCsv(indice);
        return filas.isEmpty() ? "sin indice" : filas.get(0).get("motivo");
    }

    // ── Verificacion ───────────────────────────────────────────────────────

    /** Lista de problemas del plan. Vacia = plan limpio. sim puede ser null. */
    static List<String> verificar(List<PlanInicialFijo.Fila> plan, List<PlanInicialFijo.Fila> sim,
                                  Map<String, Integer> capacidadDestino) {
        List<String> prob = new ArrayList<>();
        List<PlanInicialFijo.Fila> cam = new ArrayList<>(), pal = new ArrayList<>(), des = new ArrayList<>();
        for (PlanInicialFijo.Fila f : plan) {
            if (CAMION.equals(f.tipo)) cam.add(f);
            else if (PALA.equals(f.tipo)) pal.add(f);
            else des.add(f);
        }

        // 1. Copia del simulador = agenda interna de cada camion.
        if (sim != null) {
            Map<String, Integer> a = contar(cam, f -> f.agente + "|" + f.operacion + "|" + f.contraparte
                    + "|" + f.ubicacion + "|" + f.inicio + "|" + f.fin);
            Map<String, Integer> b = contar(sim, f -> f.agente + "|" + f.operacion + "|" + f.contraparte
                    + "|" + f.ubicacion + "|" + f.inicio + "|" + f.fin);
            for (String k : diferencia(a, b)) prob.add("el camion tiene una tarea que el simulador no tiene: " + legible(k));
            for (String k : diferencia(b, a)) prob.add("el simulador tiene una tarea que el camion no tiene: " + legible(k));
        }

        // 2. Cada carga del camion esta en la agenda de su pala, y al reves.
        List<PlanInicialFijo.Fila> cargas = new ArrayList<>(), descargas = new ArrayList<>();
        for (PlanInicialFijo.Fila f : cam) {
            if ("CARGA".equals(f.operacion)) cargas.add(f);
            if ("DESCARGA".equals(f.operacion)) descargas.add(f);
        }
        Map<String, Integer> a = contar(cargas, f -> f.contraparte + "|" + f.agente + "|" + f.inicio + "|" + f.fin);
        Map<String, Integer> b = contar(pal, f -> f.agente + "|" + f.contraparte + "|" + f.inicio + "|" + f.fin);
        for (String k : diferencia(a, b)) prob.add("carga sin reserva en la pala: " + legible(k));
        for (String k : diferencia(b, a)) prob.add("la pala tiene una carga que el camion no tiene: " + legible(k));

        // 3. Cada descarga del camion esta en la agenda de su destino, y al reves.
        a = contar(descargas, f -> sinAlmohadilla(f.ubicacion) + "|" + f.agente + "|" + f.inicio + "|" + f.fin);
        b = contar(des, f -> f.agente + "|" + f.contraparte + "|" + f.inicio + "|" + f.fin);
        for (String k : diferencia(a, b)) prob.add("descarga sin reserva en el destino: " + legible(k));
        for (String k : diferencia(b, a)) prob.add("el destino tiene una descarga que el camion no tiene: " + legible(k));

        // 4. Agenda de cada camion: sin superposicion y en ciclos completos.
        for (Map.Entry<String, List<PlanInicialFijo.Fila>> e : agrupar(cam).entrySet()) {
            List<PlanInicialFijo.Fila> l = e.getValue();
            for (int i = 1; i < l.size(); i++) {
                if (l.get(i).inicio < l.get(i - 1).fin) {
                    prob.add(e.getKey() + " hace dos cosas a la vez a las " + hms(l.get(i).inicio));
                }
            }
            boolean ciclos = l.size() % 4 == 0;
            for (int i = 0; ciclos && i < l.size(); i++) ciclos = CICLO[i % 4].equals(l.get(i).operacion);
            if (!ciclos) prob.add(e.getKey() + " no tiene ciclos completos (viaje vacio, carga, viaje cargado, descarga)");
        }

        // 5. Palas: una carga a la vez.
        for (Map.Entry<String, List<PlanInicialFijo.Fila>> e : agrupar(pal).entrySet()) {
            if (maxSimultaneos(e.getValue()) > 1) prob.add("la pala " + e.getKey() + " carga a dos camiones a la vez");
        }

        // 6. Destinos: no mas descargas a la vez que su capacidad.
        Map<String, List<PlanInicialFijo.Fila>> porDestino = new TreeMap<>();
        for (PlanInicialFijo.Fila f : descargas) {
            porDestino.computeIfAbsent(sinAlmohadilla(f.ubicacion), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<String, List<PlanInicialFijo.Fila>> e : porDestino.entrySet()) {
            int cap = capacidadDestino.getOrDefault(e.getKey(), 99);
            int m = maxSimultaneos(e.getValue());
            if (m > cap) prob.add("el destino " + e.getKey() + " recibe " + m + " descargas a la vez (capacidad " + cap + ")");
        }
        return prob;
    }

    /** Diferencias entre el plan unico y el plan con que partio la corrida. */
    private static List<String> comparar(List<PlanInicialFijo.Fila> fijo, List<PlanInicialFijo.Fila> corrida) {
        Map<String, Integer> a = contar(fijo, PlanInicialFijo.Fila::clave);
        Map<String, Integer> b = contar(corrida, PlanInicialFijo.Fila::clave);
        List<String> dif = new ArrayList<>();
        List<String> faltan = diferencia(a, b), sobran = diferencia(b, a);
        if (!faltan.isEmpty()) dif.add("  " + faltan.size() + " tareas del plan unico que la corrida no tiene, p. ej. " + legible(faltan.get(0)));
        if (!sobran.isEmpty()) dif.add("  " + sobran.size() + " tareas de la corrida que el plan unico no tiene, p. ej. " + legible(sobran.get(0)));
        return dif;
    }

    private static int maxSimultaneos(List<PlanInicialFijo.Fila> tareas) {
        List<long[]> ev = new ArrayList<>();
        for (PlanInicialFijo.Fila f : tareas) {
            ev.add(new long[]{f.inicio, 1});
            ev.add(new long[]{f.fin, -1});
        }
        // a igual instante, primero sale el que termina
        ev.sort(Comparator.<long[]>comparingLong(x -> x[0]).thenComparingLong(x -> x[1]));
        int n = 0, max = 0;
        for (long[] x : ev) {
            n += (int) x[1];
            max = Math.max(max, n);
        }
        return max;
    }

    // ── Resumen y archivos ─────────────────────────────────────────────────

    private static List<String> resumen(List<PlanInicialFijo.Fila> plan) {
        int operaciones = 0, ciclos = 0;
        double toneladas = 0;
        long ultima = 0;
        Set<String> camiones = new HashSet<>();
        for (PlanInicialFijo.Fila f : plan) {
            if (!CAMION.equals(f.tipo)) continue;
            operaciones++;
            camiones.add(f.agente);
            ultima = Math.max(ultima, f.fin);
            if ("DESCARGA".equals(f.operacion)) {
                ciclos++;
                if (f.fin <= TURNO_MS) toneladas += f.material;
            }
        }
        List<String> l = new ArrayList<>();
        l.add(String.format("  %d operaciones, %d ciclos, %d camiones con plan", operaciones, ciclos, camiones.size()));
        l.add(String.format("  toneladas planificadas en 6 h: %.0f", toneladas));
        l.add("  ultima tarea termina a las " + hms(ultima));
        return l;
    }

    private static List<String> textoProblemas(List<String> problemas) {
        List<String> l = new ArrayList<>();
        if (problemas.isEmpty()) {
            l.add("  limpio: cuatro copias iguales, sin superposiciones, capacidades respetadas");
            return l;
        }
        l.add("  PROBLEMAS (" + problemas.size() + "):");
        for (int i = 0; i < problemas.size() && i < 30; i++) l.add("    - " + problemas.get(i));
        if (problemas.size() > 30) l.add("    ... y " + (problemas.size() - 30) + " mas");
        return l;
    }

    private static void guardarComoPlanUnico(List<PlanInicialFijo.Fila> plan, String etiqueta, String motivo)
            throws IOException {
        File fijo = new File(PlanInicialFijo.archivo());
        File dir = fijo.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("no se pudo crear " + dir);
        escribir(fijo, plan);
        List<String> origen = new ArrayList<>();
        origen.add("Plan inicial unico");
        origen.add("Tomado de la corrida: " + etiqueta + " (" + motivo + ")");
        origen.add("Guardado: " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        origen.addAll(resumen(plan));
        String nombre = fijo.getPath();
        escribirTexto(new File(nombre.substring(0, nombre.length() - 4) + "_origen.txt"), origen);
    }

    /** Orden del archivo: camiones, palas y destinos; por agente y por hora. */
    private static void ordenar(List<PlanInicialFijo.Fila> filas) {
        List<String> orden = Arrays.asList(CAMION, PALA, DESCARGA);
        filas.sort(Comparator.<PlanInicialFijo.Fila>comparingInt(f -> orden.indexOf(f.tipo))
                .thenComparing(f -> f.agente)
                .thenComparingLong(f -> f.inicio));
    }

    private static void escribir(File f, List<PlanInicialFijo.Fila> filas) throws IOException {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            w.println(PlanInicialFijo.CABECERA);
            for (PlanInicialFijo.Fila x : filas) w.println(x.linea());
        }
    }

    private static void escribirTexto(File f, List<String> lineas) throws IOException {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            for (String l : lineas) w.println(l);
        }
    }

    private static List<Map<String, String>> leerCsv(File f) throws IOException {
        List<Map<String, String>> filas = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String cab = r.readLine();
            if (cab == null) return filas;
            String[] col = cab.split(",", -1);
            String linea;
            while ((linea = r.readLine()) != null) {
                if (linea.trim().isEmpty()) continue;
                String[] c = linea.split(",", -1);
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < col.length; i++) m.put(col[i].trim(), i < c.length ? c[i].trim() : "");
                filas.add(m);
            }
        }
        return filas;
    }

    // ── Utilidades ─────────────────────────────────────────────────────────

    private interface Clave { String de(PlanInicialFijo.Fila f); }

    private static Map<String, Integer> contar(List<PlanInicialFijo.Fila> filas, Clave clave) {
        Map<String, Integer> m = new HashMap<>();
        for (PlanInicialFijo.Fila f : filas) m.merge(clave.de(f), 1, Integer::sum);
        return m;
    }

    /** Claves que estan mas veces en a que en b (una por cada vez que sobra). */
    private static List<String> diferencia(Map<String, Integer> a, Map<String, Integer> b) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(a).entrySet()) {
            for (int i = b.getOrDefault(e.getKey(), 0); i < e.getValue(); i++) l.add(e.getKey());
        }
        return l;
    }

    private static Map<String, List<PlanInicialFijo.Fila>> agrupar(List<PlanInicialFijo.Fila> filas) {
        Map<String, List<PlanInicialFijo.Fila>> m = new TreeMap<>();
        for (PlanInicialFijo.Fila f : filas) m.computeIfAbsent(f.agente, k -> new ArrayList<>()).add(f);
        for (List<PlanInicialFijo.Fila> l : m.values()) l.sort(Comparator.comparingLong(f -> f.inicio));
        return m;
    }

    /** Pasa las horas en ms de una clave "a|b|inicio|fin" a h:mm:ss. */
    private static String legible(String clave) {
        String[] p = clave.split("\\|", -1);
        StringBuilder s = new StringBuilder();
        for (String x : p) {
            if (s.length() > 0) s.append(' ');
            s.append(x.matches("\\d{4,}") ? hms(Long.parseLong(x)) : x);
        }
        return s.toString();
    }

    private static String sinAlmohadilla(String s) {
        return s == null ? "" : s.replace("#", "");
    }

    private static String hms(long ms) {
        return Utilidades.longToHHMMSS(Math.max(0, ms));
    }
}
