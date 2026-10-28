import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agente JADE que monitorea continuamente los retrasos acumulados del
 * simulador y dispara rescheduling RIGHT-SHIFT cuando se supera el umbral
 * de Nivel 2 (retraso > 15 min en cualquier slot).
 *
 * ─── ¿Qué es Right-Shift? ─────────────────────────────────────────────────
 * Se desplazan hacia adelante TODOS los slots futuros del camión afectado
 * por el valor exacto del retraso acumulado (deltaT). No hay reasignación
 * de palas ni destinos: el camión va a los mismos puntos, solo que más tarde.
 *
 * ─── ¿Por qué enviar mensajes a los agentes? ─────────────────────────────
 * Los agentes JADE mantienen sus schedules internos para decidir
 * disponibilidad en futuras negociaciones (CFPs). Sin actualización:
 *   • TruckAgent cree estar libre desde T=100 aunque realmente acaba en T=150
 *     → acepta CFPs que le generan conflicto.
 *   • ShovelAgent sigue mostrando un slot de carga en T=100-120 para el camión
 *     cuando en realidad llegará en T=150 → distorsiona la planificación.
 *   • UnloadingPointAgent tiene el slot de descarga en T=200 cuando llegará
 *     en T=250 → puede haber doble-booking real en T=200-250.
 *
 * ─── Flujo de mensajes ────────────────────────────────────────────────────
 *   ReschedulingAgent → TruckAgent(X)           : INFORM RESCHEDULE-X-ts
 *   ReschedulingAgent → ShovelAgent(pala)        : INFORM RESCHEDULE-X-ts
 *   ReschedulingAgent → UnloadingPointAgent(dest): INFORM RESCHEDULE-X-ts
 *
 * Todos llevan DocumentoRescheduling serializado como contenido.
 *
 * ─── Umbrales ─────────────────────────────────────────────────────────────
 *   UMBRAL_NIVEL2 = 1 500 000 ms (25 min)  → disparo del rescheduling
 *   DELTA_NUEVO_DISPARO = 900 000 ms (15 min) → umbral mínimo de nuevo delta
 *       para evitar re-disparos repetidos sobre el mismo camión.
 */
public class ReschedulingAgent extends Agent {

    // ── Umbrales públicos (visibles desde los tests y el dashboard) ───────────
    /** Nivel 2: retraso mayor a 25 min → right-shift cascada.
     *  Calibrado tras análisis de daño crudo (ver tesis): con 15min la cascada
     *  se disparaba 32 veces en 9min reales y perdía 18% de operaciones. Con
     *  25min cae a un régimen razonable manteniendo la sensibilidad para los
     *  eventos críticos (falla pala/chancador). */
    public static final long UMBRAL_NIVEL2       = 1_500_000L; // 25 min

    /** Solo re-dispara si el nuevo retraso supera en 15 min al último aplicado.
     *  Antes 5min — generaba re-disparos sobre el mismo camión por desfases marginales. */
    public static final long DELTA_NUEVO_DISPARO = 900_000L;   // 15 min

    /** Periodicidad del TickerBehaviour, en tiempo REAL. JADE solo sabe
     *  despertar behaviours en tiempo real, asi que este numero no se puede
     *  llevar a tiempo simulado. Lo que SI se controla es cada cuanto el
     *  detector actua de verdad: ver GRANULARIDAD_DETECCION_SIM_MS. */
    public static final long INTERVALO_CHEQUEO   = 1_000L;     // 1 s real

    /** Granularidad REAL de la deteccion, en tiempo de MINA.
     *
     * El ticker despierta cada INTERVALO_CHEQUEO reales, pero el detector solo
     * actua si el reloj de simulacion avanzo al menos esto desde el ultimo
     * chequeo efectivo. Sin esta compuerta la granularidad era
     * INTERVALO_CHEQUEO x velocidad: a 100x el detector miraba una vez cada
     * 8,3 MINUTOS de mina, y cambiaba con la velocidad elegida — o sea que los
     * resultados dependian del multiplicador.
     *
     * Con la compuerta la granularidad es constante e igual a este valor para
     * cualquier velocidad V que cumpla INTERVALO_CHEQUEO x V <= este valor,
     * es decir V <= 120. Cubre todas las velocidades de trabajo (20x a 100x).
     *
     * 2 min de mina frente a un UMBRAL_NIVEL2 de 25 min = 8% del umbral:
     * fino de sobra para no perder el instante del cruce. */
    public static final long GRANULARIDAD_DETECCION_SIM_MS = 120_000L; // 2 min sim

    /** Reloj de simulacion del ultimo chequeo efectivo (compuerta de arriba).
     *
     *  Se inicializa en -GRANULARIDAD y NO en Long.MIN_VALUE: con MIN_VALUE la
     *  resta (relojSim - ultimoChequeoSimMs) DESBORDA y da negativo, con lo que
     *  la compuerta cortaba siempre y chequearUmbrales() no se ejecutaba nunca.
     *  Con este valor la primera comparacion da relojSim + GRANULARIDAD, que
     *  pasa de inmediato y sin desbordar. */
    private long ultimoChequeoSimMs = -GRANULARIDAD_DETECCION_SIM_MS;

    // ── Umbrales de COMPLETE REGENERATION ───────────────────────────────────────
    // La regeneracion completa borra y renegocia TODA la flota: tiene alto costo
    // computacional, asi que NO se dispara por un solo camion atrasado (para eso
    // esta Partial Repair). Solo se activa ante una perturbacion SISTEMICA y con
    // freno anti-bucle. Dos gatillos (basta uno):
    //
    //   (a) SISTEMICO  : >= FRACCION_FLOTA_REGEN de la flota (con piso
    //                    MIN_CAMIONES_REGEN) supera UMBRAL_NIVEL2 al mismo tiempo.
    //                    Es la firma de una falla que arrastra a muchos camiones
    //                    (pala/chancador caido), donde un replan global paga su costo.
    //   (b) CATASTROFICO: un solo camion con retraso >= UMBRAL_REGEN_INDIVIDUAL.
    //                    Su cadena completa quedo rota y el local-repair no basta.

    /** Retraso individual que por si solo justifica regenerar todo el plan. */
    public static final long   UMBRAL_REGEN_INDIVIDUAL = 2_700_000L; // 45 min

    /** Fraccion de la flota simultaneamente sobre UMBRAL_NIVEL2 que dispara el
     *  gatillo sistemico. */
    public static final double FRACCION_FLOTA_REGEN    = 0.25;        // 25%

    /** Piso absoluto de camiones afectados para el gatillo sistemico (evita que
     *  en flotas chicas el 25% sea 1 camion). */
    public static final int    MIN_CAMIONES_REGEN      = 4;

