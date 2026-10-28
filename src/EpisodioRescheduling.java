import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinador del EPISODIO DE RESCHEDULING: une el reloj de la mina con el
 * reloj de la negociacion.
 *
 * --- El problema que resuelve ----------------------------------------------
 * La realidad de la mina esta PRECALCULADA (CalculadorScheduleReal) y el reloj
 * de simulacion es solo un cursor que la recorre a multiplicadorVelocidad.
 * Los agentes JADE, en cambio, negocian en tiempo REAL. Mientras negocian, el
 * cursor seguia avanzando: a 100x, los 5 s reales de una ronda CNP costaban
 * 8,3 MINUTOS de tiempo de mina. Consecuencias:
 *
 *   1. La reparacion se aplicaba mucho despues del instante en que se decidio;
 *      la mina seguia corriendo con el plan roto mientras se deliberaba.
 *   2. Los metodos que renegocian MAS quedaban penalizados: Partial Repair
 *      mueve 1 camion, el AOR canonico ~47, Complete Regeneration la flota
 *      entera. La comparacion entre metodos quedaba inclinada a favor del que
 *      menos negocia, por un artefacto del reloj y no por meritos del metodo.
 *   3. Los resultados dependian de la velocidad de reproduccion elegida.
 *
 * --- La solucion -----------------------------------------------------------
 * Semantica clasica de simulacion de eventos discretos: las decisiones se
 * toman en un INSTANTE, no mientras el mundo se mueve. Cuando se dispara un
 * metodo que renegocia via CNP, el cursor se CONGELA. Los agentes negocian con
 * todo el tiempo real que necesiten. Al cerrarse el episodio, el cursor avanza
 * una LATENCIA DE DECISION declarada (parametro del modelo, no de la maquina),
 * que representa lo que tarda en la faena real decidir y comunicar un
 * redespacho.
 *
 * Congelar es seguro porque la realidad esta precalculada: el cursor es un
 * puntero de lectura, no hay fisica corriendo en vivo que se desincronice.
 * Y no llega informacion nueva durante la deliberacion, que es justo lo
 * correcto.
 *
 * --- Cierre del episodio ---------------------------------------------------
 * El episodio termina cuando la negociacion CONVERGE, no por un timeout fijo:
 *
 *   a) No quedan renegociaciones pendientes: exito.
 *   b) La cantidad de pendientes no baja en ESTANCAMIENTO_REAL_MS: el CNP se
 *      quedo sin capacidad de colocar a los que faltan; esos son HUERFANOS.
 *      Es un criterio de convergencia adaptativo: le da al CNP todo el tiempo
 *      que este usando productivamente y corta cuando deja de progresar.
 *   c) Valvula de seguridad dura a MAX_EPISODIO_REAL_MS, solo anti-cuelgue.
 *
 * El contador de cierres por valvula (c) es la EVIDENCIA EXPERIMENTAL de que
 * la velocidad de la maquina no influyo en ningun resultado: si vale 0 en
 * todas las corridas, ninguna quedo cortada por limite de computo.
 *
 * --- Metodos que NO congelan -----------------------------------------------
 * RIGHT_SHIFT y RIGHT_SHIFT_CASCADA no renegocian: modifican el plan y avisan
 * por INFORM. No necesitan tiempo real de negociacion, asi que no congelan.
 * Pagan igual la misma LATENCIA_DECISION_MS, de modo que la comparacion entre
 * metodos sigue siendo justa: todos pagan el mismo costo de decidir.
 */
public final class EpisodioRescheduling {

    // ── Fin de la renegociacion = todas las palas terminaron su barrido ──
    // Igual que la negociacion inicial (que espera a que todas las palas
    // recorran su turno), la renegociacion tras una reparacion no termina
    // hasta que cada pala avisada recorrio su tiempo libre hasta el fin del
    // turno. Antes terminaba cuando cada camion liberado tenia UNA tarea nueva
    // (o tras 60 s sin avances), con lo que solo se replanificaba el proximo
    // ciclo de cada camion y el resto del turno quedaba sin ofertas.
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> BARRIDOS_PENDIENTES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger GENERACION_BARRIDO = new AtomicInteger();

    public static int nuevaGeneracionBarrido() { return GENERACION_BARRIDO.incrementAndGet(); }

    /** La pala recibira un aviso de re-oferta de esta generacion. */
    /**
     * Espera real tras el fin del ultimo barrido antes de cerrar: la ultima
     * asignacion todavia tiene que confirmarse, escribirse en el CSV y ser
     * leida por el simulador (POLLING_CSV_MS = 500 ms). Sin esta espera, el
     * conteo final de huerfanos podia contar a un camion que estaba cerrando
     * su trato justo en ese momento. El reloj sigue congelado: no cambia nada
     * de la mina.
     */
    public static final long GRACIA_CIERRE_REAL_MS = 3_000L;
    private static volatile long tRealBarridosVacios = -1L;

