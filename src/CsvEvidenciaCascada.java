import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evidencia visual de cada cascada right-shift ("evidencia_cascadas.csv").
 *
 * Para cada cascada vuelca DOS fotografias de los recursos afectados (palas
 * y destinos): los slots como estaban ANTES del shift y como quedaron
 * DESPUES. Con este CSV se construye el diagrama de Gantt antes/despues que
 * demuestra en la defensa de tesis que:
 *   (1) el retraso del camion disparador generaba solapes sobre el recurso, y
 *   (2) tras la cascada los slots quedan secuenciales (sin solapamiento).
 *
 * Como graficarlo (Excel):
 *   - filtrar cascada_seq = N y recurso = "PALA:PA01"
 *   - fase "antes":   una barra por camion de hora_inicio a hora_fin
 *   - fase "despues": lo mismo, al lado
 *   El solape se ve a simple vista en "antes" y desaparece en "despues".
 */
public class CsvEvidenciaCascada {

    private static final String ARCHIVO = "evidencia_cascadas.csv";
    private static int secuencia = 0;

    private static final String HEADER = String.join(",",
            "cascada_seq", "timestamp", "trigger", "fase",
            "recurso", "camion",
            "hora_inicio_ms", "hora_fin_ms", "hora_inicio", "hora_fin");

    /**
     * Fotografia del estado actual de todos los recursos compartidos:
     * recurso (PALA:x / DEST:y) → lista de {camion, inicioMs, finMs}.
     * Llamar ANTES de ejecutar la cascada; el "despues" lo captura log().
     */
    public static Map<String, List<String[]>> capturarEstadoRecursos() {
        Map<String, List<String[]>> porRecurso = new HashMap<>();
        for (Map.Entry<String, Schedule> e :
                RegistroSimulacion.getInstancia().getAgendasCamiones().entrySet()) {
            Schedule sch = e.getValue();
            synchronized (sch) {
                for (Slot s : sch.getSlots()) {
                    // Mismo criterio de agrupacion que usa la cascada.
                    String key = CascadingRightShift.keyDeRecurso(s);
                    if (key == null) continue;
                    porRecurso.computeIfAbsent(key, k -> new ArrayList<>())
                              .add(new String[]{
                                      e.getKey(),
                                      Long.toString(s.getHoraInicio()),
                                      Long.toString(s.getHoraFin())});
                }
            }
        }
        return porRecurso;
    }

    /**
     * Vuelca antes/despues de los recursos afectados por la cascada.
     * @param trigger camion que disparo la cascada.
     * @param antes   foto tomada con capturarEstadoRecursos() antes del shift.
     * @param res     resultado de la cascada (define que recursos volcar).
     */
    public static synchronized void log(String trigger,
            Map<String, List<String[]>> antes,
            CascadingRightShift.Resultado res) {

        // Solo los recursos tocados por esta cascada (no toda la mina).
        Set<String> recursos = new HashSet<>();
        for (Set<String> palas : res.palasAfectadasPorCamion.values())
            for (String p : palas) recursos.add("PALA:" + p);
        for (Set<String> dests : res.destinosAfectadosPorCamion.values())
            for (String d : dests) recursos.add("DEST:" + d);
        if (recursos.isEmpty()) return;

        Map<String, List<String[]>> despues = capturarEstadoRecursos();
        int seq = ++secuencia;
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());

        boolean existe = new File(ARCHIVO).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(ARCHIVO, true))) {
            if (!existe) w.println(HEADER);
            volcar(w, seq, ts, trigger, "antes",   antes,   recursos);
            volcar(w, seq, ts, trigger, "despues", despues, recursos);
        } catch (IOException e) {
            System.err.println("[EvidenciaCascada] error escribiendo " + ARCHIVO
                    + ": " + e.getMessage());
        }
    }

    private static void volcar(PrintWriter w, int seq, String ts, String trigger,
            String fase, Map<String, List<String[]>> estado, Set<String> recursos) {
        for (String r : recursos) {
            List<String[]> slots = estado.get(r);
            if (slots == null) continue;
            for (String[] s : slots) {
                long ini = Long.parseLong(s[1]);
                long fin = Long.parseLong(s[2]);
                w.println(String.join(",",
                        Integer.toString(seq), ts, trigger, fase, r, s[0],
                        s[1], s[2],
                        Utilidades.longToHHMMSS(ini), Utilidades.longToHHMMSS(fin)));
            }
        }
    }
}