    /** ANTI-BUCLE (clave): tiempo de MINA minimo entre dos regeneraciones.
     *
     *  Antes eran 120 s de reloj de pared, que a 100x equivalian a 3,3 horas
     *  simuladas: el cooldown terminaba siendo el limite efectivo en vez del
     *  tope por turno, y ese limite se movia con la velocidad de reproduccion.
     *  Expresado en tiempo simulado pasa a ser una decision operacional que se
     *  puede declarar y defender: no se regenera el plan global de toda la
     *  flota mas de una vez cada media hora de turno. Con esto el freno que
     *  manda vuelve a ser MAX_REGEN_POR_TURNO, que es lo que se buscaba. */
    public static final long   COOLDOWN_REGEN_SIM_MS   = 1_800_000L;  // 30 min sim

    /** Tope duro de regeneraciones por turno. Acota el costo computacional total. */
    public static final int    MAX_REGEN_POR_TURNO     = 3;

    // ── Estado interno ─────────────────────────────────────────────────────────
    /**
     * Por camión, el deltaT del último reschedule aplicado.
     * Evita re-disparar continuamente por el mismo retraso ya corregido.
     */
    private final ConcurrentHashMap<String, Long> ultimosDeltaAplicados =
            new ConcurrentHashMap<>();

    private int totalReschedules = 0;

    // ── Estado anti-bucle de COMPLETE REGENERATION ──────────────────────────────
    /** Reloj de SIMULACION de la ultima regeneracion global (cooldown). */
    private long ultimaRegeneracionSimMs = Long.MIN_VALUE;
    /** Cuantas regeneraciones completas se dispararon en este turno. */
    private int  totalRegeneraciones = 0;
    /**
     * Por camion, las tareas cuyo atraso ya se atendio en su ultima
     * reparacion (por identidad de objeto): lo que ya ocurrio y el ciclo que
     * se protegio. Sus atrasos no cuentan para el disparo siguiente.
     *
     * Antes contaban: el atraso maximo de un camion nunca baja, y tras la
     * primera regeneracion el gatillo quedaba cumplido para siempre (en
     * grande_V3_COMPLETE re-disparo justo al vencer cada cooldown: 4:19, 4:50,
     * 5:23). Desde el 2026-10-07 se usa tambien en la cascada y en la parcial
     * (ver chequearUmbrales). Sin entrada = cuenta todo.
     */
    private final ConcurrentHashMap<String, Set<Slot>> slotsAtendidos = new ConcurrentHashMap<>();

    /** Marca como atendidas las tareas del camion que empiezan hasta 'hasta'. */
    private void marcarAtendidos(String camion, long hasta) {
        Set<Slot> s = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Slot, Boolean>());
        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        if (sch != null) {
            synchronized (sch) {
                for (Slot x : sch.getSlots()) if (x.getHoraInicio() <= hasta) s.add(x);
            }
        }
        slotsAtendidos.put(camion, s);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void setup() {
        System.out.println("[ReschedulingAgent] activo."
                + " Umbral Nivel2=" + UMBRAL_NIVEL2 / 60_000 + "min"
                + " | chequeo cada " + INTERVALO_CHEQUEO / 1_000 + "s"
                + " | incertidumbre=" + ParametrosSimulador.INCERTIDUMBRES_ACTIVAS
                + " | metodo=" + ParametrosSimulador.METODO_RESCHEDULING);

        // Solo tiene sentido cuando hay incertidumbres activas.
        // En baseline puro no hay retrasos reales que detectar.
        if (!ParametrosSimulador.INCERTIDUMBRES_ACTIVAS) {
            System.out.println("[ReschedulingAgent] incertidumbre desactivada → agente inactivo.");
            doSuspend();
            return;
        }

        addBehaviour(new TickerBehaviour(this, INTERVALO_CHEQUEO) {
            @Override
            protected void onTick() {
                // COMPUERTA DE GRANULARIDAD: el detector actua en funcion del
                // tiempo de MINA transcurrido, no del tiempo real. Asi la
                // deteccion no depende de la velocidad de reproduccion. De paso,
                // durante un episodio congelado el reloj no avanza y la
                // compuerta suprime los chequeos redundantes.
                long relojSim = VentanaSimulacion.tiempoSimulacionGlobalMs;
                if (relojSim - ultimoChequeoSimMs < GRANULARIDAD_DETECCION_SIM_MS) {
                    // Aun asi hay que resolver renegociaciones pendientes: son
                    // en tiempo real y no dependen del avance del reloj.
                    chequearPendientesSiCorresponde();
                    return;
                }
                if (ultimoChequeoSimMs < 0) {
                    // Diagnostico: confirma que la compuerta deja pasar. En la
                    // primera corrida un desbordamiento la mantenia cerrada y
                    // el detector no se ejecuto nunca, sin ninguna senal visible.
                    System.out.printf("[ReschedulingAgent] deteccion activa desde "
                            + "t=%s (granularidad=%ds sim, metodo=%s)%n",
                            Utilidades.longToHHMMSS(Math.max(0, relojSim)),
                            GRANULARIDAD_DETECCION_SIM_MS / 1000L,
                            ParametrosSimulador.METODO_RESCHEDULING);
                }
                ultimoChequeoSimMs = relojSim;

                chequearUmbrales();
                chequearPendientesSiCorresponde();
            }
        });
    }

    /**
     * Mayor retraso YA MATERIALIZADO de un camion: maximo sobre sus slots con
     * finReal <= relojSim. Mismo criterio causal que usa chequearUmbrales, para
     * que la marca de un afectado indirecto sea comparable con la del
     * disparador y con la de cualquier disparo futuro.
     */
    private long retrasoMaxObservado(String camion, long relojSim) {
        List<RegistroSimulacion.SlotReal> slots =
                RegistroSimulacion.getInstancia().getAgendasReales().get(camion);
        if (slots == null) return 0L;
        long max = 0L;
        for (RegistroSimulacion.SlotReal sr : slots) {
            if (sr.finReal > relojSim) continue;   // aun no ocurrio
            if (sr.retraso > max) max = sr.retraso;
        }
        return max;
    }

    /** Slots que le quedan al camion en el plan (post-liberacion). */
    private int contarSlots(String camion) {
        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        if (sch == null) return 0;
        synchronized (sch) { return sch.getSlots().size(); }
    }

    /**
     * Resuelve las renegociaciones pendientes de los metodos que usan CNP.
     *
     * Se ejecuta en TIEMPO REAL y en cada tick (no pasa por la compuerta de
     * granularidad) porque la negociacion CNP corre en tiempo real: mientras
     * el cursor esta congelado por un episodio, este es el unico progreso que
     * ocurre, y es lo que le permite a EpisodioRescheduling detectar la
     * convergencia y reanudar el reloj.
     */
    private void chequearPendientesSiCorresponde() {
        if (ParametrosSimulador.METODO_RESCHEDULING
                == ParametrosSimulador.MetodoRescheduling.PARTIAL_REPAIR
            || ParametrosSimulador.METODO_RESCHEDULING
                == ParametrosSimulador.MetodoRescheduling.COMPLETE_REGENERATION) {
            VerificadorRescheduling.getInstancia()
                    .chequearPendientes(System.currentTimeMillis());
        }
    }

