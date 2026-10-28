import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logger ACUMULATIVO de regeneraciones completas ("regeneraciones.csv").
 *
 * Una fila por cada disparo de COMPLETE_REGENERATION. NO se borra entre
 * corridas (es el dataset para los graficos comparativos de la tesis).
 *
 * Espejo de CsvLiberaciones / CsvCascadas para que las tres estrategias se
 * comparen con datasets de la misma forma (misma columna `escenario`, mismos
 * umbrales registrados al momento del disparo).
 *
 * Columnas:
 *   timestamp              — wall clock del disparo
 *   escenario              — etiqueta de la corrida
 *   umbral_n2_min          — UMBRAL_NIVEL2 configurado (gatillo sistemico)
 *   umbral_regen_indiv_min — UMBRAL_REGEN_INDIVIDUAL configurado (gatillo catastrofico)
 *   fraccion_flota         — FRACCION_FLOTA_REGEN configurada
 *   cooldown_sim_min       — COOLDOWN_REGEN_SIM_MS configurado (min simulados)
 *   motivo                 — SISTEMICO(...) o CATASTROFICO(...)
 *   t0_ms / t0             — punto de partida del replan (reloj de simulacion)
 *   flota_total            — camiones en la flota
 *   camiones_en_umbral     — cuantos superaban UMBRAL_NIVEL2 al disparar
 *   retraso_max_min        — mayor retraso individual observado
 *   camiones_liberados     — cuantos camiones tenian plan futuro que se descarto
 *   slots_liberados        — total de slots descartados de toda la flota
 *   palas_afectadas        — palas distintas que perdieron cargas asignadas
 *   destinos_afectados     — destinos distintos que perdieron descargas
 *   regen_num              — indice de esta regeneracion dentro del turno
 *   duracion_ms            — overhead de computo del descarte + envio de mensajes
 */
public class CsvRegeneraciones {

    private static final String ARCHIVO = "regeneraciones.csv";

    private static final String HEADER = String.join(",",
            "timestamp", "escenario",
            "umbral_n2_min", "umbral_regen_indiv_min", "fraccion_flota", "cooldown_sim_min",
            "motivo",
            "t0_ms", "t0",
            "flota_total", "camiones_en_umbral", "retraso_max_min",
            "camiones_liberados", "slots_liberados",
            "palas_afectadas", "destinos_afectados",
            "regen_num", "duracion_ms");

    /** Llamado desde ReschedulingAgent al finalizar cada regeneracion completa. */
    public static synchronized void log(String motivo, long t0,
                                        int flotaTotal, int camionesEnUmbral,
                                        long retrasoMaxMs,
                                        int camionesLiberados, int slotsLiberados,
                                        int palasAfectadas, int destinosAfectados,
                                        int regenNum, long duracionMs) {
        boolean existe = new File(ARCHIVO).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(ARCHIVO, true))) {
            if (!existe) w.println(HEADER);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
            w.println(String.join(",",
                    timestamp,
                    escape(ParametrosSimulador.ETIQUETA_ESCENARIO),
                    Long.toString(ReschedulingAgent.UMBRAL_NIVEL2 / 60_000),
                    Long.toString(ReschedulingAgent.UMBRAL_REGEN_INDIVIDUAL / 60_000),
                    String.format(Locale.US, "%.2f", ReschedulingAgent.FRACCION_FLOTA_REGEN),
                    Long.toString(ReschedulingAgent.COOLDOWN_REGEN_SIM_MS / 60_000),
                    escape(motivo),
                    Long.toString(t0),
                    Utilidades.longToHHMMSS(Math.max(0, t0)),
                    Integer.toString(flotaTotal),
                    Integer.toString(camionesEnUmbral),
                    String.format(Locale.US, "%.1f", retrasoMaxMs / 60_000.0),
                    Integer.toString(camionesLiberados),
                    Integer.toString(slotsLiberados),
                    Integer.toString(palasAfectadas),
                    Integer.toString(destinosAfectados),
                    Integer.toString(regenNum),
                    Long.toString(duracionMs)));
        } catch (IOException e) {
            System.err.println("[CsvRegeneraciones] error escribiendo " + ARCHIVO + ": " + e.getMessage());
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
