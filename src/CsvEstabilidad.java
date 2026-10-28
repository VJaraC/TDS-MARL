import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metricas de ESTABILIDAD del cronograma: cuanto se desvio el plan final
 * respecto del plan predictivo capturado por PlanBase.
 *
 * --- Por que importa ------------------------------------------------------
 * Vieira, Herrmann y Lin (2003) agrupan las medidas de desempeno de la
 * reprogramacion en tres familias: eficiencia, ESTABILIDAD y costo. La
 * estabilidad (o su inversa, la "nerviosidad") mide cuanto se altera el plan
 * al repararlo. Es el eje donde se juega la comparacion entre los tres
 * metodos: la reparacion parcial deberia preservar mas plan que la
 * regeneracion completa, aunque pague algo de eficiencia.
 *
 * --- Como se comparan los slots ------------------------------------------
 * Camion por camion, por POSICION en su propia secuencia de operaciones: el
 * i-esimo slot del plan base contra el i-esimo del plan final. Dentro de un
 * camion los slots estan en orden de ciclo, asi que la posicion i identifica
 * "la i-esima operacion de ese camion", que es la unidad natural de
 * comparacion. Cada par cae en una de tres categorias:
 *
 *   PRESERVADO  misma operacion, mismo recurso y mismo instante de inicio.
 *   DESPLAZADO  misma operacion y mismo recurso, distinto instante. Se acumula
 *               el desplazamiento absoluto, que es la medida clasica de
 *               desviacion de tiempos de inicio.
 *   REASIGNADO  cambio de operacion, de pala o de destino. Es el cambio mas
 *               disruptivo para la faena: el camion va a otro lado.
 *
 * Y las diferencias de largo:
 *   ELIMINADOS  operaciones del plan base que ya no existen (trabajo perdido).
 *   AGREGADOS   operaciones nuevas que el plan base no tenia.
 *
 * --- Salida ---------------------------------------------------------------
 * estabilidad.csv, una fila acumulativa por corrida.
 */
public final class CsvEstabilidad {

    private static final String ARCHIVO = "estabilidad.csv";

    private static final String HEADER = String.join(",",
            "timestamp", "escenario", "metodo", "velocidad",
            "camiones_base", "slots_base",
            "preservados", "desplazados", "reasignados",
            "eliminados", "agregados",
            // estabilidad_pct = preservados / slots_base. 100% = el plan
            // predictivo se ejecuto tal cual. Es el indicador principal.
            "estabilidad_pct",
            "camiones_tocados", "camiones_tocados_pct",
            "desplazamiento_total_min", "desplazamiento_medio_min",
            "desplazamiento_max_min");

    private CsvEstabilidad() {}

    public static void exportar(String etiquetaEscenario) {
        if (!PlanBase.hayPlan()) {
            System.out.println("[CsvEstabilidad] sin plan predictivo capturado; "
                    + "no se exporta estabilidad.");
            return;
        }

        Map<String, List<PlanBase.SlotBase>> base = PlanBase.getPlan();
        Map<String, Schedule> finales =
                RegistroSimulacion.getInstancia().getAgendasCamiones();

        int preservados = 0, desplazados = 0, reasignados = 0;
        int eliminados = 0, agregados = 0;
        int camionesTocados = 0;
        long desplazamientoTotal = 0, desplazamientoMax = 0;

        for (Map.Entry<String, List<PlanBase.SlotBase>> e : base.entrySet()) {
            List<PlanBase.SlotBase> slotsBase = e.getValue();

            // Copia estable del plan final de este camion.
            List<Slot> slotsFin = new ArrayList<>();
            Schedule sch = finales.get(e.getKey());
            if (sch != null) {
                synchronized (sch) { slotsFin.addAll(sch.getSlots()); }
            }

            boolean tocado = slotsBase.size() != slotsFin.size();
            int comunes = Math.min(slotsBase.size(), slotsFin.size());

            for (int i = 0; i < comunes; i++) {
                PlanBase.SlotBase b = slotsBase.get(i);
                Slot f = slotsFin.get(i);
                if (b.identico(f)) {
                    preservados++;
                } else if (b.mismoDestino(f)) {
                    desplazados++;
                    long d = Math.abs(f.getHoraInicio() - b.horaInicio);
                    desplazamientoTotal += d;
                    if (d > desplazamientoMax) desplazamientoMax = d;
                    tocado = true;
                } else {
                    reasignados++;
                    tocado = true;
                }
            }
            if (slotsBase.size() > comunes) eliminados += slotsBase.size() - comunes;
            if (slotsFin.size()  > comunes) agregados  += slotsFin.size()  - comunes;
            if (tocado) camionesTocados++;
        }

        int slotsBaseTotal = PlanBase.totalSlots();
        int camionesBase   = base.size();
        double estabilidadPct = slotsBaseTotal == 0 ? 0
                : 100.0 * preservados / slotsBaseTotal;
        double tocadosPct = camionesBase == 0 ? 0
                : 100.0 * camionesTocados / camionesBase;
        double desplMedioMin = desplazados == 0 ? 0
                : (desplazamientoTotal / (double) desplazados) / 60_000.0;

        File f = new File(ARCHIVO);
        boolean existe = f.exists() && f.length() > 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            if (!existe) w.println(HEADER);
            w.println(String.join(",",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()),
                    escape(etiquetaEscenario),
                    ParametrosSimulador.METODO_RESCHEDULING.name(),
                    Integer.toString(ParametrosSimulador.VELOCIDAD_REPRODUCCION),
                    Integer.toString(camionesBase),
                    Integer.toString(slotsBaseTotal),
                    Integer.toString(preservados),
                    Integer.toString(desplazados),
                    Integer.toString(reasignados),
                    Integer.toString(eliminados),
                    Integer.toString(agregados),
                    String.format(Locale.US, "%.1f", estabilidadPct),
                    Integer.toString(camionesTocados),
                    String.format(Locale.US, "%.1f", tocadosPct),
                    String.format(Locale.US, "%.1f", desplazamientoTotal / 60_000.0),
                    String.format(Locale.US, "%.1f", desplMedioMin),
                    String.format(Locale.US, "%.1f", desplazamientoMax / 60_000.0)));
        } catch (IOException ex) {
            System.err.println("Error al escribir " + ARCHIVO + ": " + ex.getMessage());
            return;
        }

        System.out.printf("[CsvEstabilidad] %s: estabilidad=%.1f%%  "
                + "preservados=%d desplazados=%d reasignados=%d "
                + "eliminados=%d agregados=%d  camionesTocados=%d/%d%n",
                etiquetaEscenario, estabilidadPct, preservados, desplazados,
                reasignados, eliminados, agregados, camionesTocados, camionesBase);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.contains(",") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