    // ── Lógica de detección ────────────────────────────────────────────────────

    /**
     * Recorre agendasReales buscando camiones con retraso mayor al umbral.
     * Por cada uno que supere el umbral Y que no haya sido reschedulado
     * recientemente con un delta similar, ejecuta el right-shift.
     */
    private void chequearUmbrales() {
        ConcurrentHashMap<String, List<RegistroSimulacion.SlotReal>> reales =
                RegistroSimulacion.getInstancia().getAgendasReales();

        if (reales.isEmpty()) return;  // la simulación todavía no arrancó

        long tiempoActual = estimarTiempoSimulacion(reales);
        if (tiempoActual == 0) return;

        // COMPLETE REGENERATION es una decision GLOBAL (no por camion): se evalua
        // con una mirada de toda la flota y, si corresponde, dispara UNA sola
        // regeneracion. Cortamos aca para no entrar al dispatch per-camion.
        if (ParametrosSimulador.METODO_RESCHEDULING
                == ParametrosSimulador.MetodoRescheduling.COMPLETE_REGENERATION) {
            evaluarRegeneracionGlobal(reales);
            return;
        }

        // CAUSALIDAD — MISMAS REGLAS PARA TODOS LOS METODOS: agendasReales esta
        // PRECALCULADO para el turno completo (incluye retrasos que CAUSARAN
        // fallas futuras). Sin este filtro, el detector per-camion (NINGUNO/
        // CASCADA/PARTIAL) "veia el futuro" y disparaba sobre retrasos aun no
        // materializados, mientras COMPLETE_REGENERATION ya era causal. Ahora
        // todos miden solo sobre slots YA EJECUTADOS (finReal <= reloj de sim),
        // asi la comparacion entre metodos es justa.
        long relojSim = VentanaSimulacion.tiempoSimulacionGlobalMs;
        if (relojSim <= 0) return;  // sim aun no arranco → nada ocurrido todavia

        for (Map.Entry<String, List<RegistroSimulacion.SlotReal>> entry : reales.entrySet()) {
            String camion = entry.getKey();
            List<RegistroSimulacion.SlotReal> slots = entry.getValue();
            if (slots == null || slots.isEmpty()) continue;

            // deltaT del right-shift = MAYOR retraso POSITIVO observado en los slots
            // del camión. No el último, porque un slot más reciente puede haberse
            // adelantado (retraso < 0) tras recuperarse del clima, pero el daño ya
            // se propagó hacia adelante.
            //
            // ANCLA DEL CORTE = horaInicio planificada del PRIMER slot cuyo retraso
            // ya supera el umbral Nivel 2. Ese es el punto donde la perturbación se
            // vuelve significativa: desde ese slot (inclusive) en adelante se desplaza
            // todo +deltaT. Es el right-shift clásico y garantiza que SIEMPRE haya al
            // menos un slot para mover.
            //
            // IMPORTANTE: antes el corte era estimarTiempoSimulacion() = makespan
            // global (el fin de TODO el turno). Como CalculadorScheduleReal precalcula
            // el turno completo de una vez, ningún slot planificado quedaba por encima
            // de ese corte y el right-shift no encontraba nada para desplazar
            // ("sin slots futuros para shiftear"). Por eso ahora el corte es relativo
            // al inicio de la perturbación del propio camión, no al makespan.
            // ATRASO NUEVO (2026-10-07, CASCADA y PARTIAL). Antes se comparaba
            // el mayor atraso de TODO el turno con el nivel al que el camion se
            // reparo la ultima vez. Ese maximo nunca baja: tras una reparacion,
            // un atraso nuevo respecto del plan nuevo solo se veia si superaba
            // el viejo + 15 min, y el incremento mezclaba atraso viejo y nuevo.
            // Ahora no cuentan las tareas que ya se atendieron en la ultima
            // reparacion del camion (slotsAtendidos): solo el atraso medido
            // contra el plan vigente. Ese atraso es entero nuevo, asi que se
            // repara con el y nada se suma dos veces (no hay bucle de
            // disparos sobre el mismo atraso). NINGUNO conserva la regla
            // anterior: solo observa y sus corridas no se repiten.
            boolean reglaNueva = ParametrosSimulador.RESCHEDULING_ACTIVO
                    && ParametrosSimulador.METODO_RESCHEDULING
                       != ParametrosSimulador.MetodoRescheduling.NINGUNO;
            Set<Slot> atendidos = reglaNueva ? slotsAtendidos.get(camion) : null;

            long retrasoMax = 0;
            long inicioPlanCorte = -1; // horaInicio planificada del slot ancla
            for (RegistroSimulacion.SlotReal sr : slots) {
                if (sr.finReal > relojSim) continue;  // aun no ocurrio (causalidad)
                if (atendidos != null && atendidos.contains(sr.slotPlanificado)) continue;
                if (sr.retraso > retrasoMax) retrasoMax = sr.retraso;
                if (inicioPlanCorte < 0 && sr.retraso >= UMBRAL_NIVEL2) {
                    inicioPlanCorte = sr.slotPlanificado.getHoraInicio();
                }
            }

            if (retrasoMax < UMBRAL_NIVEL2) continue;

            long incremento;
            if (reglaNueva) {
                incremento = retrasoMax;
            } else {
                // Solo actuamos si el retraso es significativamente mayor
                // al último delta ya aplicado (evita micro-disparos repetidos).
                long ultimoDelta = ultimosDeltaAplicados.getOrDefault(camion, 0L);
                incremento  = retrasoMax - ultimoDelta;
                if (incremento < DELTA_NUEVO_DISPARO) continue;
            }

            // Corte = justo ANTES del slot ancla, para que ese slot y todos los
            // posteriores entren en el right-shift (ejecutarRightShift desplaza los
            // slots con horaInicio > tiempoCorte). Fallback defensivo: si no se ubicó
            // ancla, anclamos en el primer slot del camión (desplaza todo el schedule).
            long tiempoCorte = (inicioPlanCorte >= 0)
                    ? inicioPlanCorte - 1
                    : slots.get(0).slotPlanificado.getHoraInicio() - 1;

            // CAUSALIDAD en la ACCION (no solo en la deteccion): el corte nunca
            // puede quedar en el PASADO del reloj de simulacion. Con la deteccion
            // causal, el ancla de la perturbacion (inicioPlanCorte) suele quedar
            // detras del reloj al momento de detectar; sin este clamp, Partial
            // Repair BORRABA slots ya ejecutados (produccion hecha desaparecia
            // del registro) y la cascada shifteaba trabajo ya realizado. El
            // pasado es inmutable: solo se replanifica desde el presente.
            tiempoCorte = Math.max(tiempoCorte, relojSim);

            System.out.printf("[ReschedulingAgent] NIVEL2 → %s  " +
                            "retraso=%.1fmin  incremento=%.1fmin  corte=%s%n",
                    camion, retrasoMax / 60_000.0, incremento / 60_000.0,
                    Utilidades.longToHHMMSS(Math.max(0, tiempoCorte)));

            // Desplazamos solo el INCREMENTO de retraso desde el último shift de este
            // camión (en el 1er disparo incremento == retrasoMax). Registramos retrasoMax
            // como acumulado, para no volver a sumar el retraso completo en re-disparos.
            ejecutarRightShift(camion, incremento, retrasoMax, tiempoCorte);
        }
    }

