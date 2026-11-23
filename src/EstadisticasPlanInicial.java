import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Registros y estadisticas del SCHEDULE INICIAL (ver DOCUMENTACION_REGISTROS.md).
 *
 * Se ejecuta UNA vez al final de la corrida, cuando ya se capturo el plan
 * (CierrePlanInicial). Solo LEE archivos que ya existen y el estado en memoria:
 *   corridas/<etiqueta>/plan_inicial.csv   (lo escribe GuardarPlanInicial)
 *   <prefijo>negotiations.csv              (CsvNegotiationsLogger)
 *   <prefijo>messages.csv                  (CsvLogger)
 * y ESCRIBE en corridas/<etiqueta>/:
 *   configuracion.json, negociacion_inicial.csv, resumen_plan.json, resumen_plan.txt
 * mas una fila en corridas/resumen_corridas.csv (acumulativo).
 *
 * No toca ningun logger ni la negociacion.
 */
public final class EstadisticasPlanInicial {

    // ── Copia de parametros de ShovelAgent ─────────────────────────────────
    // TODO: estos valores son COPIAS de las variables locales de
    // ShovelAgent.decideAndSendAcceptRejectMulticriterio (pesos) y del paso de
    // 60 s de ShovelAgent.generaContenidoCFP. No se leen del codigo de la pala:
    // si se cambian alla, hay que sincronizarlos A MANO aca.
    static final double W_ESPERA_PALA     = 2.0;
    static final double W_VIAJE_VACIO     = 1.0;
    static final double W_VIAJE_CARGADO   = 1.0;
    static final double W_ESPERA_DESCARGA = 1.5;
    static final double W_CAPACIDAD       = 1000.0;
    static final long   PASO_BARRIDO_MS   = 60_000L;

    static final long TURNO_MS = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO;
    static final String MOTIVO_ACEPTO = "recibi inform. ganador acepto";
    static final String FMT_FECHA = "yyyy-MM-dd HH:mm:ss.SSS";

    private EstadisticasPlanInicial() {}

    /** Totales que se muestran en el dialogo final. */
    public static final class Resultado {
        long duracionNegociacionMs;
        int negociacionesTotal, negociacionesConGanador;
        int ciclosTotal, camionesConTrabajo, flotaTotal, solapesTotal;
        double toneladasTotal, toneladasEnTurno, usoPromedioPct, minutosSolapadosTotal;
        long mensajesTotal, enviosTotal;
        List<String> advertencias = new ArrayList<>();
    }

    // ── Estructuras de lectura ─────────────────────────────────────────────

    private static final class Tarea {
        String agente, tipo, contraparte, operacion;
        long inicio, fin;
        double material;
    }

    private static final class Negociacion {
        String pala, conversacion, motivo, ganador;
        long inicio, fin, duracion;
        int propuestas, rechazos;
        boolean acepto() { return MOTIVO_ACEPTO.equals(motivo); }
    }

    // ── Punto de entrada ───────────────────────────────────────────────────

    public static Resultado generar(File dir, String motivo, long tCapturaMs, long tFinDetectadoMs)
            throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("no se pudo crear " + dir);
        Resultado r = new Resultado();
        String prefijo = ParametrosConfiguracion.prefijoArchivos();

        List<Tarea> plan = leerPlan(new File(dir, "plan_inicial.csv"));
        List<Negociacion> negs = leerNegociaciones(new File(prefijo + "negotiations.csv"), tCapturaMs, r.advertencias);

        Set<String> palas = new java.util.TreeSet<>(RegistroSimulacion.getInstancia().getUbicacionPalas().keySet());
        Set<String> camiones = new HashSet<>();
        for (int i = 0; i < ParametrosConfiguracion.NUMERODETRUCKS; i++) camiones.add("Truck" + i);

        Map<String, Object> cfg = configuracion(motivo);
        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("etiqueta", ParametrosSimulador.ETIQUETA_ESCENARIO);
        resumen.put("motivo_fin_negociacion", motivo);
        resumen.put("corrida_valida", "negociacion_completa".equals(motivo));

        estadisticasPlan(plan, palas, resumen, r);
        estadisticasNegociacion(negs, tCapturaMs, tFinDetectadoMs, resumen, r);
        estadisticasMensajes(new File(prefijo + "messages.csv"), tCapturaMs, palas, camiones, resumen, r);
        resumen.put("advertencias", new ArrayList<Object>(r.advertencias));

