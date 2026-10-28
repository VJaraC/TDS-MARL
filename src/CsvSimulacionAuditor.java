import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;

/**
 * Exporta la auditoría de la simulación — CAPA 1.
 *
 *   1) "auditoria_simulacion_planificada.csv" → lo planificado por la negociación.
 *   2) "auditoria_simulacion_comparativa.csv" → plan vs simulado lado a lado.
 *
 * En la Capa 1 (sin incertidumbre) el simulador reproduce el plan al pie de la
 * letra: por eso en la comparativa las columnas "Sim" coinciden exactamente con
 * las "Plan" y el retraso es 0 en todas las filas. Esto NO es redundante: es la
 * validación de que el reloj del simulador sigue fielmente el schedule. Cuando
 * en la Capa 3 se introduzcan incertidumbres, estas dos vistas empezarán a
 * diferir y la columna de retraso dejará de ser 0.
 *
 * Además agrega una fila al archivo acumulativo de KPIs (kpis_corridas.csv).
 */
public class CsvSimulacionAuditor {

    private static final String ARCHIVO_PLAN = "auditoria_simulacion_planificada.csv";
    private static final String ARCHIVO_COMP = "auditoria_simulacion_comparativa.csv";

    public static void exportarDatos() {
        try {
            exportarPlanificado();
            exportarComparativa();

            // Fila acumulativa de KPIs (no se borra entre corridas).
            CsvKpisCorrida.exportar(ParametrosSimulador.ETIQUETA_ESCENARIO);

            // Serie temporal de tonelaje acumulado (0→6h) para los graficos
            // por escenario del profesor. Tambien acumulativo entre corridas.
            CsvTonelajeAcumulado.exportar(ParametrosSimulador.ETIQUETA_ESCENARIO);

            // Diagnostico de sobre-agendamiento de palas (solapes de CARGA en el
            // plan). Acumulativo: una fila por corrida para comparar metodos y
            // decidir si los solapes son estructurales del CNP o los agrega el
            // rescheduling. Ver CsvSolapesPala.
            CsvSolapesPala.exportar(ParametrosSimulador.ETIQUETA_ESCENARIO);

            // ESTABILIDAD del cronograma: desviacion del plan final respecto del
            // plan predictivo (PlanBase). Es la tercera familia de medidas de
            // Vieira et al., junto a eficiencia y costo, y la unica que permite
            // sostener con numeros que la reparacion parcial preserva el plan
            // mientras la regeneracion completa lo destruye.
            CsvEstabilidad.exportar(ParametrosSimulador.ETIQUETA_ESCENARIO);

            // UTILIZACION POR PALA INDIVIDUAL. El resto de la instrumentacion
            // agrega por ubicacion o con maximos globales, y asi el desbalance
            // entre palas hermanas de un mismo punto queda invisible. Es la
            // medicion que sostiene el analisis de contencion del Capitulo VI.
            CsvUtilizacionPalas.exportar(ParametrosSimulador.ETIQUETA_ESCENARIO);

            // AGENDAS COMPLETAS DE LA CORRIDA, en su propia carpeta: plan
            // original, plan final, agendas internas de cada agente, ejecucion
            // real y perturbaciones. Es lo que usa verificar_schedules.py. Va
            // en su propio try para que un fallo aqui no bloquee el aviso final.
            try {
                CsvSchedulesPorCorrida.exportar(ParametrosSimulador.ETIQUETA_ESCENARIO);
            } catch (Exception ex) {
                System.err.println("Error al guardar las agendas de la corrida: " + ex.getMessage());
            }

            String mensaje = "Simulación finalizada. Archivos generados:\n"
                    + " - " + ARCHIVO_PLAN + "\n"
                    + " - " + ARCHIVO_COMP + "  (plan original vs plan revisado vs simulado)\n"
                    + " - kpis_corridas.csv  (acumulativo, escenario: \""
                    + ParametrosSimulador.ETIQUETA_ESCENARIO + "\")";
            System.out.println(">>> " + mensaje.replace("\n", " | "));
            JOptionPane.showMessageDialog(null, mensaje);
        } catch (IOException e) {
            System.err.println("Error al generar CSVs de auditoría: " + e.getMessage());
        }
    }