    /**
     * Tiempo de simulación aproximado = máximo finReal de todos los slots
     * ya completados. Es conservador (no adelanta el reloj más de lo observado).
     */
    private long estimarTiempoSimulacion(
            ConcurrentHashMap<String, List<RegistroSimulacion.SlotReal>> reales) {
        long maxT = 0;
        for (List<RegistroSimulacion.SlotReal> slots : reales.values()) {
            if (slots != null && !slots.isEmpty()) {
                long fin = slots.get(slots.size() - 1).finReal;
                if (fin > maxT) maxT = fin;
            }
        }
        return maxT;
    }

    // ── Right-shift ────────────────────────────────────────────────────────────

    /**
     * Aplica el right-shift sobre el schedule planificado del camión en
     * RegistroSimulacion y envía INFORM a los agentes afectados.
     *
     * Pasos:
     *   1. Shiftear en RegistroSimulacion los slots futuros del camión.
     *   2. Identificar palas (CARGA) y destinos (DESCARGA) afectados.
     *   3. Enviar DocumentoRescheduling a TruckAgent + palas + destinos.
     *   4. Registrar el delta aplicado para evitar re-disparos.
     *
     * @param camion         nombre del TruckAgent (ej: "Truck5")
     * @param deltaT         ms a desplazar AHORA = el INCREMENTO de retraso desde el
     *                       último shift de este camión (en el 1er disparo es el retraso
     *                       completo). Se aplica a RegistroSimulacion y viaja en el
     *                       DocumentoRescheduling, así los agentes suman lo mismo.
     * @param deltaAcumulado retraso máximo TOTAL del camión hasta ahora. Es lo que se
     *                       registra en ultimosDeltaAplicados; gracias a esto el próximo
     *                       incremento = nuevoMáx − acumulado, evitando sumar dos veces
     *                       el retraso (que era la causa del sobre-desplazamiento).
     * @param tiempoCorte    slots con horaInicio ≤ tiempoCorte NO se tocan
     */
    private void ejecutarRightShift(String camion, long deltaT, long deltaAcumulado, long tiempoCorte) {
        // ── MODO OBSERVACIÓN (NINGUNO o flag legacy desactivado) ────────────
        // No tocamos slots ni mandamos mensajes. Solo logueamos el evento.
        if (!ParametrosSimulador.RESCHEDULING_ACTIVO
                || ParametrosSimulador.METODO_RESCHEDULING
                   == ParametrosSimulador.MetodoRescheduling.NINGUNO) {
            System.out.printf("[ReschedulingAgent] OBSERVADO (sin reschedule): %s  " +
                            "deltaT_omitido=%.1fmin  acum=%.1fmin  corte=%s%n",
                    camion, deltaT / 60_000.0, deltaAcumulado / 60_000.0,
                    Utilidades.longToHHMMSS(Math.max(0, tiempoCorte)));
            CsvLogger.logMessage(
                    getLocalName(), "RESCHEDULE_OMITIDO",
                    getLocalName(), camion,
                    "INFORM", "OBSERV-" + camion,
                    "deltaT=" + Utilidades.longToHHMMSS(deltaT)
                            + " acum=" + Utilidades.longToHHMMSS(deltaAcumulado));
            ultimosDeltaAplicados.put(camion, deltaAcumulado);
            return;
        }

        // ── DISPATCH segun el metodo activo ──────────────────────────────────
        // Se abre un EPISODIO DE RESCHEDULING: el cursor de simulacion se
        // congela mientras los agentes negocian, y al cerrarse se cobra la
        // LATENCIA_DECISION_MS. Sin esto, la mina seguia avanzando durante la
        // negociacion (8,3 min de mina por ronda CNP a 100x) y los metodos que
        // mas renegocian quedaban penalizados por un artefacto del reloj.
        switch (ParametrosSimulador.METODO_RESCHEDULING) {
            case PARTIAL_REPAIR:
                EpisodioRescheduling.abrir(true);   // renegocia via CNP: congela
                ejecutarPartialRepair(camion, deltaT, deltaAcumulado, tiempoCorte);
                return;
            case RIGHT_SHIFT_CASCADA:
                EpisodioRescheduling.abrir(false);  // no renegocia: solo cobra latencia
                // sigue abajo (codigo original de cascada)
                break;
            default:
                ultimosDeltaAplicados.put(camion, deltaAcumulado);
                return;
        }

        // Snapshot ANTES del shift: deja evidencia visual del estado previo.
        CapturadorSnapshots.getInstancia().capturarPorEvento("antes_rightshift_" + camion);

        RegistroSimulacion reg = RegistroSimulacion.getInstancia();
        Schedule planificado   = reg.getAgendasCamiones().get(camion);
        if (planificado == null) {
            ultimosDeltaAplicados.put(camion, deltaAcumulado);
            marcarAtendidos(camion, Long.MAX_VALUE);
            return;
        }

        // Evidencia Gantt: foto de los recursos ANTES de mutar nada, para el
        // diagrama antes/despues de la defensa (evidencia_cascadas.csv).
        Map<String, List<String[]>> evidenciaAntes =
                CsvEvidenciaCascada.capturarEstadoRecursos();

        // ── CASCADA: shifteamos al camion disparador + todos los que entran en
        //    conflicto en cualquier pala o destino, hasta punto fijo. La clase
        //    CascadingRightShift muta los slots en RegistroSimulacion y devuelve
        //    el mapa de "quien quedo desplazado y por cuanto".
        CascadingRightShift cascada = new CascadingRightShift();
        CascadingRightShift.Resultado res = cascada.cascadear(camion, deltaT, tiempoCorte,
                VentanaSimulacion.tiempoSimulacionGlobalMs);

        if (res.totalCamionesAfectados() == 0) {
            System.out.println("[ReschedulingAgent] " + camion
                    + ": sin slots futuros para shiftear (turno ya completado).");
            ultimosDeltaAplicados.put(camion, deltaAcumulado);
            marcarAtendidos(camion, Long.MAX_VALUE);
            return;
        }

        // Por cada camion que la cascada movio, armamos su propio
        // DocumentoRescheduling y lo enviamos a el + sus palas + sus destinos.
        // Los receptores ya saben procesar un INFORM por camion (filtran por
        // idCamion en su schedule interno), no hace falta protocolo nuevo.
        int slotsTotalShifteados = 0;
        for (Map.Entry<String, Long> entry : res.deltaPorCamion.entrySet()) {
            String camionAfectado = entry.getKey();
            long   deltaCamion    = entry.getValue();
            long   corteCamion    = res.cortePorCamion.getOrDefault(camionAfectado, tiempoCorte);

            Schedule schC = reg.getAgendasCamiones().get(camionAfectado);
            if (schC == null) continue;

            // Snapshot de los slots que efectivamente se movieron (estan ya en
            // su posicion nueva). Copia profunda para que el DTO no referencie
            // el slot original.
            List<Slot> slotsCopia = new ArrayList<>();
            synchronized (schC) {
                for (Slot s : schC.getSlots()) {
                    if (s.getHoraInicio() <= corteCamion + deltaCamion) continue;
                    slotsCopia.add(copiarSlot(s));
                }
            }
            slotsTotalShifteados += slotsCopia.size();

            // Va el horario (antes, despues) de cada slot movido: los agentes
            // lo copian tal cual, asi su agenda queda igual a la del simulador.
            DocumentoRescheduling docR = new DocumentoRescheduling(
                    camionAfectado, deltaCamion, corteCamion, 2, slotsCopia,
                    res.cambiosPorCamion.get(camionAfectado));

            // 1. Truck
            enviarRescheduling(camionAfectado, docR);

            // 2. Sus palas (CARGA)
            Set<String> palasC = res.palasAfectadasPorCamion
                    .getOrDefault(camionAfectado, java.util.Collections.emptySet());
            for (String pala : palasC) enviarRescheduling(pala, docR);

            // 3. Sus destinos (DESCARGA)
            Set<String> destC = res.destinosAfectadosPorCamion
                    .getOrDefault(camionAfectado, java.util.Collections.emptySet());
            for (String dest : destC) enviarRescheduling(dest, docR);

            // Marcar para evitar re-disparo: si la cascada movio a otros
            // camiones, su delta acumulado debe contar tambien para que el
            // proximo chequearUmbrales no los re-shiftee.
            long previo = ultimosDeltaAplicados.getOrDefault(camionAfectado, 0L);
            ultimosDeltaAplicados.put(camionAfectado, previo + deltaCamion);

            // Lo que la cascada NO movio de este camion (lo ya ocurrido y lo
            // anterior a su corte) queda atendido: su plan se re-programo
            // desde ahi, y desde ahora su atraso se mide contra lo movido.
            marcarAtendidos(camionAfectado, corteCamion);
        }
        if (!res.deltaPorCamion.containsKey(camion)) marcarAtendidos(camion, tiempoCorte);
        // Sobrescribimos el del camion inicial con el deltaAcumulado real (es
        // el valor que viene del retraso medido, no el delta puntual).
        ultimosDeltaAplicados.put(camion, deltaAcumulado);

        totalReschedules++;

        if (!res.convergio) {
            System.err.println("[ReschedulingAgent] WARN: cascada NO convergio en "
                    + res.iteraciones + " iteraciones (tope=" + CascadingRightShift.MAX_ITERACIONES + ")."
                    + " Puede quedar solapamiento residual.");
        }

        System.out.printf("[ReschedulingAgent] #%d RIGHT-SHIFT CASCADA: trigger=%s  " +
                        "camionesAfectados=%d  slotsMovidos=%d  iter=%d  perdidos=%d%n",
                totalReschedules, camion, res.totalCamionesAfectados(),
                slotsTotalShifteados, res.iteraciones, res.slotsPerdidos);

        // schedule despues de esta reprogramacion (lo pidio el profesor)
        CsvSchedulesPorCorrida.guardarSchedule("tras right-shift (trigger " + camion + ")");

        CsvLogger.logMessage(
                getLocalName(), "RIGHT_SHIFT_CASCADA",
                getLocalName(), "trigger=" + camion + " afectados=" + res.deltaPorCamion.keySet(),
                "INFORM", "RESCHEDULE-CASCADA-" + camion,
                "deltaInicial=" + Utilidades.longToHHMMSS(deltaT)
                        + " camiones=" + res.totalCamionesAfectados()
                        + " slots=" + slotsTotalShifteados
                        + " iter=" + res.iteraciones
                        + " perdidos=" + res.slotsPerdidos
                        + " convergio=" + res.convergio
                        + " duracion=" + res.duracionMs + "ms");

        // Dataset para tesis: una fila por cascada en cascadas.csv (mucho mas
        // ligero que parsear el messages.csv para analisis).
        CsvCascadas.log(camion, deltaT, res, slotsTotalShifteados);

        // Evidencia Gantt antes/despues de los recursos afectados.
        CsvEvidenciaCascada.log(camion, evidenciaAntes, res);

        // Snapshot DESPUES del shift: par antes/despues = evidencia del efecto.
        CapturadorSnapshots.getInstancia().capturarPorEvento("despues_rightshift_" + camion);
    }

