import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fotografia del PLAN PREDICTIVO: el cronograma tal como quedo al terminar la
 * negociacion inicial, antes de que ocurra ninguna perturbacion.
 *
 * --- Para que sirve -------------------------------------------------------
 * El desempeno en reprogramacion se mide en tres familias (Vieira, Herrmann y
 * Lin, 2003): EFICIENCIA, ESTABILIDAD y COSTO. La estabilidad es, por
 * definicion, cuanto se desvia el cronograma finalmente ejecutado respecto del
 * plan ORIGINAL — y sin una copia de ese plan original no se puede calcular.
 *
 * Hasta ahora solo se median eficiencia (material, makespan) y costo
 * (huerfanos, tiempo de computo). Con esta fotografia se puede medir la
 * tercera, que es justamente donde se juega el argumento central del trabajo:
 * la reparacion parcial sacrifica algo de eficiencia pero PRESERVA el plan,
 * mientras la regeneracion completa lo destruye. Eso pasa de ser una cita a
 * ser un numero.
 *
 * --- Cuando se toma ------------------------------------------------------
 * En el instante del arranque automatico de la simulacion, que es exactamente
 * cuando la negociacion inicial converge (la firma del plan dejo de cambiar) y
 * antes de que el cursor avance un solo milisegundo. Ver
 * VentanaSimulacion.evaluarArranqueAutomatico.
 *
 * --- Alcance -------------------------------------------------------------
 * Cada corrida se compara contra SU PROPIO plan base. Hasta octubre de 2026
 * cada corrida negociaba su plan, asi que los planes de los metodos eran
 * distintos. Desde el 14-oct-2026 (pedido del profesor) se puede cargar un
 * plan inicial unico por escenario (ver PlanInicialFijo): las agendas de los
 * agentes se reconstruyen tal como las dejo la negociacion, y entonces todos
 * los metodos tienen el mismo plan base.
 */
public final class PlanBase {

    /** Copia inmutable de un slot planificado. */
    public static final class SlotBase {
        public final Slot.enum_operacion operacion;
        public final String ubicacion;
        public final String maquina;
        public final long   horaInicio;
        public final long   horaFin;
        public final double cantidadMaterial;

        SlotBase(Slot s) {
            this.operacion  = s.getOperacion();
            this.ubicacion  = s.getUbicacion();
            this.maquina    = s.getMaquina();
            this.horaInicio = s.getHoraInicio();
            this.horaFin    = s.getHoraFin();
            this.cantidadMaterial = s.getCantidadMaterial();
        }

        /** Mismo recurso y misma operacion (puede diferir el instante). */
        public boolean mismoDestino(Slot s) {
            return operacion == s.getOperacion()
                    && iguales(ubicacion, s.getUbicacion())
                    && iguales(maquina, s.getMaquina());
        }

        /** Identico: mismo recurso, misma operacion y mismo instante. */
        public boolean identico(Slot s) {
            return mismoDestino(s) && horaInicio == s.getHoraInicio();
        }

        private static boolean iguales(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    private static Map<String, List<SlotBase>> plan = null;
    private static long tomadoEnSimMs = -1L;
    /** Por que arranco la simulacion: criterio exacto o respaldo. Queda en el
     *  CSV porque distingue una corrida con el plan inicial COMPLETO de una que
     *  arranco con la negociacion a medias, y esa diferencia mueve la
     *  produccion mas que cualquier metodo de reprogramacion. */
    private static String motivoArranque = "desconocido";
    private static int palasCompletas = 0;
    private static int palasTotales    = 0;

    private PlanBase() {}

    /**
     * Toma la fotografia. Idempotente: la primera llamada manda, para que un
     * re-arranque accidental no pise el plan predictivo original.
     */
    public static synchronized void capturar(long relojSimMs) {
        capturar(relojSimMs, "desconocido");
    }

    public static synchronized void capturar(long relojSimMs, String motivo) {
        if (plan != null) return;
        motivoArranque = motivo;
        palasCompletas = ShovelAgent.getPalasConTurnoLleno();
        palasTotales   = ShovelAgent.getPalasRegistradas();

        Map<String, List<SlotBase>> copia = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, Schedule> e :
                RegistroSimulacion.getInstancia().getAgendasCamiones().entrySet()) {
            List<SlotBase> lista = new ArrayList<>();
            Schedule sch = e.getValue();
            synchronized (sch) {
                for (Slot s : sch.getSlots()) lista.add(new SlotBase(s));
            }
            copia.put(e.getKey(), lista);
            total += lista.size();
        }
        plan = copia;
        tomadoEnSimMs = relojSimMs;
        // Fallas de pala y de punto de descarga sobre equipos que este plan usa.
        InicializadorSimulador.cargarAveriasSegunPlan(copia);
        CsvSchedulesPorCorrida.guardarSchedule("plan inicial (" + motivo + ")");
        // Plan inicial unico: guarda y verifica el plan (ver GuardarPlanInicial).
        GuardarPlanInicial.alCapturar(ParametrosSimulador.ETIQUETA_ESCENARIO, motivo);
        System.out.printf("[PlanBase] plan predictivo capturado: %d camiones, "
                + "%d slots, en t=%s%n",
                copia.size(), total, Utilidades.longToHHMMSS(Math.max(0, relojSimMs)));
    }

    public static synchronized boolean hayPlan() { return plan != null; }
    public static synchronized long getTomadoEnSimMs() { return tomadoEnSimMs; }
    public static synchronized String getMotivoArranque() { return motivoArranque; }
    public static synchronized int getPalasCompletas() { return palasCompletas; }
    public static synchronized int getPalasTotales()   { return palasTotales; }

    public static synchronized Map<String, List<SlotBase>> getPlan() {
        if (plan == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(plan);
    }

    /** Slots totales del plan predictivo. */
    public static synchronized int totalSlots() {
        if (plan == null) return 0;
        int n = 0;
        for (List<SlotBase> l : plan.values()) n += l.size();
        return n;
    }
}
