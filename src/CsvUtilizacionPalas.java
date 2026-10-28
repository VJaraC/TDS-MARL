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
 * Utilizacion y carga de trabajo POR PALA INDIVIDUAL ("utilizacion_palas.csv").
 *
 * --- Por que hace falta ----------------------------------------------------
 * El resto de la instrumentacion mide por UBICACION o con maximos globales:
 * kpis_corridas informa uso_max_palas y cola_max_palas como maximos sobre toda
 * la faena, y CsvSolapesPala agrupa por punto de carga. Pero una ubicacion aloja
 * hasta seis palas, y el cuello de botella observado en este trabajo no es la
 * capacidad agregada del punto sino el DESBALANCE entre palas hermanas: camiones
 * haciendo cola frente a una pala mientras otra del mismo punto permanece ociosa.
 *
 * Ese fenomeno es invisible en cualquier metrica agregada por ubicacion, porque
 * el punto en su conjunto puede exhibir una utilizacion razonable mientras la
 * carga se reparte de manera muy desigual entre sus palas.
 *
 * --- Que mide --------------------------------------------------------------
 * Una fila por corrida y por pala fisica, acotada al turno nominal para que sea
 * comparable con material_en_turno:
 *
 *   cargas, toneladas       trabajo efectivamente realizado por esa pala
 *   ocupada_min             tiempo con una carga en curso
 *   utilizacion_pct         ocupada / duracion del turno
 *   pct_cargas_ubicacion    participacion de esta pala en el total de su punto.
 *                           Con N palas en el punto, el reparto perfectamente
 *                           equilibrado seria 100/N. La desviacion respecto de
 *                           ese valor es la medida directa del desbalance.
 *   cola_max                maximo de camiones esperando a la vez por esta pala
 *   espera_total_min        espera acumulada de todos los camiones ante ella
 */
public final class CsvUtilizacionPalas {

    private static final String ARCHIVO = "utilizacion_palas.csv";

    private static final String HEADER = String.join(",",
            "timestamp", "escenario", "metodo", "pala", "ubicacion",
            "palas_en_ubicacion",
            "cargas", "toneladas", "ocupada_min", "utilizacion_pct",
            "pct_cargas_ubicacion", "reparto_equilibrado_pct", "desviacion_pp",
            "cola_max", "espera_total_min", "espera_media_min");

    private CsvUtilizacionPalas() {}

    /** Acumulador por pala. */
    private static final class Pala {
        String ubicacion = "";
        int cargas = 0;
        double toneladas = 0;
        long ocupadaMs = 0;
        long esperaTotalMs = 0;
        int esperas = 0;
        final List<long[]> intervalosEspera = new ArrayList<>();
    }

