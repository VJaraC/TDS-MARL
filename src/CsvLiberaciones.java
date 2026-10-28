import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Logger ACUMULATIVO de liberaciones Partial Repair ("liberaciones.csv").
 *
 * Una fila por cada disparo de PartialRepair. NO se borra entre corridas
 * (es el dataset para los graficos de tesis).
 *
 * Columnas:
 *   timestamp           — wall clock del disparo
 *   escenario           — etiqueta de la corrida
 *   umbral_min          — umbral N2 configurado al momento
 *   delta_disparo_min   — DELTA_NUEVO_DISPARO configurado al momento
 *   trigger             — camion que cruzo el umbral
 *   delta_inicial_min   — retraso original del camion (info)
 *   corte_ms / corte    — punto de corte aplicado (respeta tarea en curso)
 *   tarea_en_curso      — true si respetamos slot activo
 *   slots_liberados     — count de slots borrados del plan
 *   palas_afectadas     — count de palas distintas que perdieron carga
 *   destinos_afectados  — count de destinos que perdieron descarga
 *   duracion_ms         — overhead computo de la liberacion
 *
 * Para comparar contra cascadas.csv: misma columna `trigger` y mismo umbral
 * permite armar una tabla "que metodo se aplico en cada evento".
 */
public class CsvLiberaciones {

    private static final String ARCHIVO = "liberaciones.csv";

    private static final String HEADER = String.join(",",
            "timestamp", "escenario",
            "umbral_min", "delta_disparo_min",
            "trigger", "delta_inicial_min",
            "corte_ms", "corte",
            "tarea_en_curso",
            "slots_liberados", "palas_afectadas", "destinos_afectados",
            "duracion_ms");

    /** Llamado desde ReschedulingAgent al finalizar cada liberacion. */
    public static synchronized void log(String trigger,
                                         long deltaInicialMs,
                                         PartialRepair.Resultado res) {
        boolean existe = new File(ARCHIVO).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(ARCHIVO, true))) {
            if (!existe) w.println(HEADER);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
            w.println(String.join(",",
                    timestamp,
                    escape(ParametrosSimulador.ETIQUETA_ESCENARIO),
                    Long.toString(ReschedulingAgent.UMBRAL_NIVEL2 / 60_000),
                    Long.toString(ReschedulingAgent.DELTA_NUEVO_DISPARO / 60_000),
                    escape(trigger),
                    String.format(java.util.Locale.US, "%.1f", deltaInicialMs / 60_000.0),
                    Long.toString(res.corte),
                    Utilidades.longToHHMMSS(res.corte),
                    Boolean.toString(res.tareaEnCurso),
                    Integer.toString(res.slotsLiberados),
                    Integer.toString(res.palasAfectadas.size()),
                    Integer.toString(res.destinosAfectados.size()),
                    Long.toString(res.duracionMs)));
        } catch (IOException e) {
            System.err.println("[CsvLiberaciones] error escribiendo " + ARCHIVO + ": " + e.getMessage());
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