        escribir(new File(dir, "configuracion.json"), Collections.singletonList(json(cfg, 0)));
        escribirNegociaciones(new File(dir, "negociacion_inicial.csv"), negs);
        escribir(new File(dir, "resumen_plan.json"), Collections.singletonList(json(resumen, 0)));
        escribir(new File(dir, "resumen_plan.txt"), texto(resumen));
        agregarFilaResumenCorridas(new File("corridas", "resumen_corridas.csv"), cfg, resumen);
        System.out.println("[EstadisticasPlanInicial] archivos de resumen escritos en " + dir.getPath());
        return r;
    }

    // ── configuracion.json ─────────────────────────────────────────────────

    private static Map<String, Object> configuracion(String motivo) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("etiqueta", ParametrosSimulador.ETIQUETA_ESCENARIO);
        c.put("fecha_hora", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        c.put("escenario", ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA);
        c.put("palas", RegistroSimulacion.getInstancia().getUbicacionPalas().size());
        c.put("camiones", ParametrosConfiguracion.NUMERODETRUCKS);
        c.put("destinos", RegistroSimulacion.getInstancia().getCapacidadDestinos().size());
        c.put("deadline_ms", ParametrosConfiguracion.DEADLINE_MS);
        Map<String, Object> pesos = new LinkedHashMap<>();
        pesos.put("w_espera_pala", W_ESPERA_PALA);
        pesos.put("w_viaje_vacio", W_VIAJE_VACIO);
        pesos.put("w_viaje_cargado", W_VIAJE_CARGADO);
        pesos.put("w_espera_descarga", W_ESPERA_DESCARGA);
        pesos.put("w_capacidad", W_CAPACIDAD);
        c.put("pesos_seleccion_pala", pesos);
        c.put("paso_barrido_ms", PASO_BARRIDO_MS);
        c.put("turno_ms", TURNO_MS);
        c.put("usar_plan_inicial_fijo", ParametrosSimulador.USAR_PLAN_INICIAL_FIJO);
        c.put("metodo_rescheduling", ParametrosSimulador.METODO_RESCHEDULING.name());
        c.put("terminar_tras_plan_inicial", ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL);
        long tope = ParametrosSimulador.TOPE_NEGOCIACION_INICIAL_MS;
        boolean sinTope = tope == ConfiguracionCorrida.SIN_TOPE;
        c.put("tope_negociacion_inicial_ms", sinTope ? null : (Object) tope);
        c.put("tope_negociacion_min", sinTope ? null : (Object) red(tope / 60_000.0));
        c.put("origen_parametros", ConfiguracionCorrida.origen());
        c.put("sin_dialogo", ConfiguracionCorrida.sinDialogo());
        c.put("sin_ventana", ConfiguracionCorrida.sinVentana());
        c.put("java_version", System.getProperty("java.version"));
        String commit = git("rev-parse", "HEAD");
        c.put("git_commit", commit == null ? null : commit.trim());
        String estado = git("status", "--porcelain");
        c.put("git_sucio", estado == null ? null : (Object) !estado.trim().isEmpty());
        c.put("llm_modelo", null);
        c.put("llm_recomendacion", null);
        c.put("llm_latencia_ms", null);
        return c;
    }

    /** Salida de un comando git, o null si git no esta o falla. */
    private static String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            Collections.addAll(cmd, args);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder s = new StringBuilder();
            try (BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = b.readLine()) != null) s.append(l).append('\n');
            }
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Plan ───────────────────────────────────────────────────────────────

    private static List<Tarea> leerPlan(File f) throws IOException {
        List<Tarea> l = new ArrayList<>();
        try (BufferedReader r = lector(f)) {
            String linea = r.readLine();   // cabecera (PlanInicialFijo.CABECERA)
            while ((linea = r.readLine()) != null) {
                if (linea.trim().isEmpty()) continue;
                String[] c = linea.split(",", -1);
                Tarea t = new Tarea();
                t.agente = c[0]; t.tipo = c[1]; t.contraparte = c[2]; t.operacion = c[3];
                t.inicio = Long.parseLong(c[5].trim());
                t.fin = Long.parseLong(c[6].trim());
                t.material = Double.parseDouble(c[7].trim());
                l.add(t);
            }
        }
        return l;
    }

    private static void estadisticasPlan(List<Tarea> plan, Set<String> palas, Map<String, Object> out, Resultado r) {
        int filasCamion = 0, ciclosTotal = 0, ciclosTurno = 0;
        double tonTotal = 0, tonTurno = 0, tonDescargadas = 0;
        Set<String> conTrabajo = new HashSet<>();
        Map<String, List<long[]>> cargasPorPala = new TreeMap<>();
        Map<String, Double> tonPorPala = new HashMap<>();
        for (String p : palas) cargasPorPala.put(p, new ArrayList<>());

        for (Tarea t : plan) {
            if (!RegistroAgendasAgentes.CAMION.equals(t.tipo)) continue;
            filasCamion++;
            if ("CARGA".equals(t.operacion)) {
                ciclosTotal++;
                tonTotal += t.material;
                if (t.fin <= TURNO_MS) { ciclosTurno++; tonTurno += t.material; }
                conTrabajo.add(t.agente);
                cargasPorPala.computeIfAbsent(t.contraparte, k -> new ArrayList<>()).add(new long[]{t.inicio, t.fin});
                tonPorPala.merge(t.contraparte, t.material, Double::sum);
            } else if ("DESCARGA".equals(t.operacion) && t.fin <= TURNO_MS) {
                tonDescargadas += t.material;
            }
        }
        int flota = ParametrosConfiguracion.NUMERODETRUCKS;

        out.put("filas_plan_inicial", plan.size());
        out.put("tareas_camiones", filasCamion);
        out.put("toneladas_planificadas", red(tonTotal));
        out.put("toneladas_planificadas_en_turno", red(tonTurno));
        out.put("toneladas_descargadas_en_turno", red(tonDescargadas));
        out.put("ciclos_planificados", ciclosTotal);
        out.put("ciclos_planificados_en_turno", ciclosTurno);
        out.put("plan_vacio", ciclosTotal == 0);
        out.put("camiones_con_trabajo", conTrabajo.size());
        out.put("flota_total", flota);
        out.put("pct_flota_con_trabajo", red(flota == 0 ? 0 : 100.0 * conTrabajo.size() / flota));

        List<Object> porPala = new ArrayList<>();
        double sumaUso = 0, sumaOcupado = 0;
        int solapesTotal = 0;
        double minSolTotal = 0;
        for (Map.Entry<String, List<long[]>> e : cargasPorPala.entrySet()) {
            List<long[]> iv = e.getValue();
            iv.sort((a, b) -> Long.compare(a[0], b[0]));
            double ocupadoMin = union(iv, 0, TURNO_MS) / 60_000.0;
            double ociosoMin = TURNO_MS / 60_000.0 - ocupadoMin;
            double uso = 100.0 * ocupadoMin / (TURNO_MS / 60_000.0);
            int solapes = 0;
            long msSolapados = 0;
            for (int i = 0; i < iv.size(); i++) {
                for (int j = i + 1; j < iv.size(); j++) {
                    long ini = Math.max(iv.get(i)[0], iv.get(j)[0]);
                    long fin = Math.min(iv.get(i)[1], iv.get(j)[1]);
                    if (fin > ini) { solapes++; msSolapados += fin - ini; }
                }
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pala", e.getKey());
            p.put("cargas", iv.size());
            p.put("toneladas", red(tonPorPala.getOrDefault(e.getKey(), 0.0)));
            p.put("tiempo_ocupado_min", red(ocupadoMin));
            p.put("tiempo_ocioso_min", red(ociosoMin));
            p.put("uso_pct", red(uso));
            p.put("solapes", solapes);
            p.put("minutos_solapados", red(msSolapados / 60_000.0));
            porPala.add(p);
            sumaUso += uso;
            sumaOcupado += ocupadoMin;
            solapesTotal += solapes;
            minSolTotal += msSolapados / 60_000.0;
        }
        int nPalas = cargasPorPala.size();
        out.put("palas", porPala);
        out.put("uso_promedio_palas_pct", red(nPalas == 0 ? 0 : sumaUso / nPalas));
        out.put("tiempo_ocupado_promedio_min", red(nPalas == 0 ? 0 : sumaOcupado / nPalas));
        out.put("tiempo_ocioso_promedio_min", red(nPalas == 0 ? 0 : TURNO_MS / 60_000.0 - sumaOcupado / nPalas));
        out.put("solapes_total", solapesTotal);
        out.put("minutos_solapados_total", red(minSolTotal));

        r.ciclosTotal = ciclosTotal;
        r.toneladasTotal = tonTotal;
        r.toneladasEnTurno = tonTurno;
        r.camionesConTrabajo = conTrabajo.size();
        r.flotaTotal = flota;
        r.usoPromedioPct = nPalas == 0 ? 0 : sumaUso / nPalas;
        r.solapesTotal = solapesTotal;
        r.minutosSolapadosTotal = minSolTotal;
    }

    /** Largo de la union de intervalos (ordenados por inicio), recortados a [desde, hasta]. */
    private static long union(List<long[]> iv, long desde, long hasta) {
        long total = 0, curIni = -1, curFin = -1;
        for (long[] x : iv) {
            long a = Math.max(x[0], desde), b = Math.min(x[1], hasta);
            if (b <= a) continue;
            if (curFin < 0 || a > curFin) {
                if (curFin >= 0) total += curFin - curIni;
                curIni = a; curFin = b;
            } else if (b > curFin) {
                curFin = b;
            }
        }
        if (curFin >= 0) total += curFin - curIni;
        return total;
    }

    // ── Negociaciones ──────────────────────────────────────────────────────

    /**
     * Filas de negotiations.csv de la FASE INICIAL: t_sim_ms = -1 y que
     * terminaron antes de la captura. Columnas por posicion (encabezado de
     * CsvNegotiationsLogger): 0 timestamp, 1 agent, 2 inicio, 3 fin,
     * 4 conversationId, 5 motivo, 6 duracion, 7 ganador, 8 proposes,
     * 9 refuses, 10 informs, 11 cancels, 12 t_sim_ms.
     */
    private static List<Negociacion> leerNegociaciones(File f, long tCaptura, List<String> adv) throws IOException {
        List<Negociacion> l = new ArrayList<>();
        if (!f.exists()) {
            adv.add("no existe " + f.getName() + " (no se registraron negociaciones)");
            return l;
        }
        int malas = 0;
        try (BufferedReader r = lector(f)) {
            String linea = r.readLine();
            while ((linea = r.readLine()) != null) {
                if (linea.trim().isEmpty()) continue;
                String[] c = linea.split(",", -1);
                if (c.length < 13) { malas++; continue; }
                try {
                    if (Long.parseLong(c[12].trim()) != -1L) continue;
                    Negociacion n = new Negociacion();
                    n.pala = c[1].trim();
                    n.inicio = Long.parseLong(c[2].trim());
                    n.fin = Long.parseLong(c[3].trim());
                    if (n.fin > tCaptura) continue;
                    n.conversacion = c[4].trim();
                    n.motivo = c[5].trim();
                    n.duracion = Long.parseLong(c[6].trim());
                    n.ganador = c[7].trim();
                    n.propuestas = Integer.parseInt(c[8].trim());
                    n.rechazos = Integer.parseInt(c[9].trim());
                    l.add(n);
                } catch (NumberFormatException e) {
                    malas++;
                }
            }
        }
        if (malas > 0) adv.add(malas + " filas de " + f.getName() + " no se pudieron leer y se omitieron");
        return l;
    }

    private static void estadisticasNegociacion(List<Negociacion> negs, long tCaptura, long tFinDetectado,
                                                Map<String, Object> out, Resultado r) {
        long minIni = Long.MAX_VALUE, maxFin = Long.MIN_VALUE;
        int conGanador = 0, sinPropMotivo = 0, sinPropCero = 0;
        Map<String, Integer> porConv = new HashMap<>();
        Map<String, Integer> porMotivo = new TreeMap<>();
        for (Negociacion n : negs) {
            minIni = Math.min(minIni, n.inicio);
            maxFin = Math.max(maxFin, n.fin);
            if (n.acepto()) conGanador++;
            if (n.motivo.contains("No hubieron propuestas")) sinPropMotivo++;
            if (n.propuestas == 0) sinPropCero++;
            porConv.merge(n.conversacion, 1, Integer::sum);
            porMotivo.merge(n.motivo, 1, Integer::sum);
        }
        boolean hay = !negs.isEmpty();
        long pura = hay ? maxFin - minIni : 0;
        out.put("duracion_negociacion_inicial_ms", hay ? (Object) pura : null);
        out.put("espera_copias_ms", tFinDetectado > 0 ? (Object) (tCaptura - tFinDetectado) : null);
        out.put("duracion_hasta_captura_ms", hay ? (Object) (tCaptura - minIni) : null);
        out.put("inicio_primera_negociacion", hay ? fecha(minIni) : null);
        out.put("fin_ultima_negociacion", hay ? fecha(maxFin) : null);
        out.put("captura_plan", fecha(tCaptura));
        out.put("negociaciones_total", negs.size());
        out.put("negociaciones_con_ganador", conGanador);
        out.put("negociaciones_sin_propuestas", sinPropMotivo);
        out.put("negociaciones_con_0_propuestas_recibidas", sinPropCero);
        out.put("negociaciones_por_motivo", new LinkedHashMap<String, Object>(porMotivo));

        int repetidos = 0, filasExtra = 0;
        for (int v : porConv.values()) if (v > 1) { repetidos++; filasExtra += v - 1; }
        out.put("conversationid_repetidos", repetidos);
        if (repetidos > 0) {
            r.advertencias.add(repetidos + " conversationId aparecen mas de una vez en negotiations.csv ("
                    + filasExtra + " filas extra); se cuentan todas las filas, sin deduplicar");
        }
        r.duracionNegociacionMs = pura;
        r.negociacionesTotal = negs.size();
        r.negociacionesConGanador = conGanador;
    }

    private static void escribirNegociaciones(File f, List<Negociacion> negs) throws IOException {
        List<String> l = new ArrayList<>();
        l.add("pala,inicio,fin,duracion_ms,propuestas,rechazos,ganador,acepto,motivo_cierre,id_conversacion");
        for (Negociacion n : negs) {
            l.add(String.join(",", n.pala, fecha(n.inicio), fecha(n.fin), Long.toString(n.duracion),
                    Integer.toString(n.propuestas), Integer.toString(n.rechazos),
                    "null".equals(n.ganador) ? "" : n.ganador, Boolean.toString(n.acepto()),
                    n.motivo, n.conversacion));
        }
        escribir(f, l);
    }

    // ── Mensajes ───────────────────────────────────────────────────────────

    /**
     * Criterio (ver DOCUMENTACION_REGISTROS.md): cada ENTREGA (emisor -> un
     * receptor) se cuenta una vez, desde el lado del EMISOR:
     *   - fila SEND de una pala: un mensaje por cada receptor de la columna
     *     receivers (un CFP a 15 camiones = 15);
     *   - fila SEND de un camion: 1. Los camiones siempre envian a UN receptor
     *     (createReply o un solo addReceiver), pero TruckAgent.getReceiverString
     *     deja la columna receivers vacia (usa concat sin asignar el resultado);
     *   - punto de descarga: no registra SEND; cada respuesta AGREE deja una fila
     *     descriptiva propia ("AGREE descarga ...", performative AGREE).
     *   envios = filas SEND (un CFP a 15 camiones = 1 envio).
     * Control cruzado, desde el lado del RECEPTOR: filas RECEIVE (palas y
     * camiones) + filas descriptivas del punto de descarga que prueban que
     * recibio un REQUEST ("AGREE descarga ...") o un INFORM ("INFORM recibido
     * ..."). Ambas cifras deberian coincidir.
     * Se ignoran las demas filas (no son mensajes).
     */
    private static void estadisticasMensajes(File f, long tCaptura, Set<String> palas, Set<String> camiones,
                                             Map<String, Object> out, Resultado r) throws IOException {
        if (!f.exists()) {
            r.advertencias.add("no existe " + f.getName() + " (no se registraron mensajes)");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat(FMT_FECHA);
        long entregas = 0, envios = 0, receives = 0, recibidosDescarga = 0, otras = 0, posteriores = 0;
        long sendsSinReceptores = 0, sendsSinReceptoresPala = 0;
        Map<String, Long> entregasPorTipo = new TreeMap<>();
        Map<String, Long> enviosPorTipo = new TreeMap<>();
        try (BufferedReader rd = lector(f)) {
            String linea = rd.readLine();
            while ((linea = rd.readLine()) != null) {
                if (linea.trim().isEmpty()) continue;
                String[] c = linea.split(",", -1);
                if (c.length < 9) { otras++; continue; }
                String tsim = c[c.length - 1].trim();
                if (!"-1".equals(tsim)) continue;
                try {
                    if (fmt.parse(c[0]).getTime() > tCaptura) { posteriores++; continue; }
                } catch (ParseException e) {
                    otras++;
                    continue;
                }
                String agente = c[1].trim();
                String accion = c[2];
                String perf = c[5].trim();
                boolean esDescarga = !palas.contains(agente) && !camiones.contains(agente)
                        && !"ReschedulingAgent".equals(agente);
                if ("SEND".equals(accion)) {
                    envios++;
                    enviosPorTipo.merge(perf, 1L, Long::sum);
                    int n = 0;
                    for (String rec : c[4].trim().split("\\s+")) if (!rec.isEmpty()) n++;
                    // Camion: columna vacia por el defecto de getReceiverString -> 1 receptor.
                    // Pala: la columna es fiel; vacia = envio sin receptores (p. ej. un
                    // REJECT_PROPOSAL cuando no hubo perdedores), no se entrega a nadie.
                    if (n == 0 && camiones.contains(agente)) { n = 1; sendsSinReceptores++; }
                    else if (n == 0) sendsSinReceptoresPala++;
                    entregas += n;
                    if (n > 0) entregasPorTipo.merge(perf, (long) n, Long::sum);
                } else if ("RECEIVE".equals(accion)) {
                    receives++;
                } else if (esDescarga && accion.startsWith("AGREE descarga") && "AGREE".equals(perf)) {
                    entregas++;                       // el punto de descarga envio un AGREE
                    entregasPorTipo.merge("AGREE", 1L, Long::sum);
                    recibidosDescarga++;              // ... porque recibio un REQUEST
                } else if (esDescarga && accion.startsWith("INFORM recibido") && "INFORM".equals(perf)) {
                    recibidosDescarga++;              // el punto de descarga recibio un INFORM
                } else {
                    otras++;
                }
            }
        }
        long control = receives + recibidosDescarga;
        out.put("mensajes_total", entregas);
        out.put("mensajes_por_tipo", new LinkedHashMap<String, Object>(entregasPorTipo));
        out.put("envios_total", envios);
        out.put("envios_por_tipo", new LinkedHashMap<String, Object>(enviosPorTipo));
        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("entregas_lado_emisor", entregas);
        cc.put("entregas_lado_receptor", control);
        cc.put("diferencia", entregas - control);
        cc.put("filas_receive", receives);
        cc.put("recepciones_punto_descarga", recibidosDescarga);
        cc.put("filas_send_camion_sin_receptores_contadas_como_1", sendsSinReceptores);
        cc.put("filas_send_pala_sin_receptores_contadas_como_0", sendsSinReceptoresPala);
        out.put("control_cruzado_mensajes", cc);
        out.put("filas_messages_no_mensaje", otras);
        if (entregas != control) {
            r.advertencias.add("control cruzado de mensajes no calza: " + entregas + " entregas contadas desde el emisor vs "
                    + control + " desde el receptor (diferencia " + (entregas - control) + ")");
        }
        if (posteriores > 0) {
            r.advertencias.add(posteriores + " filas de messages.csv posteriores a la captura del plan se omitieron");
        }
        r.mensajesTotal = entregas;
        r.enviosTotal = envios;
    }

    // ── resumen_plan.txt ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<String> texto(Map<String, Object> m) {
        List<String> l = new ArrayList<>();
        boolean valida = Boolean.TRUE.equals(m.get("corrida_valida"));
        l.add("RESUMEN DEL PLAN INICIAL - " + m.get("etiqueta"));
        l.add("==================================================");
        l.add(valida ? "Corrida VALIDA (la negociacion inicial termino con todas las palas con el turno lleno)."
                : "CORRIDA NO VALIDA: la negociacion termino por '" + m.get("motivo_fin_negociacion")
                  + "', no por 'negociacion_completa'. No comparar con las demas.");
        if (Boolean.TRUE.equals(m.get("plan_vacio"))) {
            l.add("PLAN VACIO: ninguna negociacion tuvo ganador (ningun camion recibio una carga).");
        }
        l.add("");
        l.add("PRODUCCION PLANIFICADA");
        l.add(String.format(Locale.ROOT, "  Toneladas planificadas (todas las cargas):      %s t", m.get("toneladas_planificadas")));
        l.add(String.format(Locale.ROOT, "  Toneladas planificadas (cargas dentro de 6 h):  %s t", m.get("toneladas_planificadas_en_turno")));
        l.add(String.format(Locale.ROOT, "  Toneladas descargadas dentro de 6 h:            %s t", m.get("toneladas_descargadas_en_turno")));
        l.add(String.format(Locale.ROOT, "  Ciclos planificados: %s (dentro de 6 h: %s)", m.get("ciclos_planificados"), m.get("ciclos_planificados_en_turno")));
        l.add(String.format(Locale.ROOT, "  Camiones con trabajo: %s de %s (%s %%)", m.get("camiones_con_trabajo"), m.get("flota_total"), m.get("pct_flota_con_trabajo")));
        l.add("");
        l.add("PALAS (turno de 360 min)");
        l.add(String.format(Locale.ROOT, "  %-10s %7s %10s %10s %8s %8s %10s", "pala", "cargas", "ocupado", "ocioso", "uso %", "solapes", "min solap"));
        for (Object o : (List<Object>) m.get("palas")) {
            Map<String, Object> p = (Map<String, Object>) o;
            l.add(String.format(Locale.ROOT, "  %-10s %7s %10s %10s %8s %8s %10s", p.get("pala"), p.get("cargas"),
                    p.get("tiempo_ocupado_min"), p.get("tiempo_ocioso_min"), p.get("uso_pct"),
                    p.get("solapes"), p.get("minutos_solapados")));
        }
        l.add(String.format(Locale.ROOT, "  Uso promedio: %s %%   Solapes: %s pares, %s min",
                m.get("uso_promedio_palas_pct"), m.get("solapes_total"), m.get("minutos_solapados_total")));
        l.add("");
        l.add("NEGOCIACION INICIAL");
        l.add("  Duracion (primer inicio -> ultimo fin): " + ms(m.get("duracion_negociacion_inicial_ms")));
        l.add("  Espera de copias antes de la captura:   " + ms(m.get("espera_copias_ms")));
        l.add("  Primer inicio -> captura del plan:      " + ms(m.get("duracion_hasta_captura_ms")));
        l.add("  Negociaciones: " + m.get("negociaciones_total") + ", con ganador que acepto: "
                + m.get("negociaciones_con_ganador") + ", sin propuestas validas: " + m.get("negociaciones_sin_propuestas")
                + " (con 0 propuestas recibidas: " + m.get("negociaciones_con_0_propuestas_recibidas") + ")");
        Object pm = m.get("negociaciones_por_motivo");
        if (pm instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) pm).entrySet()) {
                l.add("    " + e.getValue() + "  " + e.getKey());
            }
        }
        l.add("");
        l.add("MENSAJES (fase inicial)");
        l.add("  Entregas (cada mensaje a cada receptor): " + m.get("mensajes_total")
                + "   Envios (filas SEND): " + m.get("envios_total"));
        Object pt = m.get("mensajes_por_tipo");
        if (pt instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) pt).entrySet()) {
                l.add("    " + e.getKey() + ": " + e.getValue());
            }
        }
        List<Object> adv = (List<Object>) m.get("advertencias");
        l.add("");
        l.add("ADVERTENCIAS: " + (adv == null || adv.isEmpty() ? "ninguna" : ""));
        if (adv != null) for (Object a : adv) l.add("  - " + a);
        l.add("");
        l.add("Definiciones y unidades: DOCUMENTACION_REGISTROS.md");
        return l;
    }

    private static String ms(Object v) {
        if (!(v instanceof Number)) return "sin dato";
        long x = ((Number) v).longValue();
        return String.format(Locale.ROOT, "%d ms (%.1f min)", x, x / 60_000.0);
    }

    // ── corridas/resumen_corridas.csv ──────────────────────────────────────

    private static final String[] COLUMNAS_CFG = {"etiqueta", "fecha_hora", "escenario", "palas", "camiones",
            "destinos", "deadline_ms", "tope_negociacion_min", "usar_plan_inicial_fijo", "metodo_rescheduling",
            "git_commit", "git_sucio"};
    private static final String[] COLUMNAS_RES = {"motivo_fin_negociacion", "corrida_valida", "plan_vacio",
            "toneladas_planificadas", "toneladas_planificadas_en_turno", "toneladas_descargadas_en_turno",
            "ciclos_planificados", "ciclos_planificados_en_turno", "camiones_con_trabajo", "flota_total",
            "pct_flota_con_trabajo", "uso_promedio_palas_pct", "solapes_total", "minutos_solapados_total",
            "duracion_negociacion_inicial_ms", "espera_copias_ms", "duracion_hasta_captura_ms",
            "negociaciones_total", "negociaciones_con_ganador", "negociaciones_sin_propuestas",
            "mensajes_total", "envios_total"};

    private static void agregarFilaResumenCorridas(File f, Map<String, Object> cfg, Map<String, Object> res)
            throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("no se pudo crear " + dir);
        List<String> cab = new ArrayList<>();
        Collections.addAll(cab, COLUMNAS_CFG);
        Collections.addAll(cab, COLUMNAS_RES);
        actualizarCabecera(f, cab);
        boolean nuevo = !f.exists();
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
            if (nuevo) w.println(String.join(",", cab));
            List<String> fila = new ArrayList<>();
            for (String k : COLUMNAS_CFG) fila.add(celda(cfg.get(k)));
            for (String k : COLUMNAS_RES) fila.add(celda(res.get(k)));
            w.println(String.join(",", fila));
        }
    }

    /**
     * Si resumen_corridas.csv tiene un encabezado de una version anterior (con
     * menos columnas), lo reescribe con el encabezado actual: cada fila vieja
     * conserva sus valores por nombre de columna y las columnas nuevas quedan
     * vacias. No se pierde ningun dato.
     */
    private static void actualizarCabecera(File f, List<String> cab) throws IOException {
        if (!f.exists()) return;
        List<String> lineas = new ArrayList<>();
        try (BufferedReader r = lector(f)) {
            String l;
            while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) lineas.add(l);
        }
        if (lineas.isEmpty() || lineas.get(0).equals(String.join(",", cab))) return;
        String[] vieja = lineas.get(0).split(",", -1);
        List<String> nuevas = new ArrayList<>();
        nuevas.add(String.join(",", cab));
        for (int i = 1; i < lineas.size(); i++) {
            String[] c = lineas.get(i).split(",", -1);
            Map<String, String> m = new HashMap<>();
            for (int j = 0; j < vieja.length && j < c.length; j++) m.put(vieja[j], c[j]);
            List<String> fila = new ArrayList<>();
            for (String k : cab) fila.add(m.getOrDefault(k, ""));
            nuevas.add(String.join(",", fila));
        }
        escribir(f, nuevas);
        System.out.println("[EstadisticasPlanInicial] " + f.getPath() + ": encabezado actualizado a la version actual"
                + " (columnas nuevas vacias en las filas anteriores)");
    }

    private static String celda(Object v) {
        return v == null ? "" : v.toString().replace(",", ";");
    }

    // ── Utilidades ─────────────────────────────────────────────────────────

    private static double red(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static String fecha(long ms) {
        return new SimpleDateFormat(FMT_FECHA).format(new Date(ms));
    }

    private static BufferedReader lector(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
    }

    private static void escribir(File f, List<String> lineas) throws IOException {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            for (String l : lineas) w.println(l);
        }
    }

    /** JSON minimo (Map, List, String, Number, Boolean, null), con sangria. */
    @SuppressWarnings("unchecked")
    static String json(Object v, int nivel) {
        String sang = repetir("  ", nivel + 1), fin = repetir("  ", nivel);
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) return v.toString();
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
        }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) return "{}";
            StringBuilder s = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                s.append(sang).append(cadena(e.getKey())).append(": ").append(json(e.getValue(), nivel + 1));
                s.append(++i < m.size() ? ",\n" : "\n");
            }
            return s.append(fin).append('}').toString();
        }
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) return "[]";
            StringBuilder s = new StringBuilder("[\n");
            for (int i = 0; i < l.size(); i++) {
                s.append(sang).append(json(l.get(i), nivel + 1)).append(i + 1 < l.size() ? ",\n" : "\n");
            }
            return s.append(fin).append(']').toString();
        }
        return cadena(v.toString());
    }

    private static String cadena(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    private static String repetir(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
