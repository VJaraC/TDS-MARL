import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Guarda TODAS las agendas de una corrida en su propia carpeta, para poder
 * revisarlas despues maquina por maquina (lo pidio el profesor: ver el plan
 * original y la ejecucion real de cada metodo, y comprobar que ninguna maquina
 * hace dos cosas a la vez).
 *
 * Carpeta: corridas/<ETIQUETA_ESCENARIO>/
 *   plan_original.csv   plan de cada camion al terminar la negociacion inicial
 *   plan_final.csv      plan de cada camion en el simulador al final del turno
 *   agendas_agentes.csv agenda interna de cada TruckAgent, ShovelAgent y
 *                       UnloadingPointAgent al final del turno
 *   ejecucion_real.csv  cada operacion con su hora planificada y su hora real
 *   eventos.csv         perturbaciones (clima y fallas) con su hora
 *   recursos.csv        palas con su ubicacion, destinos con su capacidad
 *
 * A diferencia de las auditorias, no se sobrescribe entre corridas: cada
 * etiqueta tiene su carpeta. Solo lee estado; no modifica nada.
 * La verificacion la hace verificar_schedules.py.
 */
public final class CsvSchedulesPorCorrida {

    private static final String RAIZ = "corridas";

    private CsvSchedulesPorCorrida() {}