    // ── PARTIAL REPAIR ─────────────────────────────────────────────────────────

    /**
     * Libera al camion afectado en lugar de empujar el plan en cascada.
     * Borra sus slots futuros (respetando tarea en curso) y manda INFORM
     * "LIBERAR-..." a Truck + sus palas + sus destinos. El CNP renegocia
     * naturalmente a traves del TickerBehaviour de ShovelAgent.
     */
    private void ejecutarPartialRepair(String camion, long deltaT, long deltaAcumulado, long tiempoCorte) {
        // Snapshot ANTES de liberar: estado previo a la renegociacion.
        CapturadorSnapshots.getInstancia().capturarPorEvento("antes_partial_" + camion);

        // Snapshot del schedule ANTES de liberar (para el verificador).
        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        int slotsAntes = 0;
        if (sch != null) {
            synchronized (sch) { slotsAntes = sch.getSlots().size(); }
        }

        // REPARACION PARCIAL CANONICA (AOR): se libera al camion disparador Y a
        // los afectados INDIRECTAMENTE, que es lo que exige la definicion de
        // Abumaizar y Svestka. El corte de cada uno respeta su tarea y su ciclo
        // en curso, y nunca cae en el pasado del reloj de simulacion.
        long relojSim = VentanaSimulacion.tiempoSimulacionGlobalMs;
        PartialRepair partial = new PartialRepair();
        PartialRepair.ResultadoAOR aor =
                partial.liberarAfectados(camion, deltaT, tiempoCorte, relojSim);

        if (aor.slotsLiberados == 0) {
            System.out.println("[ReschedulingAgent] " + camion
                    + ": sin slots futuros para liberar (corte="
                    + Utilidades.longToHHMMSS(tiempoCorte) + ").");
            ultimosDeltaAplicados.put(camion, deltaAcumulado);
            marcarAtendidos(camion, Long.MAX_VALUE);
            return;
        }

        // Mandar INFORM "LIBERAR-..." por CADA camion liberado, mas sus palas y
        // destinos. Cada camion lleva su propio corte y, si se libero segun su
        // realidad, la hora en que queda libre de verdad.
        String motivo = "retraso=" + Utilidades.longToHHMMSS(deltaT)
                + " trigger=" + camion;
        for (PartialRepair.Resultado r : aor.porCamion) {
            DocumentoLiberacion docL =
                    new DocumentoLiberacion(r.camion, r.corte, motivo, r.disponibleDesde);
            // Liberado segun su realidad (el disparador): lo que le queda en el
            // plan (lo ocurrido y el ciclo protegido) ya esta atendido.
            if (r.disponibleDesde >= 0) marcarAtendidos(r.camion, Long.MAX_VALUE);
            enviarLiberacion(r.camion, docL);
            for (String pala : r.palasAfectadas)    enviarLiberacion(pala, docL);
            for (String dest : r.destinosAfectados) enviarLiberacion(dest, docL);

            // Los afectados indirectos se marcan con SU PROPIO retraso, no con
            // el del disparador.
            //
            // Por que hay que marcarlos: el retraso se mide sobre slots YA
            // EJECUTADOS y liberar solo borra slots futuros, asi que el retraso
            // observado de un camion NUNCA baja. Sin marca volveria a disparar
            // en cada ciclo de deteccion, y cada disparo liberaria la flota
            // entera otra vez.
            //
            // Por que con el propio y no con el del disparador: atribuirle a un
            // camion el retraso de otro lo suprime por una perturbacion que no
            // sufrio (un camion con 2 min de retraso quedaba en silencio hasta
            // acumular 45). La regla correcta, y la misma que ya usa el
            // right-shift con su disparador, es que cada operacion reparada
            // registre el nivel de perturbacion al que fue reparada.
            if (!r.camion.equals(camion)) {
                ultimosDeltaAplicados.put(r.camion,
                        retrasoMaxObservado(r.camion, relojSim));
            }

            // Verificacion por camion: cada uno tiene que conseguir su propia
            // reasignacion, y los que no lo logren son los huerfanos.
            VerificadorRescheduling.getInstancia().agendarVerificacion(
                    r.camion, r.corte, System.currentTimeMillis(),
                    r.camion.equals(camion) ? slotsAntes
                                            : r.slotsLiberados + contarSlots(r.camion));

            CsvLiberaciones.log(r.camion, deltaT, r);
        }

        // Todas las palas vuelven a ofrecer su tiempo libre (no solo las que
        // perdieron una carga). Va despues de los LIBERAR para que cada pala
        // ya tenga borrados sus huecos al empezar el barrido.
        pedirReofertaATodasLasPalas();

        // Marcar para no re-disparar inmediatamente sobre el mismo retraso.
        ultimosDeltaAplicados.put(camion, deltaAcumulado);
        // El disparador queda atendido aunque no haya liberado nada (si no, su
        // mismo atraso lo volveria a disparar en el chequeo siguiente).
        marcarAtendidos(camion, Long.MAX_VALUE);
        totalReschedules++;

        System.out.printf("[ReschedulingAgent] #%d PARTIAL REPAIR (AOR): trigger=%s  "
                        + "corte=%s  camionesLiberados=%d (indirectos=%d)  "
                        + "slotsLiberados=%d  palas=%d  destinos=%d  "
                        + "iteracionesCierre=%d  convergio=%s  duracion=%dms%n",
                totalReschedules, camion, Utilidades.longToHHMMSS(tiempoCorte),
                aor.totalCamionesLiberados(), aor.camionesAfectadosIndirectos,
                aor.slotsLiberados, aor.palasAfectadas.size(),
                aor.destinosAfectados.size(), aor.iteracionesCierre,
                aor.cierreConvergio, aor.duracionMs);

        if (!aor.cierreConvergio) {
            System.err.println("[ReschedulingAgent] AVISO: el cierre de afectados "
                    + "no convergio en " + PartialRepair.MAX_ITERACIONES_CIERRE
                    + " iteraciones (trigger=" + camion + ").");
        }

        CsvLogger.logMessage(
                getLocalName(), "PARTIAL_REPAIR",
                getLocalName(), "trigger=" + camion
                        + " camiones=" + aor.totalCamionesLiberados()
                        + " palas=" + aor.palasAfectadas
                        + " destinos=" + aor.destinosAfectados,
                "INFORM", "LIBERAR-" + camion,
                "corte=" + Utilidades.longToHHMMSS(tiempoCorte)
                        + " slots=" + aor.slotsLiberados
                        + " indirectos=" + aor.camionesAfectadosIndirectos
                        + " duracion=" + aor.duracionMs + "ms");

        // Snapshot DESPUES.
        CapturadorSnapshots.getInstancia().capturarPorEvento("despues_partial_" + camion);
    }