    private static void exportarPlanificado() throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ARCHIVO_PLAN))) {
            // HoraInicio_MS/HoraFin_MS = plan VIGENTE (revisado por el right-shift si lo hubo).
            // HoraInicioOrig/HoraFinOrig = plan ORIGINAL de la negociación, antes de cualquier shift.
            writer.println("Camion,Operacion,Ubicacion,Reschedulado,"
                    + "HoraInicioOrig_MS,HoraFinOrig_MS,HoraInicio_MS,HoraFin_MS,Duracion_MS,"
                    + "HoraInicioOrig_Reloj,HoraFinOrig_Reloj,HoraInicio_Reloj,HoraFin_Reloj");
            Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
            for (Map.Entry<String, Schedule> entry : agendas.entrySet()) {
                String camion = entry.getKey();
                List<Slot> slots;
                synchronized (entry.getValue()) { slots = new ArrayList<>(entry.getValue().getSlots()); }
                for (Slot slot : slots) {
                    long duracion = slot.getHoraFin() - slot.getHoraInicio();
                    writer.println(String.join(",",
                            camion,
                            slot.getOperacion().toString(),
                            slot.getUbicacion(),
                            slot.fueReschedulado() ? "SI" : "NO",
                            Long.toString(slot.getHoraInicioOriginal()),
                            Long.toString(slot.getHoraFinOriginal()),
                            Long.toString(slot.getHoraInicio()),
                            Long.toString(slot.getHoraFin()),
                            Long.toString(duracion),
                            Utilidades.longToHHMMSS(slot.getHoraInicioOriginal()),
                            Utilidades.longToHHMMSS(slot.getHoraFinOriginal()),
                            Utilidades.longToHHMMSS(slot.getHoraInicio()),
                            Utilidades.longToHHMMSS(slot.getHoraFin())));
                }
            }
        }
    }

    private static void exportarComparativa() throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ARCHIVO_COMP))) {
            // Vista de TRES planos para auditar el right-shift:
            //   PlanOrig  = plan de la negociación (antes de cualquier rescheduling)
            //   PlanRev   = plan vigente tras el right-shift (si el slot fue desplazado)
            //   Sim       = ejecución real simulada (con clima/averías)
            // RetrasoVsOrig = Sim - PlanOrig  -> el retraso que MOTIVÓ el reschedule (>0)
            // RetrasoVsRev  = Sim - PlanRev   -> tras el shift debería tender a 0
            //                 (negativo = el right-shift sobre-corrigió ese slot, normal
            //                  porque se desplaza por el retraso MÁXIMO del camión).
            writer.println("Camion,Operacion,Ubicacion,Reschedulado,"
                    + "InicioPlanOrig_MS,FinPlanOrig_MS,"
                    + "InicioPlanRev_MS,FinPlanRev_MS,"
                    + "InicioSim_MS,FinSim_MS,DuracionSim_MS,"
                    + "RetrasoVsOrig_MS,RetrasoVsOrig_HHMMSS,"
                    + "RetrasoVsRev_MS,RetrasoVsRev_HHMMSS,"
                    + "InicioPlanOrig_Reloj,FinPlanOrig_Reloj,"
                    + "InicioPlanRev_Reloj,FinPlanRev_Reloj,"
                    + "InicioSim_Reloj,FinSim_Reloj");
            boolean conIncert = ParametrosSimulador.INCERTIDUMBRES_ACTIVAS;
            Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
            Map<String, List<RegistroSimulacion.SlotReal>> reales =
                    RegistroSimulacion.getInstancia().getAgendasReales();

            for (Map.Entry<String, Schedule> entry : agendas.entrySet()) {
                String camion = entry.getKey();

                if (conIncert) {
                    // Con incertidumbre: plan original vs plan revisado vs real.
                    List<RegistroSimulacion.SlotReal> lista = reales.get(camion);
                    if (lista == null) continue;
                    for (RegistroSimulacion.SlotReal sr : lista) {
                        Slot p = sr.slotPlanificado;
                        long ioOrig = p.getHoraInicioOriginal();
                        long foOrig = p.getHoraFinOriginal();
                        long ioRev  = p.getHoraInicio();   // post-shift si lo hubo
                        long foRev  = p.getHoraFin();
                        long durSim = sr.finReal - sr.inicioReal;
                        long retrasoVsOrig = sr.finReal - foOrig;  // el que disparó el reschedule
                        long retrasoVsRev  = sr.finReal - foRev;   // tras el shift -> ~0
                        writer.println(String.join(",",
                                camion, p.getOperacion().toString(), p.getUbicacion(),
                                p.fueReschedulado() ? "SI" : "NO",
                                Long.toString(ioOrig), Long.toString(foOrig),
                                Long.toString(ioRev),  Long.toString(foRev),
                                Long.toString(sr.inicioReal), Long.toString(sr.finReal),
                                Long.toString(durSim),
                                Long.toString(retrasoVsOrig), Utilidades.longToHHMMSS(Math.max(0, retrasoVsOrig)),
                                Long.toString(retrasoVsRev),  Utilidades.longToHHMMSS(Math.max(0, retrasoVsRev)),
                                Utilidades.longToHHMMSS(ioOrig), Utilidades.longToHHMMSS(foOrig),
                                Utilidades.longToHHMMSS(ioRev),  Utilidades.longToHHMMSS(foRev),
                                Utilidades.longToHHMMSS(sr.inicioReal), Utilidades.longToHHMMSS(sr.finReal)));
                    }
                } else {
                    // Sin incertidumbre: no hay rescheduling; orig = rev = sim (retraso 0).
                    List<Slot> slots;
                    synchronized (entry.getValue()) { slots = new ArrayList<>(entry.getValue().getSlots()); }
                    for (Slot p : slots) {
                        long ini = p.getHoraInicio();
                        long fin = p.getHoraFin();
                        long dur = fin - ini;
                        writer.println(String.join(",",
                                camion, p.getOperacion().toString(), p.getUbicacion(),
                                "NO",
                                Long.toString(ini), Long.toString(fin),
                                Long.toString(ini), Long.toString(fin),
                                Long.toString(ini), Long.toString(fin),
                                Long.toString(dur),
                                "0", Utilidades.longToHHMMSS(0),
                                "0", Utilidades.longToHHMMSS(0),
                                Utilidades.longToHHMMSS(ini), Utilidades.longToHHMMSS(fin),
                                Utilidades.longToHHMMSS(ini), Utilidades.longToHHMMSS(fin),
                                Utilidades.longToHHMMSS(ini), Utilidades.longToHHMMSS(fin)));
                    }
                }
            }
        }
    }
}