    public static void exportar(String etiquetaEscenario) {
        final long finTurno = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO;
        Map<String, Pala> porPala = new HashMap<>();

        boolean conIncert = ParametrosSimulador.INCERTIDUMBRES_ACTIVAS;
        Map<String, List<RegistroSimulacion.SlotReal>> reales =
                RegistroSimulacion.getInstancia().getAgendasReales();
        Map<String, Schedule> agendas =
                RegistroSimulacion.getInstancia().getAgendasCamiones();

        for (Map.Entry<String, Schedule> e : agendas.entrySet()) {
            // Lista de (slot, inicio, fin) usando el real si hay incertidumbre.
            List<Object[]> acts = new ArrayList<>();
            if (conIncert) {
                List<RegistroSimulacion.SlotReal> lista = reales.get(e.getKey());
                if (lista != null) {
                    for (RegistroSimulacion.SlotReal sr : lista) {
                        acts.add(new Object[]{sr.slotPlanificado, sr.inicioReal, sr.finReal});
                    }
                }
            } else {
                Schedule sch = e.getValue();
                synchronized (sch) {
                    for (Slot s : sch.getSlots()) {
                        acts.add(new Object[]{s, s.getHoraInicio(), s.getHoraFin()});
                    }
                }
            }

            long finAnterior = -1L;
            for (Object[] a : acts) {
                Slot s      = (Slot) a[0];
                long inicio = (Long) a[1];
                long fin    = (Long) a[2];

                if (s.getOperacion() == Slot.enum_operacion.CARGA) {
                    String nombre = s.getMaquina();
                    if (nombre != null && !nombre.trim().isEmpty() && fin <= finTurno) {
                        Pala p = porPala.get(nombre);
                        if (p == null) { p = new Pala(); porPala.put(nombre, p); }
                        String ubic = s.getUbicacion();
                        if (ubic != null) p.ubicacion = ubic.replace("#", "").trim();
                        p.cargas++;
                        p.toneladas += s.getCantidadMaterial();
                        p.ocupadaMs += Math.max(0, fin - inicio);
                        // Espera: desde que el camion llego (fin del viaje vacio
                        // anterior) hasta que efectivamente empezo a cargar.
                        if (finAnterior >= 0 && inicio > finAnterior) {
                            p.esperaTotalMs += inicio - finAnterior;
                            p.esperas++;
                            p.intervalosEspera.add(new long[]{finAnterior, inicio});
                        }
                    }
                }
                finAnterior = fin;
            }
        }

        if (porPala.isEmpty()) {
            System.out.println("[CsvUtilizacionPalas] sin cargas registradas; no se exporta.");
            return;
        }

        // Cargas totales por ubicacion, para calcular la participacion de cada pala.
        Map<String, Integer> cargasPorUbic  = new HashMap<>();
        Map<String, Integer> palasPorUbic   = new HashMap<>();
        for (Pala p : porPala.values()) {
            Integer c = cargasPorUbic.get(p.ubicacion);
            cargasPorUbic.put(p.ubicacion, (c == null ? 0 : c) + p.cargas);
            Integer n = palasPorUbic.get(p.ubicacion);
            palasPorUbic.put(p.ubicacion, (n == null ? 0 : n) + 1);
        }

        List<String> nombres = new ArrayList<>(porPala.keySet());
        java.util.Collections.sort(nombres);

        File f = new File(ARCHIVO);
        boolean existe = f.exists() && f.length() > 0;
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            if (!existe) w.println(HEADER);
            for (String nombre : nombres) {
                Pala p = porPala.get(nombre);
                int totalUbic = cargasPorUbic.get(p.ubicacion);
                int nPalas    = palasPorUbic.get(p.ubicacion);
                double pct    = totalUbic == 0 ? 0 : 100.0 * p.cargas / totalUbic;
                double ideal  = nPalas == 0 ? 0 : 100.0 / nPalas;
                double util   = finTurno == 0 ? 0 : 100.0 * p.ocupadaMs / finTurno;
                double espMed = p.esperas == 0 ? 0
                        : (p.esperaTotalMs / (double) p.esperas) / 60_000.0;

                w.println(String.join(",",
                        ts,
                        escape(etiquetaEscenario),
                        ParametrosSimulador.METODO_RESCHEDULING.name(),
                        escape(nombre),
                        escape(p.ubicacion),
                        Integer.toString(nPalas),
                        Integer.toString(p.cargas),
                        String.format(Locale.US, "%.1f", p.toneladas),
                        String.format(Locale.US, "%.1f", p.ocupadaMs / 60_000.0),
                        String.format(Locale.US, "%.1f", util),
                        String.format(Locale.US, "%.1f", pct),
                        String.format(Locale.US, "%.1f", ideal),
                        String.format(Locale.US, "%.1f", pct - ideal),
                        Integer.toString(maxSolapamiento(p.intervalosEspera)),
                        String.format(Locale.US, "%.1f", p.esperaTotalMs / 60_000.0),
                        String.format(Locale.US, "%.1f", espMed)));
            }
        } catch (IOException ex) {
            System.err.println("Error al escribir " + ARCHIVO + ": " + ex.getMessage());
            return;
        }

        // Resumen en consola: la pala mas y la menos cargada de cada ubicacion.
        System.out.printf("[CsvUtilizacionPalas] %s: %d palas en %d ubicaciones%n",
                etiquetaEscenario, porPala.size(), cargasPorUbic.size());
        for (String ubic : cargasPorUbic.keySet()) {
            String masCargada = null, menosCargada = null;
            double maxPct = -1, minPct = 1e9;
            int totalUbic = cargasPorUbic.get(ubic);
            for (String nombre : nombres) {
                Pala p = porPala.get(nombre);
                if (!p.ubicacion.equals(ubic)) continue;
                double pct = totalUbic == 0 ? 0 : 100.0 * p.cargas / totalUbic;
                if (pct > maxPct) { maxPct = pct; masCargada = nombre; }
                if (pct < minPct) { minPct = pct; menosCargada = nombre; }
            }
            if (masCargada != null && !masCargada.equals(menosCargada)) {
                System.out.printf("   %-22s %s %.1f%%  vs  %s %.1f%%  (equilibrio %.1f%%)%n",
                        ubic, masCargada, maxPct, menosCargada, minPct,
                        100.0 / palasPorUbic.get(ubic));
            }
        }
    }

    /** Maximo de intervalos solapados (camiones esperando a la vez). */
    private static int maxSolapamiento(List<long[]> intervalos) {
        List<long[]> eventos = new ArrayList<>();
        for (long[] iv : intervalos) {
            if (iv[1] <= iv[0]) continue;
            eventos.add(new long[]{iv[0], +1});
            eventos.add(new long[]{iv[1], -1});
        }
        eventos.sort((a, b) -> a[0] != b[0]
                ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        int cur = 0, max = 0;
        for (long[] ev : eventos) {
            cur += (int) ev[1];
            if (cur > max) max = cur;
        }
        return max;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.contains(",") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