    // ── COMPLETE REGENERATION ────────────────────────────────────────────────

    /**
     * Evalua, con una mirada de TODA la flota, si corresponde disparar una
     * regeneracion completa, y la dispara si pasa los gatillos y el anti-bucle.
     *
     * Anti-bucle (en este orden, barato → caro):
     *   1. Tope por turno (MAX_REGEN_POR_TURNO).
     *   2. Cooldown en tiempo de MINA (COOLDOWN_REGEN_SIM_MS) desde la ultima regen.
     *   3. Gatillos de severidad (sistemico o catastrofico).
     *
     * Si RESCHEDULING_ACTIVO esta en false se queda en modo observacion: detecta
     * y loguea el evento que HABRIA disparado, pero no toca el plan (sirve para
     * medir cada cuanto se gatillaria sin pagar el costo).
     */
    private void evaluarRegeneracionGlobal(
            ConcurrentHashMap<String, List<RegistroSimulacion.SlotReal>> reales) {

        long ahora = System.currentTimeMillis();

        // 1. Tope por turno.
        if (totalRegeneraciones >= MAX_REGEN_POR_TURNO) return;
        // Despues del fin del turno no hay nada que replanificar: las palas ya no
        // ofrecen. En grande_V5_COMPLETE la 3a regeneracion fue a las 6:04 y no
        // libero nada, pero gastaba uno de los 3 cupos y contaba como evento.
        if (VentanaSimulacion.tiempoSimulacionGlobalMs >= ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO) return;

        // 2. Cooldown en TIEMPO DE MINA (freno anti-bucle principal).
        //    Antes se media en reloj de pared: 120 s reales equivalian a 3,3
        //    HORAS simuladas a 100x, asi que el cooldown — y no el tope por
        //    turno — era lo que limitaba la regeneracion, y ese limite cambiaba
        //    con la velocidad. Ahora es una decision operacional explicita: no
        //    se regenera el plan global mas de una vez cada media hora de turno.
        long relojSimAhora = VentanaSimulacion.tiempoSimulacionGlobalMs;
        if (ultimaRegeneracionSimMs != Long.MIN_VALUE
                && (relojSimAhora - ultimaRegeneracionSimMs) < COOLDOWN_REGEN_SIM_MS) {
            return;
        }

        // 3. Severidad: contar camiones sobre umbral y el retraso maximo global.
        int  flota = reales.size();
        if (flota == 0) return;

        // CAUSALIDAD: agendasReales esta PRECALCULADO para todo el turno (incluye
        // los retrasos que CAUSARAN fallas futuras como el chancador a las 3:20).
        // Si midieramos sobre todo el turno, el agente "veria el futuro" y
        // dispararia antes de que la perturbacion ocurra fisicamente (se vio:
        // disparo a las 0:08 reaccionando a un retraso de las 3:20). Por eso solo
        // contamos retrasos de slots YA EJECUTADOS: finReal <= reloj de simulacion.
        long relojSim = VentanaSimulacion.tiempoSimulacionGlobalMs;
        if (relojSim <= 0) return;  // sim aun no arranco → nada ocurrido todavia

        int  camionesEnUmbral = 0;
        long retrasoMaxGlobal = 0;
        for (Map.Entry<String, List<RegistroSimulacion.SlotReal>> eR : reales.entrySet()) {
            List<RegistroSimulacion.SlotReal> slots = eR.getValue();
            if (slots == null || slots.isEmpty()) continue;
            Set<Slot> atendidos = slotsAtendidos.get(eR.getKey());
            long rmax = 0;
            for (RegistroSimulacion.SlotReal sr : slots) {
                if (sr.finReal > relojSim) continue;       // aun no ocurrio
                // Solo retrasos del plan vigente: lo anterior ya se regenero.
                if (atendidos != null && atendidos.contains(sr.slotPlanificado)) continue;
                if (sr.retraso > rmax) rmax = sr.retraso;
            }
            if (rmax >= UMBRAL_NIVEL2) camionesEnUmbral++;
            if (rmax > retrasoMaxGlobal) retrasoMaxGlobal = rmax;
        }

        int umbralCamiones = Math.max(MIN_CAMIONES_REGEN,
                (int) Math.ceil(FRACCION_FLOTA_REGEN * flota));
        boolean sistemico    = camionesEnUmbral >= umbralCamiones;
        boolean catastrofico = retrasoMaxGlobal >= UMBRAL_REGEN_INDIVIDUAL;

        if (!sistemico && !catastrofico) return;

        String motivo = sistemico
                ? "SISTEMICO(" + camionesEnUmbral + "/" + flota + " >= " + umbralCamiones + ")"
                : "CATASTROFICO(retrasoMax=" + Utilidades.longToHHMMSS(retrasoMaxGlobal) + ")";

        // t0 = estado actual de la mina = reloj de simulacion (mismo instante
        // hasta el que medimos los retrasos, asi el corte es causal y coherente).
        long t0 = relojSim;

        // ── MODO OBSERVACION ────────────────────────────────────────────────
        if (!ParametrosSimulador.RESCHEDULING_ACTIVO) {
            System.out.printf("[ReschedulingAgent] REGEN OBSERVADA (sin actuar): %s  t0=%s%n",
                    motivo, Utilidades.longToHHMMSS(Math.max(0, t0)));
            CsvLogger.logMessage(
                    getLocalName(), "REGEN_OMITIDA",
                    getLocalName(), "flota",
                    "INFORM", "OBSERV-REGEN",
                    motivo + " t0=" + Utilidades.longToHHMMSS(Math.max(0, t0)));
            // Marcamos cooldown igual para no spamear el log cada 5s.
            ultimaRegeneracionSimMs = relojSimAhora;
            return;
        }

        // Complete Regeneration renegocia TODA la flota via CNP: es el metodo
        // que mas se beneficia de que el cursor no avance mientras negocia.
        EpisodioRescheduling.abrir(true);
        ejecutarCompleteRegeneration(t0, camionesEnUmbral, flota, retrasoMaxGlobal, motivo);
    }