    public static void esperarBarrido(String pala, int generacion) {
        tRealBarridosVacios = -1L;
        BARRIDOS_PENDIENTES.put(pala, generacion);
    }

    /** La pala termino el barrido de esa generacion (si es la vigente, deja de esperarse). */
    public static void barridoTerminado(String pala, int generacion) {
        BARRIDOS_PENDIENTES.remove(pala, generacion);
    }

    public static int barridosPendientes() { return BARRIDOS_PENDIENTES.size(); }


    /** Costo en tiempo de MINA de tomar y comunicar una decision de
     *  redespacho. Parametro declarado del modelo. Sustituye al costo
     *  accidental que antes dependia de la CPU y del multiplicador. */
    public static final long LATENCIA_DECISION_MS = 30_000L;   // 30 s simulados

    /** Piso real del episodio: evita que se cierre antes de que el
     *  verificador alcance a registrar las renegociaciones pendientes. */
    public static final long MIN_EPISODIO_REAL_MS = 1_000L;

    /** Sin progreso durante este lapso real, el CNP se considera estancado.
     *
     *  Subido de 15 s a 60 s (12 ciclos de DEADLINE_MS). Con 15 s el criterio
     *  cortaba episodios que todavia podian avanzar: cuando se liberan muchos
     *  camiones a la vez, la ventana de re-oferta de cada pala abarca casi todo
     *  el turno y la pala la recorre en saltos de 60 s, una negociacion por vez.
     *  Muchos de esos saltos caen en horas sin hueco real (la ventana es el
     *  RANGO de los huecos, no su union), asi que hay rachas largas sin colocar
     *  a nadie. Esas rachas disparaban el estancamiento y se declaraban
     *  huerfanos camiones que aun podian colocarse: la metrica quedaba
     *  confundida entre "el CNP no puede" y "no le di tiempo". */
    public static final long ESTANCAMIENTO_REAL_MS = 60_000L;

    /** Valvula de seguridad anti-cuelgue. No deberia activarse nunca.
     *
     *  Subida de 120 s a 600 s. Con 120 s se activo en 10 de 28 corridas del
     *  lote, todas de PARTIAL_REPAIR o COMPLETE_REGENERATION. La valvula corta
     *  episodios que TODAVIA estaban progresando (los estancados los cierra
     *  antes el criterio de ESTANCAMIENTO_REAL_MS), asi que en esos casos los
     *  camiones pendientes se declararon huerfanos por limite de computo y no
     *  porque el CNP hubiera agotado sus posibilidades.
     *
     *  El sesgo va EN CONTRA de los metodos que renegocian, de modo que la
     *  direccion del resultado era conservadora, pero la magnitud quedaba
     *  sobreestimada. Con 600 s la valvula deja de ser el limite operativo y
     *  manda el criterio de convergencia, que es lo correcto: se le da al CNP
     *  todo el tiempo que este usando productivamente.
     *
     *  Si con 600 s la columna cierres_valvula sigue dando > 0, el episodio
     *  progresa tan lento que hay que revisar el mecanismo de re-oferta de las
     *  palas antes de sacar conclusiones sobre el metodo. */
    // Valvula de seguridad. Con la re-oferta total cada pala recorre de a 60 s
    // el resto del turno (~5 s reales por oferta): un barrido de 5 h toma
    // ~25 min reales. 60 min deja margen y solo actua si algo quedo colgado.
    public static final long MAX_EPISODIO_REAL_MS = 3_600_000L;

    private static boolean abierto             = false;
    private static long    tRealApertura       = 0L;
    private static long    tRealUltimoProgreso = 0L;
    private static int     pendientesUltimo    = -1;
    private static long    latenciaPendienteMs = 0L;
    /** Mayor cantidad de pendientes vista en el episodio en curso. Sirve para
     *  saber CUANTOS alcanzo a colocar el CNP antes de frenarse, que es lo que
     *  distingue "no puede colocarlos" de "no le di tiempo". */
    private static int     pendientesMax       = 0;

    private static final AtomicInteger episodiosTotales        = new AtomicInteger();
    private static final AtomicInteger cierresPorConvergencia  = new AtomicInteger();
    private static final AtomicInteger cierresPorEstancamiento = new AtomicInteger();
    private static final AtomicInteger cierresPorValvula       = new AtomicInteger();
    private static final AtomicInteger congelamientoTotalRealS = new AtomicInteger();

    private EpisodioRescheduling() {}

    /**
     * Abre un episodio. Lo llama ReschedulingAgent al disparar un metodo.
     *
     * @param requiereNegociacion true para los metodos que renegocian via CNP
     *        (PARTIAL_REPAIR, COMPLETE_REGENERATION). Con false solo se cobra
     *        la latencia de decision, sin congelar.
     */
    public static synchronized void abrir(boolean requiereNegociacion) {
        episodiosTotales.incrementAndGet();
        if (!requiereNegociacion) {
            latenciaPendienteMs += LATENCIA_DECISION_MS;
            return;
        }
        if (abierto) return;   // ya hay uno en curso: los disparos se funden
        abierto = true;
        long ahora = System.currentTimeMillis();
        tRealApertura       = ahora;
        tRealUltimoProgreso = ahora;
        pendientesUltimo    = -1;
        tRealBarridosVacios = -1L;
    }

