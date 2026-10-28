import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serie temporal de TONELAJE ACUMULADO por corrida ("tonelaje_acumulado.csv").
 *
 * Para el gráfico pedido por el profesor: un panel por tamaño de flota con el
 * eje X = tiempo (0 → 6 h) y el eje Y = toneladas acumuladas, una línea por
 * método. Muestra cómo crece la producción a lo largo del turno, no solo el
 * total final (que ya está en kpis_corridas.csv).
 *
 * Formato LARGO (una fila por punto de muestreo), acumulativo entre corridas
 * (NO se borra): así el archivo va juntando las curvas de los 4 métodos × 3
 * flotas a medida que se corren.
 *
 *   escenario, t_ms, t_hhmmss, toneladas_acumuladas
 *
 * El tonelaje se acredita al COMPLETAR cada DESCARGA (finReal), igual criterio
 * que material_en_turno en CsvKpisCorrida — de hecho el último punto (t=6h)
 * debe coincidir con material_en_turno de esa corrida (chequeo de consistencia).
 * Se usa el schedule REAL (con clima/colas/averías) cuando hay incertidumbre; el
 * plan si no la hay.
 */
public class CsvTonelajeAcumulado {

    private static final String ARCHIVO = "tonelaje_acumulado.csv";

    /** Muestreo cada 5 min desde 0 hasta el fin de turno (6 h) inclusive. */
    private static final long PASO_MS = 5 * 60_000L;               // 5 min
    private static final long FIN_TURNO = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO; // 6 h

    public static void exportar(String etiquetaEscenario) {
        // 1. Reunir los eventos de DESCARGA (instante de fin, toneladas) dentro
        //    del turno de 6 h, usando el real si hay incertidumbre o el plan si no.
        boolean conIncert = ParametrosSimulador.INCERTIDUMBRES_ACTIVAS;
        RegistroSimulacion reg = RegistroSimulacion.getInstancia();
        List<long[]> eventos = new ArrayList<>(); // {finMs, toneladas*1000 para no perder decimales}

        if (conIncert) {
            for (List<RegistroSimulacion.SlotReal> lista : reg.getAgendasReales().values()) {
                if (lista == null) continue;
                for (RegistroSimulacion.SlotReal sr : lista) {
                    Slot p = sr.slotPlanificado;
                    if (p.getOperacion() == Slot.enum_operacion.DESCARGA
                            && sr.finReal <= FIN_TURNO) {
                        eventos.add(new long[]{sr.finReal,
                                Math.round(p.getCantidadMaterial() * 1000)});
                    }
                }
            }
        } else {
            for (Schedule sch : reg.getAgendasCamiones().values()) {
                synchronized (sch) {
                    for (Slot s : sch.getSlots()) {
                        if (s.getOperacion() == Slot.enum_operacion.DESCARGA
                                && s.getHoraFin() <= FIN_TURNO) {
                            eventos.add(new long[]{s.getHoraFin(),
                                    Math.round(s.getCantidadMaterial() * 1000)});
                        }
                    }
                }
            }
        }

        eventos.sort((a, b) -> Long.compare(a[0], b[0]));

        // 2. Recorrer buckets de 5 min acumulando el tonelaje entregado hasta t.
        boolean existe = new File(ARCHIVO).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(ARCHIVO, true))) {
            if (!existe) w.println("escenario,t_ms,t_hhmmss,toneladas_acumuladas");
            int idx = 0;
            double acumMilesTon = 0; // en (ton*1000)
            for (long t = 0; t <= FIN_TURNO; t += PASO_MS) {
                while (idx < eventos.size() && eventos.get(idx)[0] <= t) {
                    acumMilesTon += eventos.get(idx)[1];
                    idx++;
                }
                w.println(String.join(",",
                        escape(etiquetaEscenario),
                        Long.toString(t),
                        Utilidades.longToHHMMSS(t),
                        String.format(Locale.US, "%.1f", acumMilesTon / 1000.0)));
            }
            System.out.println(">>> Serie de tonelaje acumulado agregada a " + ARCHIVO
                    + " (" + etiquetaEscenario + ")");
        } catch (IOException e) {
            System.err.println("[CsvTonelajeAcumulado] error escribiendo " + ARCHIVO + ": " + e.getMessage());
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