    /**
     * Descarta el plan futuro de TODA la flota y deja que el CNP reconstruya
     * desde cero, tomando t0 (reloj de simulacion) como nuevo punto de partida.
     *
     * Implementacion: es un Partial Repair aplicado a CADA camion con el MISMO
     * corte global t0. Por cada camion:
     *   1. Se liberan sus slots con horaInicio > t0 (la tarea en curso, que
     *      ABARCA t0, se preserva → no se interrumpe lo que ya esta pasando).
     *   2. Se envia INFORM "LIBERAR-" al camion + sus palas + sus destinos, que
     *      borran de sus agendas internas lo asignado a ese camion. El camion
     *      ademas resetea su estado de negociacion y vuelve a aceptar CFPs.
     * Tras vaciar los planes, los TickerBehaviour de las palas detectan vacantes
     * y arrancan nuevas negociaciones CNP: el plan global se reconstruye solo.
     */
    private void ejecutarCompleteRegeneration(long t0, int camionesEnUmbral,
                                              int flota, long retrasoMaxGlobal,
                                              String motivo) {
        long tInicio = System.currentTimeMillis();
        CapturadorSnapshots.getInstancia().capturarPorEvento("antes_regen_global");

        java.util.Map<String, Schedule> agendas =
                RegistroSimulacion.getInstancia().getAgendasCamiones();
        PartialRepair partial = new PartialRepair();

        int camionesLiberados = 0;
        int slotsLiberadosTotal = 0;
        Set<String> palasGlobal    = new HashSet<>();
        Set<String> destinosGlobal = new HashSet<>();

        // Copia de claves: liberar() muta los schedules mientras iteramos.
        List<String> camiones = new ArrayList<>(agendas.keySet());
        String motivoMsg = "regen-global " + motivo;

        for (String camion : camiones) {
            // Contar slots ANTES de liberar (para el verificador).
            Schedule sch = agendas.get(camion);
            int slotsAntes = 0;
            if (sch != null) {
                synchronized (sch) { slotsAntes = sch.getSlots().size(); }
            }

            // Se libera segun lo que el camion esta haciendo DE VERDAD y se le
            // avisa cuando queda libre (ver PartialRepair.liberarDesdeRealidad).
            PartialRepair.Resultado res = partial.liberarDesdeRealidad(camion, t0);
            if (res.slotsLiberados == 0) continue;  // ya no tenia futuro que tirar

            camionesLiberados++;
            slotsLiberadosTotal += res.slotsLiberados;
            palasGlobal.addAll(res.palasAfectadas);
            destinosGlobal.addAll(res.destinosAfectados);

            DocumentoLiberacion docL = new DocumentoLiberacion(camion, res.corte, motivoMsg,
                    res.disponibleDesde);
            enviarLiberacion(camion, docL);
            for (String pala : res.palasAfectadas)    enviarLiberacion(pala, docL);
            for (String dest : res.destinosAfectados) enviarLiberacion(dest, docL);

            // Verificacion diferida en tiempo real (igual que Partial Repair).
            VerificadorRescheduling.getInstancia()
                    .agendarVerificacion(camion, res.corte,
                            System.currentTimeMillis(), slotsAntes);

            // Anti re-disparo per-camion: registramos su retraso como ya atendido.
            ultimosDeltaAplicados.put(camion, retrasoMaxGlobal);
        }

        // Lo que queda en las agendas tras liberar es el plan anterior (pasado y
        // ciclos protegidos): sus retrasos no cuentan para el proximo disparo.
        for (String camion : camiones) marcarAtendidos(camion, Long.MAX_VALUE);

        // Todas las palas vuelven a ofrecer su tiempo libre hasta el fin del turno.
        if (camionesLiberados > 0) pedirReofertaATodasLasPalas();

        long duracion = System.currentTimeMillis() - tInicio;
        ultimaRegeneracionSimMs = VentanaSimulacion.tiempoSimulacionGlobalMs;
        totalRegeneraciones++;

        System.out.printf("[ReschedulingAgent] #%d COMPLETE REGENERATION: %s  " +
                        "t0=%s  camionesLiberados=%d/%d  slots=%d  palas=%d  destinos=%d  duracion=%dms%n",
                totalRegeneraciones, motivo, Utilidades.longToHHMMSS(Math.max(0, t0)),
                camionesLiberados, flota, slotsLiberadosTotal,
                palasGlobal.size(), destinosGlobal.size(), duracion);

        CsvLogger.logMessage(
                getLocalName(), "COMPLETE_REGENERATION",
                getLocalName(), "flota(" + camionesLiberados + "/" + flota + ")",
                "INFORM", "REGEN-" + totalRegeneraciones,
                motivo + " t0=" + Utilidades.longToHHMMSS(Math.max(0, t0))
                        + " slots=" + slotsLiberadosTotal
                        + " palas=" + palasGlobal.size()
                        + " destinos=" + destinosGlobal.size()
                        + " duracion=" + duracion + "ms");

        // Dataset para tesis: una fila por regeneracion en regeneraciones.csv.
        CsvRegeneraciones.log(motivo, t0, flota, camionesEnUmbral,
                retrasoMaxGlobal, camionesLiberados, slotsLiberadosTotal,
                palasGlobal.size(), destinosGlobal.size(),
                totalRegeneraciones, duracion);

        CapturadorSnapshots.getInstancia().capturarPorEvento("despues_regen_global");
    }