    /**
     * true si el cursor de simulacion NO debe avanzar. Evalua el cierre en la
     * misma llamada, asi que la granularidad es la del timer que la invoca
     * (50 ms reales desde VentanaSimulacion).
     */
    public static synchronized boolean congelado() {
        if (!abierto) return false;

        long ahora = System.currentTimeMillis();
        int  pend  = VerificadorRescheduling.getInstancia().cantidadPendientes();
        if (pend > pendientesMax) pendientesMax = pend;
        if (pend != pendientesUltimo) {          // hubo progreso
            pendientesUltimo    = pend;
            tRealUltimoProgreso = ahora;
        }

        // CRITERIO DE CIERRE: todas las palas avisadas terminaron de ofrecer su
        // tiempo libre hasta el fin del turno, como en la negociacion inicial.
        // Los camiones liberados que siguen sin tarea despues de eso no
        // consiguieron nada en todo el turno: son huerfanos.
        if (!BARRIDOS_PENDIENTES.isEmpty())  tRealBarridosVacios = -1L;
        else if (tRealBarridosVacios < 0)    tRealBarridosVacios = ahora;
        if (BARRIDOS_PENDIENTES.isEmpty() && (ahora - tRealApertura) >= MIN_EPISODIO_REAL_MS
                && (ahora - tRealBarridosVacios) >= GRACIA_CIERRE_REAL_MS) {
            cierresPorConvergencia.incrementAndGet();
            int huerfanos = (pend > 0)
                    ? VerificadorRescheduling.getInstancia().resolverPendientesComoHuerfanos() : 0;
            System.out.printf("[Episodio] RENEGOCIACION COMPLETA en %.1fs reales: las palas "
                    + "recorrieron su tiempo libre hasta el fin del turno; de %d camiones "
                    + "liberados, %d sin ninguna tarea nueva (huerfanos)%n",
                    (ahora - tRealApertura) / 1000.0, pendientesMax, huerfanos);
            cerrar(ahora);
            return false;
        }
        if ((ahora - tRealApertura) >= MAX_EPISODIO_REAL_MS) {
            cierresPorValvula.incrementAndGet();
            System.err.printf("[Episodio] VALVULA DE SEGURIDAD a los %.0fs reales "
                    + "con %d palas sin terminar su barrido y %d camiones pendientes. "
                    + "Resultado posiblemente afectado por limite de computo.%n",
                    (ahora - tRealApertura) / 1000.0, BARRIDOS_PENDIENTES.size(), pend);
            VerificadorRescheduling.getInstancia().resolverPendientesComoHuerfanos();
            BARRIDOS_PENDIENTES.clear();
            cerrar(ahora);
            return false;
        }
        return true;
    }

    private static void cerrar(long ahora) {
        abierto = false;
        latenciaPendienteMs += LATENCIA_DECISION_MS;
        congelamientoTotalRealS.addAndGet((int) ((ahora - tRealApertura) / 1000L));
        pendientesMax = 0;
        // schedule despues de esta reprogramacion (lo pidio el profesor)
        CsvSchedulesPorCorrida.guardarSchedule("tras reprogramacion ("
                + ParametrosSimulador.METODO_RESCHEDULING + ")");
    }

    /**
     * Devuelve y CONSUME la latencia de decision acumulada, para que el cursor
     * la sume una sola vez. Se invoca solo cuando el cursor va a avanzar.
     */
    public static synchronized long consumirLatencia() {
        long l = latenciaPendienteMs;
        latenciaPendienteMs = 0L;
        return l;
    }

    public static boolean estaAbierto()              { return abierto; }
    public static int getEpisodiosTotales()          { return episodiosTotales.get(); }
    public static int getCierresPorConvergencia()    { return cierresPorConvergencia.get(); }
    public static int getCierresPorEstancamiento()   { return cierresPorEstancamiento.get(); }
    public static int getCierresPorValvula()         { return cierresPorValvula.get(); }
    public static int getCongelamientoTotalRealS()   { return congelamientoTotalRealS.get(); }

    /** Linea de resumen para el log y la auditoria de la corrida. */
    public static String resumen() {
        return String.format("episodios=%d convergencia=%d estancamiento=%d "
                + "valvula=%d congelado_real=%ds latencia=%ds",
                getEpisodiosTotales(), getCierresPorConvergencia(),
                getCierresPorEstancamiento(), getCierresPorValvula(),
                getCongelamientoTotalRealS(), LATENCIA_DECISION_MS / 1000L);
    }
}
