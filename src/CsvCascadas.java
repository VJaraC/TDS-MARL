import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Logger ACUMULATIVO de cascadas right-shift ("cascadas.csv").
 *
 * Una fila por cada disparo de CascadingRightShift. NO se borra entre corridas
 * (es el dataset para los graficos de tesis comparando 3 metodos).
 *
 * Columnas:
 *   timestamp           — wall clock del disparo
 *   escenario           — etiqueta de la corrida (ParametrosSimulador.ETIQUETA_ESCENARIO)
 *   umbral_min          — umbral N2 configurado al momento (info de calibracion)
 *   delta_disparo_min   — DELTA_NUEVO_DISPARO configurado al momento
 *   trigger             — camion que cruzo el umbral y disparo la cascada
 *   delta_inicial_ms    — ms desplazados al camion inicial
 *   delta_inicial_min   — idem en minutos (lectura humana)
 *   camiones_afectados  — total incluyendo el trigger
 *   slots_movidos       — suma de slots desplazados de todos los camiones
 *   iteraciones         — iteraciones de punto fijo hasta convergencia (o tope)
 *   convergio           — true/false: false = hit MAX_ITERACIONES (ciclo o convergencia lenta)
 *   slots_perdidos      — slots cuya nueva horaInicio quedo > 6h (perdidos del turno)
 *   duracion_ms         — tiempo de wall clock que tardo la cascada (overhead computo)
 *
 * Para tesis: con este CSV se grafican distribuciones de tamaño de cascada,
 * tasa de convergencia, slots perdidos por evento, overhead computacional vs
 * tamano de cascada, etc. — todo sin parsear el messages.csv que pesa 174MB.
 */
public class CsvCascadas {

    private static final String ARCHIVO = "cascadas.csv";

    private static final String HEADER = String.join(",",
            "timestamp", "escenario",
            "umbral_min", "delta_disparo_min",
            "trigger",
            "delta_inicial_ms", "delta_inicial_min",
            "camiones_afectados", "slots_movidos",
            "iteraciones", "convergio",
            "slots_perdidos", "duracion_ms");

    /** Llamado desde ReschedulingAgent al finalizar cada cascada. */
    public static synchronized void log(String trigger,
                                         long deltaInicialMs,
                                         CascadingRightShift.Resultado res,
                                         int slotsMovidos) {
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
                    Long.toString(deltaInicialMs),
                    String.format(java.util.Locale.US, "%.1f", deltaInicialMs / 60_000.0),
                    Integer.toString(res.totalCamionesAfectados()),
                    Integer.toString(slotsMovidos),
                    Integer.toString(res.iteraciones),
                    Boolean.toString(res.convergio),
                    Integer.toString(res.slotsPerdidos),
                    Long.toString(res.duracionMs)));
        } catch (IOException e) {
            System.err.println("[CsvCascadas] error escribiendo " + ARCHIVO + ": " + e.getMessage());
        }
    }

    /** Escape minimal: si el campo tiene coma o comilla, lo encerramos en "". */
    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