    /**
     * Pide a TODAS las palas que recorran su tiempo libre desde el reloj actual
     * hasta el fin del turno (reunion con el profesor del 2-oct-2026). El
     * episodio de renegociacion queda esperando a que cada una termine.
     */
    private void pedirReofertaATodasLasPalas() {
        int gen = EpisodioRescheduling.nuevaGeneracionBarrido();
        long desde = VentanaSimulacion.tiempoSimulacionGlobalMs;
        int n = 0;
        for (java.util.Map.Entry<String, RegistroAgendasAgentes.Entrada> e
                : RegistroAgendasAgentes.getAgendas().entrySet()) {
            if (!RegistroAgendasAgentes.PALA.equals(e.getValue().tipo)) continue;
            String pala = e.getKey();
            EpisodioRescheduling.esperarBarrido(pala, gen);
            try {
                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(new AID(pala, AID.ISLOCALNAME));
                msg.setConversationId("REOFERTA-" + pala + "-" + gen);
                msg.setContentObject(new DocumentoReoferta(gen, desde));
                send(msg);
                n++;
            } catch (IOException ex) {
                EpisodioRescheduling.barridoTerminado(pala, gen);
                System.err.println("[ReschedulingAgent] error enviando REOFERTA a " + pala);
            }
        }
        System.out.printf("[ReschedulingAgent] RE-OFERTA TOTAL pedida a %d palas desde %s (barrido %d)%n",
                n, Utilidades.longToHHMMSS(Math.max(0, desde)), gen);
    }

    /** Envia un INFORM con DocumentoLiberacion (convId "LIBERAR-..."). */
    private void enviarLiberacion(String agenteDestino, DocumentoLiberacion docL) {
        try {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(new AID(agenteDestino, AID.ISLOCALNAME));
            String convId = "LIBERAR-" + docL.getIdCamion()
                          + "-" + System.currentTimeMillis();
            msg.setConversationId(convId);
            msg.setContentObject(docL);
            send(msg);

            CsvLogger.logMessage(
                    getLocalName(), "SEND LIBERAR_INFORM",
                    getLocalName(), agenteDestino,
                    "INFORM", convId,
                    "partial repair " + docL.getIdCamion()
                            + " corte=" + Utilidades.longToHHMMSS(docL.getCorte()));
        } catch (IOException e) {
            System.err.println("[ReschedulingAgent] error serializando LIBERAR para "
                    + agenteDestino + ": " + e.getMessage());
        }
    }

    // ── Envío ──────────────────────────────────────────────────────────────────

    /**
     * Construye y envía el ACLMessage.INFORM con el DocumentoRescheduling.
     *
     * convId = "RESCHEDULE-<camion>-<timestamp>" → los agentes receptores
     * identifican por el prefijo "RESCHEDULE-" que este INFORM es de
     * rescheduling y lo manejan por separado del flujo Contract-Net.
     */
    private void enviarRescheduling(String agenteDestino, DocumentoRescheduling docR) {
        try {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(new AID(agenteDestino, AID.ISLOCALNAME));
            String convId = "RESCHEDULE-" + docR.getIdCamion()
                          + "-" + System.currentTimeMillis();
            msg.setConversationId(convId);
            msg.setContentObject(docR);
            send(msg);

            CsvLogger.logMessage(
                    getLocalName(), "SEND RESCHEDULE_INFORM",
                    getLocalName(), agenteDestino,
                    "INFORM", convId,
                    "right-shift " + docR.getIdCamion()
                            + " +" + Utilidades.longToHHMMSS(docR.getDeltaT()));
        } catch (IOException e) {
            System.err.println("[ReschedulingAgent] error serializando para "
                    + agenteDestino + ": " + e.getMessage());
        }
    }

    // ── Utilidades ─────────────────────────────────────────────────────────────

    /** Copia superficial de un Slot (no referencias compartidas con el original). */
    private Slot copiarSlot(Slot orig) {
        Slot c = new Slot();
        c.setMaquinaPropietariaSchedule(orig.getMaquinaPropietariaSchedule());
        c.setMaquina       (orig.getMaquina());
        c.setOperacion     (orig.getOperacion());
        c.setUbicacion     (orig.getUbicacion());
        c.setCantidadMaterial(orig.getCantidadMaterial());
        c.setHoraInicio    (orig.getHoraInicio());  // ya shiftado
        c.setHoraFin       (orig.getHoraFin());     // ya shiftado
        return c;
    }

    @Override
    protected void takeDown() {
        System.out.println("[ReschedulingAgent] finalizando. "
                + "Total reschedules emitidos: " + totalReschedules);
    }
}
