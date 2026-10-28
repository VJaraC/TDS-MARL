import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Archivo ACUMULATIVO de KPIs por corrida ("kpis_corridas.csv").
 *
 * A diferencia de las auditorías (que se sobrescriben en cada corrida), este
 * archivo se va llenando con UNA FILA por simulación terminada, para poder
 * comparar escenarios y poblar la tabla de rescheduling (igual que hicimos
 * antes). NO se borra entre corridas.
 *
 * Métricas calculadas sobre el plan que produce la negociación:
 *   - num_camiones, total_slots, total_cargas, total_descargas, material_total
 *   - makespan (hora de fin del último slot)
 *   - uso_max_palas / uso_max_destino: máximo número de cargas / descargas
 *     simultáneas (cuántos "servidores" se usan a la vez en el pico).
 *   - cola_max_palas / cola_max_destino: máximo número de camiones esperando
 *     a la vez en un punto de carga / descarga.
 *   - espera_max: mayor espera individual de un camión antes de ser atendido.
 *   - slots_retrasados_pct: % de slots que terminan tarde respecto al plan.
 *
 * En la CAPA 1 (sin incertidumbre) real == plan, así que cola_max_*,
 * espera_max y slots_retrasados_pct deben dar 0. Si no dan 0, hay un problema
 * en el plan (p. ej. solapamientos que no respetan capacidad).
 */
public class CsvKpisCorrida {

    private static final String ARCHIVO = "kpis_corridas.csv";

    private static final String HEADER = String.join(",",
            "timestamp", "escenario", "incertidumbre",
            // num_camiones = camiones que participaron (con >=1 slot en el plan).
            // flota_total = flota nominal del OWL (constante por escenario). Ojo:
            // varian entre metodos porque no todos los camiones llegan a negociar;
            // usar flota_total como tamano de flota comparable, num_camiones como
            // participacion.
            "num_camiones", "flota_total",
            "total_slots", "total_cargas", "total_descargas",
            "material_total",
            "makespan_ms", "makespan_hhmmss",
            "uso_max_palas", "cola_max_palas",
            "uso_max_destino", "cola_max_destino",
            "espera_max_ms", "espera_max_min",
            // slots_retrasados = conteo ENTERO de slots dentro del turno que
            // terminaron tarde; _pct = ese conteo / total_slots (siempre <=100%).
            "slots_retrasados_pct", "slots_retrasados",
            // ── KPIs "dentro del turno" ─────────────────────────────────────
            // El simulador extiende el turno hasta completar todo el plan, pero
            // en una mina real el turno corta a las 6h: el trabajo posterior no
            // existe. Estas columnas cuentan SOLO lo que termina antes de
            // SEGUNDO_FINAL_HORARIO — la produccion REAL comparable entre
            // metodos de rescheduling. El material cuenta al DESCARGAR (una
            // carga que a las 6h sigue arriba del camion no se entrego).
            "cargas_en_turno", "material_en_turno", "material_en_turno_pct",
            // ── Tiempo de viaje acumulado (suma sobre TODOS los camiones) ────
            // Soporta la eficiencia operacional = toneladas / tiempo de viaje.
            // Se acumula dentro del MISMO recorte a 6h que material_en_turno,
            // usando los tiempos reales cuando la incertidumbre esta activa, de
            // modo que numerador y denominador de la eficiencia son coherentes.
            "tiempo_vacio_ms", "tiempo_cargado_ms", "tiempo_viaje_total_ms",
            "eficiencia_ton_por_h_viaje",
            // ── Makespan REAL (sin recorte a 6h) ────────────────────────────
            // Duracion total hasta completar TODA la actividad, incluso la que
            // pasa del turno nominal. Es la UNICA metrica que mira mas alla de
            // las 6h: sirve para graficos comparativos de "cuanto tardo en
            // terminar" entre metodos. El resto de KPIs (material, cargas, etc.)
            // sigue acotado al turno de 6h a proposito.
            "makespan_real_ms", "makespan_real_hhmmss",
            // ── Produccion AL FINAL (sin recorte a 6h) ──────────────────────
            // Versiones "hasta el final de la simulacion" de cargas/descargas/
            // material. Comparar contra las _en_turno (dentro de 6h): la
            // diferencia es lo que se entrego en el "overtime" (6h → makespan_real).
            "cargas_final", "descargas_final", "material_final",
            // ── Trazabilidad del reloj (reproducibilidad) ───────────────────
            // velocidad = multiplicador FIJO de la corrida. Con el
            // congelamiento por episodio no deberia alterar los resultados;
            // queda registrado para poder demostrarlo comparando corridas a
            // distinta velocidad.
            // episodios / cierres = como termino cada episodio de negociacion.
            // valvula > 0 es la BANDERA ROJA: significa que algun episodio se
            // corto por limite de computo y ese resultado pudo verse afectado
            // por la maquina. En una corrida valida debe ser 0.
            "velocidad", "latencia_decision_s",
            "episodios", "cierres_convergencia", "cierres_estancamiento",
            "cierres_valvula", "congelado_real_s",
            // ── Calidad del PLAN INICIAL ────────────────────────────────────
            // La negociacion inicial es no determinista (hilos JADE) y su
            // resultado mueve la produccion MAS que cualquier metodo de
            // reprogramacion: entre dos corridas de NINGUNO se midio 1360 vs
            // 940 slots (31%). Sin estas columnas es imposible saber si una
            // diferencia entre metodos es real o es el plan de partida.
            // motivo_arranque debe decir "negociacion_completa"; cualquier otro
            // valor significa que la corrida partio con el plan a medias.
            "motivo_arranque", "palas_completas", "palas_totales",
            "slots_plan_inicial", "camiones_plan_inicial");

