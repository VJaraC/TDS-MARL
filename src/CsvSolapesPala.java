import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Diagnostico de SOBRE-AGENDAMIENTO DE PALAS por corrida ("solapes_pala.csv").
 *
 * ─── Que mide y por que ─────────────────────────────────────────────────────
 * Una pala es un servidor de UN solo puesto: fisicamente carga UN camion a la
 * vez. El plan que produce el CNP (RegistroSimulacion.agendasCamiones) NO impone
 * esa exclusividad: nada en la negociacion ni en ShovelAgent.AgregaGanador
 * chequea que dos CARGA de la misma pala no se pisen en el tiempo. Cuando el
 * plan agenda 2+ camiones a la vez en una pala, en ejecucion la cola FIFO los
 * serializa → esos camiones ESPERAN → se mueve menos material.
 *
 * Este exportador cuenta, por corrida, cuantos solapes de CARGA hay en el plan
 * y cuanto tiempo la pala queda "sobre-agendada" (>=2 camiones simultaneos).
 * Es el numero que necesitamos para responder la pregunta del profesor:
 *   ¿el metodo de rescheduling AGREGA solapes respecto del baseline, o los
 *    solapes son estructurales del CNP (existen en todos los metodos)?
 * Corriendo cada metodo y comparando la fila de este CSV se responde de una.
 *
 * Se mide sobre el PLAN (agendasCamiones, horaInicio/horaFin), que es lo que la
 * negociacion comprometio — el objeto directo de la preocupacion del profesor.
 *
 * Formato acumulativo (una fila por corrida, NO se borra), igual que
 * kpis_corridas.csv:
 *
 *   escenario, palas, cargas_en_turno, pares_solapados, pares_inicio_identico,
 *   tiempo_sobreagendado_s, max_camiones_simultaneos
 *
 *   pares_solapados         → nº de pares (camionA,camionB) de la misma pala con
 *                             ventanas de CARGA que se intersectan (dentro del turno).
 *   pares_inicio_identico   → subconjunto donde |inicioA - inicioB| < 2s: firma del
 *                             clamp "oferta<reloj→reloj" / re-oferta (varias ofertas
 *                             colapsadas sobre el mismo instante).
 *   tiempo_sobreagendado_s  → segundos de UNION en que alguna pala tiene >=2 cargas
 *                             simultaneas (sin doble-conteo, barrido de eventos).
 *   max_camiones_simultaneos→ maximo nº de camiones agendados a la vez en una pala
 *                             (2 = doble-booking puntual; 3-4 = plan muy infactible).
 */
public class CsvSolapesPala {

    private static final String ARCHIVO = "solapes_pala.csv";
    private static final long FIN_TURNO = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO; // 6 h
    private static final long TOL_INICIO_IDENTICO = 2000L; // 2 s

    public static void exportar(String etiquetaEscenario) {
        RegistroSimulacion reg = RegistroSimulacion.getInstancia();

        // 1. Agrupar los slots de CARGA del PLAN por pala (ubicacion), solo los
        //    que arrancan dentro del turno.
        Map<String, List<long[]>> porPala = new java.util.HashMap<>(); // ubic -> [ini,fin]
        for (Map.Entry<String, Schedule> e : reg.getAgendasCamiones().entrySet()) {
            Schedule sch = e.getValue();
            synchronized (sch) {
                for (Slot s : sch.getSlots()) {
                    if (s.getOperacion() != Slot.enum_operacion.CARGA) continue;
                    if (s.getHoraInicio() >= FIN_TURNO) continue;
                    String ub = s.getUbicacion() == null ? "?" : s.getUbicacion();
                    porPala.computeIfAbsent(ub, k -> new ArrayList<>())
                           .add(new long[]{s.getHoraInicio(), s.getHoraFin()});
                }
            }
        }

        int palas = porPala.size();
        int cargasEnTurno = 0;
        int paresSolapados = 0;
        int paresInicioIdentico = 0;
        long tiempoSobreagendadoMs = 0;
        int maxSimultaneos = 0;

        for (List<long[]> lst : porPala.values()) {
            cargasEnTurno += lst.size();

            // 1a. Conteo de PARES solapados (O(n^2) acotado, n~100 por pala).
            lst.sort((a, b) -> Long.compare(a[0], b[0]));
            for (int i = 0; i < lst.size() - 1; i++) {
                long ini1 = lst.get(i)[0], fin1 = lst.get(i)[1];
                for (int j = i + 1; j < lst.size() && lst.get(j)[0] < fin1; j++) {
                    long ini2 = lst.get(j)[0], fin2 = lst.get(j)[1];
                    long solape = Math.min(fin1, fin2) - Math.max(ini1, ini2);
                    if (solape > 0) {
                        paresSolapados++;
                        if (Math.abs(ini1 - ini2) < TOL_INICIO_IDENTICO) paresInicioIdentico++;
                    }
                }
            }

            // 1b. Barrido de eventos: tiempo de UNION con >=2 simultaneos y pico.
            List<long[]> eventos = new ArrayList<>(); // {t, +1/-1}
            for (long[] s : lst) {
                eventos.add(new long[]{s[0], +1});
                eventos.add(new long[]{s[1], -1});
            }
            // orden por tiempo; a igual tiempo, las salidas (-1) antes que las
            // entradas (+1) para no contar contactos borde-a-borde como solape.
            eventos.sort((a, b) -> a[0] != b[0]
                    ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
            int activos = 0;
            long tPrev = 0;
            for (long[] ev : eventos) {
                if (activos >= 2) tiempoSobreagendadoMs += ev[0] - tPrev;
                activos += (int) ev[1];  // ev[1] es +1 o -1
                if (activos > maxSimultaneos) maxSimultaneos = activos;
                tPrev = ev[0];
            }
        }

        // 2. Append de la fila.
        boolean existe = new File(ARCHIVO).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(ARCHIVO, true))) {
            if (!existe) {
                w.println("escenario,palas,cargas_en_turno,pares_solapados,"
                        + "pares_inicio_identico,tiempo_sobreagendado_s,max_camiones_simultaneos");
            }
            w.println(String.join(",",
                    escape(etiquetaEscenario),
                    Integer.toString(palas),
                    Integer.toString(cargasEnTurno),
                    Integer.toString(paresSolapados),
                    Integer.toString(paresInicioIdentico),
                    Long.toString(tiempoSobreagendadoMs / 1000),
                    Integer.toString(maxSimultaneos)));
            System.out.printf(">>> Solapes de pala agregados a %s (%s): "
                    + "pares=%d (inicio_identico=%d) sobreagendado=%ds maxSimult=%d%n",
                    ARCHIVO, etiquetaEscenario, paresSolapados, paresInicioIdentico,
                    tiempoSobreagendadoMs / 1000, maxSimultaneos);
        } catch (IOException ex) {
            System.err.println("[CsvSolapesPala] error escribiendo " + ARCHIVO + ": " + ex.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