    public static void exportar(String etiqueta) throws IOException {
        File dir = new File(RAIZ, etiqueta);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("no se pudo crear " + dir.getAbsolutePath());
        }
        exportarPlanOriginal(new File(dir, "plan_original.csv"));
        exportarPlanFinal(new File(dir, "plan_final.csv"));
        exportarAgendasAgentes(new File(dir, "agendas_agentes.csv"));
        exportarEjecucionReal(new File(dir, "ejecucion_real.csv"));
        exportarEventos(new File(dir, "eventos.csv"));
        exportarRecursos(new File(dir, "recursos.csv"));
        copiarRegistrosNegociacion(new File(dir, "registros_negociacion"));
        System.out.println(">>> Agendas de la corrida guardadas en " + dir.getPath());
    }

    /**
     * Copia los registros de mensajes, negociaciones y schedules de la corrida.
     * Esos archivos se sobrescriben en cada corrida del mismo escenario, y sin
     * copia no se puede revisar despues que respondieron los agentes.
     */
    private static void copiarRegistrosNegociacion(File destino) {
        String prefijo = ParametrosConfiguracion.prefijoArchivos();
        if (!destino.exists() && !destino.mkdirs()) return;
        for (String sufijo : new String[]{"messages.csv", "negotiations.csv", "schedules.csv"}) {
            File origen = new File(prefijo + sufijo);
            if (!origen.exists()) continue;
            try {
                java.nio.file.Files.copy(origen.toPath(), new File(destino, origen.getName()).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("No se pudo copiar " + origen.getName() + ": " + e.getMessage());
            }
        }
    }

    // ── Un schedule por reprogramacion (reunion con el profesor, 2-oct-2026) ──
    // corridas/<etiqueta>/schedules_por_reprogramacion/
    //   schedule_01.csv          plan inicial (al terminar la negociacion inicial)
    //   schedule_02.csv ...      plan despues de cada reprogramacion
    //   agendas_agentes_NN.csv   agendas internas de los agentes en ese momento
    //   indice.csv               numero, motivo, reloj de simulacion, operaciones
    // El ultimo schedule es el plan final (plan_final.csv), que conserva lo ejecutado.
    private static int numeroSchedule = 0;

    public static synchronized void guardarSchedule(String motivo) {
        try {
            File dir = new File(new File(RAIZ, ParametrosSimulador.ETIQUETA_ESCENARIO),
                    "schedules_por_reprogramacion");
            if (numeroSchedule == 0 && dir.exists()) {
                // corrida nueva con la misma etiqueta: no mezclar con la anterior
                File[] viejos = dir.listFiles();
                if (viejos != null) for (File v : viejos) v.delete();
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("no se pudo crear " + dir.getAbsolutePath());
            }
            int n = ++numeroSchedule;
            String sufijo = String.format("%02d", n);
            File plan = new File(dir, "schedule_" + sufijo + ".csv");
            exportarPlanFinal(plan);
            exportarAgendasAgentes(new File(dir, "agendas_agentes_" + sufijo + ".csv"));

            int ops = 0;
            for (Schedule s : RegistroSimulacion.getInstancia().getAgendasCamiones().values()) {
                synchronized (s) { ops += s.getSlots().size(); }
            }
            long reloj = VentanaSimulacion.tiempoSimulacionGlobalMs;
            File indice = new File(dir, "indice.csv");
            boolean nuevo = !indice.exists();
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(indice, true), StandardCharsets.UTF_8))) {
                if (nuevo) w.println("numero,motivo,reloj_ms,reloj,operaciones");
                w.println(String.join(",", String.valueOf(n), txt(motivo),
                        String.valueOf(reloj), hms(reloj), String.valueOf(ops)));
            }
            System.out.printf(">>> Schedule %02d guardado (%s, reloj %s, %d operaciones)%n",
                    n, motivo, hms(reloj), ops);
        } catch (Exception e) {
            System.err.println("No se pudo guardar el schedule de la reprogramacion: " + e.getMessage());
        }
    }

    private static PrintWriter abrir(File f) throws IOException {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
    }

    private static String hms(long ms) {
        return Utilidades.longToHHMMSS(Math.max(0, ms));
    }

    private static String txt(String s) {
        if (s == null) return "";
        return s.replace(",", ";").trim();
    }

    /** Copia de los slots bajo el lock de la agenda (los agentes pueden seguir vivos). */
    private static List<Slot> copiar(Schedule sch) {
        synchronized (sch) {
            return new ArrayList<>(sch.getSlots());
        }
    }

    private static void exportarPlanOriginal(File f) throws IOException {
        try (PrintWriter w = abrir(f)) {
            w.println("camion,idx,operacion,maquina,ubicacion,inicio_ms,fin_ms,inicio,fin,material");
            if (!PlanBase.hayPlan()) return;
            for (Map.Entry<String, List<PlanBase.SlotBase>> e : PlanBase.getPlan().entrySet()) {
                int i = 0;
                for (PlanBase.SlotBase s : e.getValue()) {
                    w.println(String.join(",", e.getKey(), String.valueOf(i++), s.operacion.name(),
                            txt(s.maquina), txt(s.ubicacion),
                            String.valueOf(s.horaInicio), String.valueOf(s.horaFin),
                            hms(s.horaInicio), hms(s.horaFin),
                            String.valueOf(s.cantidadMaterial)));
                }
            }
        }
    }

    private static void exportarPlanFinal(File f) throws IOException {
        try (PrintWriter w = abrir(f)) {
            w.println("camion,idx,operacion,maquina,ubicacion,inicio_ms,fin_ms,inicio,fin,material");
            for (Map.Entry<String, Schedule> e :
                    RegistroSimulacion.getInstancia().getAgendasCamiones().entrySet()) {
                int i = 0;
                for (Slot s : copiar(e.getValue())) {
                    w.println(String.join(",", e.getKey(), String.valueOf(i++), s.getOperacion().name(),
                            txt(s.getMaquina()), txt(s.getUbicacion()),
                            String.valueOf(s.getHoraInicio()), String.valueOf(s.getHoraFin()),
                            hms(s.getHoraInicio()), hms(s.getHoraFin()),
                            String.valueOf(s.getCantidadMaterial())));
                }
            }
        }
    }

    private static void exportarAgendasAgentes(File f) throws IOException {
        try (PrintWriter w = abrir(f)) {
            w.println("agente,tipo,contraparte,operacion,ubicacion,inicio_ms,fin_ms,inicio,fin");
            for (Map.Entry<String, RegistroAgendasAgentes.Entrada> e :
                    RegistroAgendasAgentes.getAgendas().entrySet()) {
                for (Slot s : copiar(e.getValue().schedule)) {
                    w.println(String.join(",", e.getKey(), e.getValue().tipo,
                            txt(s.getMaquina()), s.getOperacion().name(), txt(s.getUbicacion()),
                            String.valueOf(s.getHoraInicio()), String.valueOf(s.getHoraFin()),
                            hms(s.getHoraInicio()), hms(s.getHoraFin())));
                }
            }
        }
    }

    private static void exportarEjecucionReal(File f) throws IOException {
        try (PrintWriter w = abrir(f)) {
            w.println("camion,idx,operacion,maquina,ubicacion,inicio_plan_ms,fin_plan_ms,"
                    + "inicio_real_ms,fin_real_ms,inicio_plan,fin_plan,inicio_real,fin_real,material");
            for (Map.Entry<String, List<RegistroSimulacion.SlotReal>> e :
                    RegistroSimulacion.getInstancia().getAgendasReales().entrySet()) {
                List<RegistroSimulacion.SlotReal> lista;
                synchronized (e.getValue()) {
                    lista = new ArrayList<>(e.getValue());
                }
                int i = 0;
                for (RegistroSimulacion.SlotReal sr : lista) {
                    Slot s = sr.slotPlanificado;
                    w.println(String.join(",", e.getKey(), String.valueOf(i++), s.getOperacion().name(),
                            txt(s.getMaquina()), txt(s.getUbicacion()),
                            String.valueOf(s.getHoraInicio()), String.valueOf(s.getHoraFin()),
                            String.valueOf(sr.inicioReal), String.valueOf(sr.finReal),
                            hms(s.getHoraInicio()), hms(s.getHoraFin()),
                            hms(sr.inicioReal), hms(sr.finReal),
                            String.valueOf(s.getCantidadMaterial())));
                }
            }
        }
    }

    private static void exportarEventos(File f) throws IOException {
        try (PrintWriter w = abrir(f)) {
            w.println("tipo,objetivo,inicio_ms,fin_ms,inicio,fin,factor_velocidad");
            for (EventoSimulacion ev : GestorEventos.getInstancia().getEventos()) {
                w.println(String.join(",", ev.getTipo().name(), txt(ev.getTargetId()),
                        String.valueOf(ev.getHoraInicioMs()), String.valueOf(ev.getHoraFinMs()),
                        hms(ev.getHoraInicioMs()), hms(ev.getHoraFinMs()),
                        String.valueOf(ev.getFactorVelocidad())));
            }
        }
    }

    private static void exportarRecursos(File f) throws IOException {
        RegistroSimulacion reg = RegistroSimulacion.getInstancia();
        try (PrintWriter w = abrir(f)) {
            w.println("tipo,nombre,ubicacion,capacidad");
            for (Map.Entry<String, String> e : reg.getUbicacionPalas().entrySet()) {
                w.println(String.join(",", "PALA", e.getKey(), txt(e.getValue()), "1"));
            }
            for (Map.Entry<String, Integer> e : reg.getCapacidadDestinos().entrySet()) {
                w.println(String.join(",", "DESTINO", txt(e.getKey()), txt(e.getKey()),
                        String.valueOf(e.getValue())));
            }
        }
    }
}