    public static void exportar(String etiquetaEscenario) {
        Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();

        int numCamiones = agendas.size();
        int totalSlots = 0, totalCargas = 0, totalDescargas = 0;
        double materialTotal = 0;
        long makespan = 0;
        long makespanCompleto = 0; // makespan REAL, sin recorte a 6h
        int slotsRetrasados = 0; // solo con incertidumbre

        // Produccion DENTRO del turno nominal (6h). Ver comentario del HEADER.
        final long finTurno = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO;
        int cargasEnTurno = 0;
        double materialEnTurno = 0;

        // Produccion AL FINAL (sin recorte a 6h): cuenta toda la simulacion hasta
        // el ultimo descargue real. Para comparar contra lo entregado en el turno.
        int cargasFinal = 0, descargasFinal = 0;
        double materialFinal = 0;

        // Tiempo de viaje acumulado (ms) sumando todos los camiones, recortado
        // al turno de 6h igual que materialEnTurno → eficiencia = ton / viaje.
        long tiempoVacio = 0;
        long tiempoCargado = 0;

        // Intervalos de USO (operación en curso) y de ESPERA (cola) por ubicación.
        Map<String, List<long[]>> usoPorCarga = new HashMap<>();
        Map<String, List<long[]>> usoPorDescarga = new HashMap<>();
        Map<String, List<long[]>> esperaPorCarga = new HashMap<>();
        Map<String, List<long[]>> esperaPorDescarga = new HashMap<>();
        long esperaMax = 0;

        boolean conIncert = ParametrosSimulador.INCERTIDUMBRES_ACTIVAS;
        Map<String, List<RegistroSimulacion.SlotReal>> reales =
                RegistroSimulacion.getInstancia().getAgendasReales();

        for (Map.Entry<String, Schedule> entry : agendas.entrySet()) {
            // Construimos una lista de actividades (op, ubic, inicio, fin) usando
            // el real si hay incertidumbre, o el plan si no.
            List<Object[]> acts = new ArrayList<>(); // {op, ubic, inicio, fin, material}
            if (conIncert) {
                List<RegistroSimulacion.SlotReal> lista = reales.get(entry.getKey());
                if (lista != null) {
                    for (RegistroSimulacion.SlotReal sr : lista) {
                        Slot p = sr.slotPlanificado;
                        acts.add(new Object[]{p.getOperacion(), p.getUbicacion(),
                                sr.inicioReal, sr.finReal, p.getCantidadMaterial()});
                        // Retrasado = termino mas tarde de lo planificado. Se cuenta
                        // SOLO si arranca dentro del turno (inicioReal < finTurno),
                        // mismo universo que totalSlots. Antes se contaban tambien los
                        // que arrancan despues de 6h → numerador > denominador →
                        // slots_retrasados_pct daba >100% (imposible).
                        if (sr.inicioReal < finTurno && sr.finReal > p.getHoraFin()) {
                            slotsRetrasados++;
                        }
                    }
                }
            } else {
                synchronized (entry.getValue()) {
                    for (Slot s : entry.getValue().getSlots()) {
                        acts.add(new Object[]{s.getOperacion(), s.getUbicacion(),
                                s.getHoraInicio(), s.getHoraFin(), s.getCantidadMaterial()});
                    }
                }
            }

            long[] finAnterior = {Long.MIN_VALUE};
            for (Object[] a : acts) {
                Slot.enum_operacion op = (Slot.enum_operacion) a[0];
                String ubic = (String) a[1];
                long ini = (long) a[2], fin = (long) a[3];
                double material = (double) a[4];

                // Makespan REAL (sin recorte): fin de la ultima actividad de TODA
                // la simulacion, incluidas las que arrancan/terminan despues de las
                // 6h. Se captura ANTES del filtro/recorte de turno de abajo, asi
                // que es lo unico que contabiliza tiempo mas alla del turno nominal.
                if (fin > makespanCompleto) makespanCompleto = fin;

                // Produccion AL FINAL (sin recorte): cuenta CARGA/DESCARGA de TODA
                // la simulacion, incluso lo que se completa despues de las 6h. Se
                // cuenta aca arriba (antes del filtro de turno) a proposito.
                if (op == Slot.enum_operacion.CARGA) {
                    cargasFinal++;
                } else if (op == Slot.enum_operacion.DESCARGA) {
                    descargasFinal++;
                    materialFinal += material;
                }

                // --- INICIO DE CORRECCIÓN: FILTRO ESTRICTO A LAS 6 HORAS ---
                if (ini >= finTurno) {
                    continue; // Ignora cualquier actividad que empiece después de las 6 horas
                }
                
                // Verificamos si la tarea realmente se completó dentro del turno
                boolean terminoEnTurno = (fin <= finTurno);
                
                // Topamos matemáticamente el fin de la actividad a las 6 horas para los cálculos de colas y makespan
                fin = Math.min(fin, finTurno); 
                // --- FIN DE CORRECCIÓN ---

                totalSlots++;
                if (fin > makespan) makespan = fin;

                // Tiempo de viaje (vacio/cargado) acumulado. 'fin' ya viene
                // recortado a finTurno e 'ini' es < finTurno, asi que la
                // duracion contabilizada nunca excede el turno nominal.
                if (op == Slot.enum_operacion.VIAJE_VACIO) {
                    tiempoVacio += (fin - ini);
                } else if (op == Slot.enum_operacion.VIAJE_CARGADO) {
                    tiempoCargado += (fin - ini);
                }

                if (op == Slot.enum_operacion.CARGA || op == Slot.enum_operacion.DESCARGA) {
                    Map<String, List<long[]>> uso = (op == Slot.enum_operacion.CARGA) ? usoPorCarga : usoPorDescarga;
                    Map<String, List<long[]>> esp = (op == Slot.enum_operacion.CARGA) ? esperaPorCarga : esperaPorDescarga;
                    
                    if (op == Slot.enum_operacion.CARGA) {
                        if (terminoEnTurno) {
                            totalCargas++;
                            cargasEnTurno++;
                        }
                    } else {
                        if (terminoEnTurno) { // Solo cuenta el material si alcanzó a descargarse antes del pitazo final
                            totalDescargas++; 
                            materialTotal += material;
                            materialEnTurno += material;
                        }
                    }
                    
                    // Se registran los tiempos de uso con el tope de 6 horas
                    uso.computeIfAbsent(ubic, k -> new ArrayList<>()).add(new long[]{ini, fin});
                    
                    // Cálculo de esperas y colas
                    if (finAnterior[0] != Long.MIN_VALUE && finAnterior[0] < ini) {
                        esp.computeIfAbsent(ubic, k -> new ArrayList<>()).add(new long[]{finAnterior[0], ini});
                        esperaMax = Math.max(esperaMax, ini - finAnterior[0]);
                    }
                }
                finAnterior[0] = fin;
            }
        }

        int usoMaxPalas    = maxSobreUbicaciones(usoPorCarga);
        int usoMaxDestino  = maxSobreUbicaciones(usoPorDescarga);
        int colaMaxPalas   = maxSobreUbicaciones(esperaPorCarga);
        int colaMaxDestino = maxSobreUbicaciones(esperaPorDescarga);

        double slotsRetrasadosPct = (!conIncert) ? 0.0
                : (totalSlots == 0 ? 0.0 : 100.0 * slotsRetrasados / totalSlots);

        // Eficiencia operacional = toneladas entregadas en turno / tiempo de
        // viaje total (en HORAS). Coherente con la definicion ton ÷ Σ(viaje
        // vacio + cargado). 0 si no hubo viajes registrados (evita /0).
        long tiempoViajeTotal = tiempoVacio + tiempoCargado;
        double eficienciaTonPorHViaje = (tiempoViajeTotal == 0) ? 0.0
                : materialEnTurno / (tiempoViajeTotal / 3_600_000.0);

        boolean existe = new File(ARCHIVO).exists();
        try (PrintWriter writer = new PrintWriter(new FileWriter(ARCHIVO, true))) {
            if (!existe) writer.println(HEADER);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            writer.println(String.join(",",
                    timestamp,
                    etiquetaEscenario,
                    ParametrosSimulador.INCERTIDUMBRES_ACTIVAS ? "activa" : "ninguna",
                    Integer.toString(numCamiones),
                    Integer.toString(ParametrosConfiguracion.NUMERODETRUCKS),
                    Integer.toString(totalSlots),
                    Integer.toString(totalCargas),
                    Integer.toString(totalDescargas),
                    String.format(Locale.US, "%.1f", materialTotal),
                    Long.toString(makespan),
                    Utilidades.longToHHMMSS(makespan),
                    Integer.toString(usoMaxPalas),
                    Integer.toString(colaMaxPalas),
                    Integer.toString(usoMaxDestino),
                    Integer.toString(colaMaxDestino),
                    Long.toString(esperaMax),
                    String.format(Locale.US, "%.1f", esperaMax / 60000.0),
                    String.format(Locale.US, "%.1f", slotsRetrasadosPct),
                    Integer.toString(slotsRetrasados),
                    Integer.toString(cargasEnTurno),
                    String.format(Locale.US, "%.1f", materialEnTurno),
                    String.format(Locale.US, "%.1f",
                            materialTotal == 0 ? 0.0 : 100.0 * materialEnTurno / materialTotal),
                    Long.toString(tiempoVacio),
                    Long.toString(tiempoCargado),
                    Long.toString(tiempoViajeTotal),
                    String.format(Locale.US, "%.2f", eficienciaTonPorHViaje),
                    Long.toString(makespanCompleto),
                    Utilidades.longToHHMMSS(makespanCompleto),
                    Integer.toString(cargasFinal),
                    Integer.toString(descargasFinal),
                    String.format(Locale.US, "%.1f", materialFinal),
                    Integer.toString(ParametrosSimulador.VELOCIDAD_REPRODUCCION),
                    Long.toString(EpisodioRescheduling.LATENCIA_DECISION_MS / 1000L),
                    Integer.toString(EpisodioRescheduling.getEpisodiosTotales()),
                    Integer.toString(EpisodioRescheduling.getCierresPorConvergencia()),
                    Integer.toString(EpisodioRescheduling.getCierresPorEstancamiento()),
                    Integer.toString(EpisodioRescheduling.getCierresPorValvula()),
                    Integer.toString(EpisodioRescheduling.getCongelamientoTotalRealS()),
                    escape(PlanBase.getMotivoArranque()),
                    Integer.toString(PlanBase.getPalasCompletas()),
                    Integer.toString(PlanBase.getPalasTotales()),
                    Integer.toString(PlanBase.totalSlots()),
                    Integer.toString(PlanBase.getPlan().size())));
            System.out.println(">>> KPIs agregados a " + ARCHIVO + " (escenario: " + etiquetaEscenario + ")");
        } catch (IOException e) {
            System.err.println("Error al escribir " + ARCHIVO + ": " + e.getMessage());
        }
    }

    /** Máximo solapamiento entre todas las ubicaciones de un mapa. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.contains(",") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static int maxSobreUbicaciones(Map<String, List<long[]>> porUbicacion) {
        int max = 0;
        for (List<long[]> intervalos : porUbicacion.values()) {
            max = Math.max(max, maxSolapamiento(intervalos));
        }
        return max;
    }

    /**
     * Máximo número de intervalos solapados simultáneamente (sweep line).
     * Usa solapamiento ESTRICTO: [0,10] y [10,20] NO se solapan (un puesto que
     * se libera en t deja entrar al siguiente en t), coherente con el modelo de
     * cola del UnloadingPointAgent.
     */
    private static int maxSolapamiento(List<long[]> intervalos) {
        List<long[]> eventos = new ArrayList<>();
        for (long[] iv : intervalos) {
            if (iv[1] <= iv[0]) continue;
            eventos.add(new long[]{iv[0], +1});
            eventos.add(new long[]{iv[1], -1});
        }
        // Orden: por instante; a igualdad, los cierres (-1) antes que aperturas (+1).
        eventos.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        int cur = 0, max = 0;
        for (long[] ev : eventos) { cur += (int) ev[1]; if (cur > max) max = cur; }
        return max;
    }
}