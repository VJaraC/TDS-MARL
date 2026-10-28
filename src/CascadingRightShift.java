import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Right-shift CON cascada por recurso.
 *
 * ─── Diferencia con el right-shift original ─────────────────────────────────
 * El original (ReschedulingAgent.ejecutarRightShift) solo desplaza los slots
 * futuros del camion disparador. Si TruckX se desplaza de 10:00-10:20 a
 * 10:15-10:35 en Pala_A, y TruckY tenia cargar en Pala_A en 10:20-10:40, queda
 * un DOBLE-BOOKING en 10:20-10:35. Los agentes lo aplican igual y la
 * negociacion posterior queda inconsistente.
 *
 * Esta clase resuelve eso iterando hasta un PUNTO FIJO:
 *   1. Aplica el shift al camion inicial.
 *   2. Detecta solapamientos en TODOS los recursos compartidos (palas + destinos).
 *   3. Por cada solape (A antes, B despues), shiftea a B por (A.fin - B.inicio)
 *      para que arranque exactamente cuando A termina.
 *   4. Vuelve al paso 2 (el shift de B puede generar nuevos solapes en otras
 *      palas/destinos de B).
 *   5. Termina cuando una iteracion completa no encuentra solapes.
 *
 * Garantia: a la salida, ningun recurso (pala o destino) tiene dos slots
 * solapados. Costo: O(iteraciones * slots^2). Tope de seguridad: MAX_ITERACIONES.
 *
 * ─── KPI: tareas perdidas ──────────────────────────────────────────────────
 * Si un slot termina mas alla del fin de turno (SEGUNDO_FINAL_HORARIO), se
 * registra en slotsPerdidos. Es un KPI critico para tesis: el right-shift
 * cascada PUEDE perder tareas, eso forma parte del costo del metodo.
 *
 * ─── Recursos compartidos considerados ─────────────────────────────────────
 * Solo CARGA (en pala) y DESCARGA (en destino). Los slots VIAJE_VACIO y
 * VIAJE_CARGADO son rutas y no se consideran recurso compartido (igual que
 * en el right-shift original).
 *
 * ─── Capacidad y pasado (revision del 2026-10-05) ──────────────────────────
 * Antes todo destino se trataba como de UN camion. Los botaderos admiten 99
 * descargas a la vez (maximumDischarge del OWL, lo mismo que usa el
 * simulador), asi que el plan inicial ya traia ~200 "choques" que no existen
 * y la cascada empujaba a toda la flota sin converger. Ahora un destino solo
 * choca cuando se supera su capacidad (ver paresEnConflicto); palas y
 * chancador siguen con capacidad 1 y para ellos el criterio no cambia.
 * Ademas la cascada ya no mueve tareas que empezaron antes del reloj: un
 * choque cuyo segundo slot ya empezo pertenece al pasado y se ignora (en
 * grande_V3_CASCADA se habian movido 391 tareas ya terminadas).
 */
public class CascadingRightShift {

    /** Tope de iteraciones (seguridad anti-ciclo).
     *  Subido de 50 a 200 para disambiguar convergencia lenta (H1) vs ciclo
     *  verdadero (H2) en cascadas que tocan casi toda la flota. Si una cascada
     *  sigue sin converger en 200 iter es evidencia fuerte de bucle de
     *  dependencias circular en los recursos. */
    public static final int MAX_ITERACIONES = 200;

    /** Limite del turno: slots que terminan mas alla son "perdidos". */
    public static final long LIMITE_TURNO_MS = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO;

    // ── Estado acumulado de la cascada ──────────────────────────────────────
    private final Map<String, Long> deltaTotalPorCamion       = new HashMap<>();
    private final Map<String, Long> corteAplicadoPorCamion    = new HashMap<>();
    // Set (no List): un mismo slot se empuja en varias iteraciones de la cascada;
    // con List se contaba repetido (daba ~98k "perdidos" con solo ~2400 slots).
    // Como Slot no redefine equals/hashCode, el Set dedupe por identidad de objeto.
    private final Set<Slot>         slotsPerdidos             = new HashSet<>();
    private int                     iteracionesUsadas         = 0;
    // Horario de cada slot ANTES de la cascada (identidad de objeto). Un slot
    // puede moverse en varias iteraciones y por montos distintos que otros
    // slots del mismo camion: para que las agendas de los agentes queden
    // identicas a la del simulador hay que mandarles el par (antes, despues)
    // de cada slot, no un delta total.
    private final Map<Slot, long[]> horarioOriginal = new java.util.IdentityHashMap<>();
    private final Map<String, List<Slot>> movidosPorCamion = new HashMap<>();

    // ── Resultado serializable para reporte ─────────────────────────────────
    public static class Resultado {
        public final Map<String, Long> deltaPorCamion;
        public final Map<String, Long> cortePorCamion;
        public final Map<String, Set<String>> palasAfectadasPorCamion;
        public final Map<String, Set<String>> destinosAfectadosPorCamion;
        public final int slotsPerdidos;
        public final int iteraciones;
        public final boolean convergio;
        /** Tiempo de wall clock que tardo la cascada en computarse (ms). */
        public final long duracionMs;
        /** Por camion: {inicioAntes, finAntes, inicioDespues, finDespues, op}
         *  de cada slot que la cascada movio. Es lo que se envia a los agentes. */
        public final Map<String, List<long[]>> cambiosPorCamion;

        Resultado(Map<String, Long> d, Map<String, Long> c,
                  Map<String, Set<String>> pp, Map<String, Set<String>> dp,
                  int perdidos, int iters, boolean conv, long duracion,
                  Map<String, List<long[]>> cambios) {
            this.deltaPorCamion             = d;
            this.cortePorCamion             = c;
            this.palasAfectadasPorCamion    = pp;
            this.destinosAfectadosPorCamion = dp;
            this.slotsPerdidos              = perdidos;
            this.iteraciones                = iters;
            this.convergio                  = conv;
            this.duracionMs                 = duracion;
            this.cambiosPorCamion           = cambios;
        }

        public int totalCamionesAfectados() { return deltaPorCamion.size(); }
    }

    // ── API publica ─────────────────────────────────────────────────────────

    /**
     * Aplica el right-shift en cascada arrancando desde camionInicial.
     * MUTA los slots en RegistroSimulacion en su lugar.
     *
     * @param camionInicial   camion disparador (el que cruzo el umbral).
     * @param deltaInicial    ms a desplazar los slots de camionInicial.
     * @param tiempoCorteInicial  solo se shiftean slots con horaInicio > corte.
     * @param relojSim        reloj de simulacion: nada que haya empezado antes
     *                        se mueve (el pasado es inmutable).
     */
    public Resultado cascadear(String camionInicial, long deltaInicial,
                                long tiempoCorteInicial, long relojSim) {
        long t0 = System.currentTimeMillis();
        // 1. Shift inicial
        aplicarShiftACamion(camionInicial, deltaInicial, Math.max(tiempoCorteInicial, relojSim));

        // 2. Punto fijo
        boolean huboCambios;
        do {
            huboCambios = false;
            iteracionesUsadas++;

            List<Conflicto> conflictos = detectarConflictos(relojSim);
            if (conflictos.isEmpty()) break;

            // Agrupamos por camion-a-shiftear quedandonos con el MAX delta
            // necesario para resolver TODOS sus conflictos en una sola iteracion.
            // (Si una iteracion deja conflictos residuales por cambios cruzados,
            // la siguiente iteracion los limpia.)
            Map<String, Long>  deltaPorCamionAShiftear = new HashMap<>();
            Map<String, Long>  cortePorCamionAShiftear = new HashMap<>();

            for (Conflicto c : conflictos) {
                long delta = c.finPrimero - c.inicioSegundo;  // exacto, sin solape
                if (delta <= 0) continue;

                String camion = c.camionSegundo;
                Long prev = deltaPorCamionAShiftear.get(camion);
                if (prev == null || delta > prev) {
                    deltaPorCamionAShiftear.put(camion, delta);
                    cortePorCamionAShiftear.put(camion, Math.max(c.inicioSegundo - 1, relojSim));
                }
            }

            for (Map.Entry<String, Long> e : deltaPorCamionAShiftear.entrySet()) {
                String camion = e.getKey();
                aplicarShiftACamion(camion, e.getValue(),
                        cortePorCamionAShiftear.get(camion));
                huboCambios = true;
            }
        } while (huboCambios && iteracionesUsadas < MAX_ITERACIONES);

        boolean convergio = iteracionesUsadas < MAX_ITERACIONES;

        // 3. Armar mapas de palas y destinos afectados POR CAMION (para que
        //    ReschedulingAgent sepa a quienes enviar el INFORM).
        Map<String, Set<String>> palasPorCamion    = new HashMap<>();
        Map<String, Set<String>> destinosPorCamion = new HashMap<>();
        ConcurrentHashMap<String, Schedule> ag =
                RegistroSimulacion.getInstancia().getAgendasCamiones();

        for (String camion : deltaTotalPorCamion.keySet()) {
            Schedule sch = ag.get(camion);
            if (sch == null) continue;
            long corte = corteAplicadoPorCamion.getOrDefault(camion, Long.MIN_VALUE);
            Set<String> palas    = new HashSet<>();
            Set<String> destinos = new HashSet<>();
            synchronized (sch) {
                for (Slot s : sch.getSlots()) {
                    // Solo nos importan los slots que fueron shifteados (futuros al corte).
                    // Tras el shift quedaron con horaInicio > corte + delta, lo aproximamos
                    // chequeando horaInicio > corte directamente.
                    if (s.getHoraInicio() <= corte) continue;
                    if (s.getOperacion() == Slot.enum_operacion.CARGA
                            && s.getMaquina() != null && !s.getMaquina().trim().isEmpty()) {
                        palas.add(s.getMaquina());
                    } else if (s.getOperacion() == Slot.enum_operacion.DESCARGA
                            && s.getUbicacion() != null) {
                        String d = s.getUbicacion().replace("#", "").trim();
                        if (!d.isEmpty()) destinos.add(d);
                    }
                }
            }
            palasPorCamion.put(camion, palas);
            destinosPorCamion.put(camion, destinos);
        }

        Map<String, List<long[]>> cambios = new HashMap<>();
        for (Map.Entry<String, List<Slot>> e : movidosPorCamion.entrySet()) {
            List<long[]> lista = new ArrayList<>();
            for (Slot s : e.getValue()) {
                long[] antes = horarioOriginal.get(s);
                lista.add(new long[]{antes[0], antes[1], s.getHoraInicio(), s.getHoraFin(),
                        s.getOperacion().ordinal()});
            }
            cambios.put(e.getKey(), lista);
        }

        long duracion = System.currentTimeMillis() - t0;
        return new Resultado(
                new HashMap<>(deltaTotalPorCamion),
                new HashMap<>(corteAplicadoPorCamion),
                palasPorCamion,
                destinosPorCamion,
                slotsPerdidos.size(),
                iteracionesUsadas,
                convergio,
                duracion,
                cambios);
    }

    // ── Internas ────────────────────────────────────────────────────────────

    /**
     * Aplica el shift a los slots futuros del camion (horaInicio > corte).
     * Acumula el delta. Registra slots que cayeron fuera del turno.
     */
    private void aplicarShiftACamion(String camion, long delta, long corte) {
        if (delta <= 0) return;
        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        if (sch == null) return;

        synchronized (sch) {
            for (Slot s : sch.getSlots()) {
                if (s.getHoraInicio() <= corte) continue;
                if (!horarioOriginal.containsKey(s)) {
                    horarioOriginal.put(s, new long[]{s.getHoraInicio(), s.getHoraFin()});
                    movidosPorCamion.computeIfAbsent(camion, k -> new ArrayList<>()).add(s);
                }
                long nuevoIni = s.getHoraInicio() + delta;
                long nuevoFin = s.getHoraFin()    + delta;
                s.setHoraInicio(nuevoIni);
                s.setHoraFin   (nuevoFin);
                if (nuevoIni > LIMITE_TURNO_MS) {
                    slotsPerdidos.add(s);
                }
            }
        }
        deltaTotalPorCamion.merge(camion, delta, Long::sum);
        // Guardamos el corte mas TEMPRANO aplicado a este camion (el resto
        // de shifts caen igualmente despues, asi que el mas temprano es el
        // mas representativo del "punto desde donde se desplazo").
        corteAplicadoPorCamion.merge(camion, corte, Math::min);
    }

    /**
     * Recorre todos los schedules de camiones, agrupa los slots por recurso
     * compartido (pala / destino) y devuelve los pares que superan la
     * capacidad del recurso. Se omiten los choques cuyo segundo slot empezo
     * antes del reloj: ya ocurrieron y no se pueden arreglar moviendo nada.
     */
    private List<Conflicto> detectarConflictos(long relojSim) {
        Map<String, List<Slot>> porRecurso = new HashMap<>();
        ConcurrentHashMap<String, Schedule> ag =
                RegistroSimulacion.getInstancia().getAgendasCamiones();

        for (Schedule sch : ag.values()) {
            synchronized (sch) {
                for (Slot s : sch.getSlots()) {
                    String key = keyDeRecurso(s);
                    if (key == null) continue;
                    porRecurso.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
                }
            }
        }

        List<Conflicto> conflictos = new ArrayList<>();
        for (Map.Entry<String, List<Slot>> e : porRecurso.entrySet()) {
            List<Slot> slots = e.getValue();
            Collections.sort(slots, Comparator.comparingLong(Slot::getHoraInicio));
            long[] ini = new long[slots.size()], fin = new long[slots.size()];
            for (int i = 0; i < slots.size(); i++) {
                ini[i] = slots.get(i).getHoraInicio();
                fin[i] = slots.get(i).getHoraFin();
            }
            for (int[] p : paresEnConflicto(ini, fin, capacidadDeRecurso(e.getKey()))) {
                Slot a = slots.get(p[0]);
                Slot b = slots.get(p[1]);
                if (b.getHoraInicio() <= relojSim) continue;   // choque del pasado
                conflictos.add(new Conflicto(
                        e.getKey(),
                        a.getMaquinaPropietariaSchedule(), a.getHoraFin(),
                        b.getMaquinaPropietariaSchedule(), b.getHoraInicio()));
            }
        }
        return conflictos;
    }

    /**
     * Pares {bloqueador, desplazado} (indices) de un recurso con capacidad
     * cap. Los slots vienen ordenados por inicio. Con cap = 1 es el criterio
     * de siempre: cada slot contra el siguiente. Con cap > 1 (botaderos) hay
     * choque solo cuando un slot empieza con cap slots todavia ocupando el
     * recurso, y el bloqueador es el primero que lo desocupa.
     * Package-static: PartialRepair usa el mismo criterio.
     */
    static List<int[]> paresEnConflicto(long[] ini, final long[] fin, int cap) {
        List<int[]> pares = new ArrayList<>();
        if (cap <= 1) {
            for (int i = 0; i < ini.length - 1; i++) {
                if (ini[i + 1] < fin[i]) pares.add(new int[]{i, i + 1});
            }
            return pares;
        }
        PriorityQueue<Integer> ocupando =
                new PriorityQueue<>(Comparator.<Integer>comparingLong(k -> fin[k]));
        for (int j = 0; j < ini.length; j++) {
            while (!ocupando.isEmpty() && fin[ocupando.peek()] <= ini[j]) ocupando.poll();
            if (ocupando.size() >= cap) pares.add(new int[]{ocupando.peek(), j});
            ocupando.add(j);
        }
        return pares;
    }

    /**
     * Cuantos camiones admite a la vez el recurso: pala = 1; destino = su
     * maximumDischarge del OWL (la misma capacidad que usa el simulador al
     * ejecutar). Si el destino no esta registrado se asume 1, el criterio
     * anterior.
     */
    static int capacidadDeRecurso(String key) {
        if (key == null || !key.startsWith("DEST:")) return 1;
        String nombre = key.substring("DEST:".length());
        Map<String, Integer> caps = RegistroSimulacion.getInstancia().getCapacidadDestinos();
        Integer c = caps.get("#" + nombre);
        if (c == null) c = caps.get(nombre);
        return (c == null) ? 1 : Math.max(1, c);
    }

    /**
     * Clave del recurso compartido segun el tipo de operacion del slot.
     * Solo CARGA (pala) y DESCARGA (destino) producen conflicto. VIAJE_* son rutas.
     * Package-static para que CsvEvidenciaCascada agrupe con el MISMO criterio.
     */
    static String keyDeRecurso(Slot s) {
        if (s.getOperacion() == Slot.enum_operacion.CARGA
                && s.getMaquina() != null && !s.getMaquina().trim().isEmpty()) {
            return "PALA:" + s.getMaquina();
        }
        if (s.getOperacion() == Slot.enum_operacion.DESCARGA
                && s.getUbicacion() != null) {
            String d = s.getUbicacion().replace("#", "").trim();
            if (!d.isEmpty()) return "DEST:" + d;
        }
        return null;
    }

    /** Par de slots solapados en un mismo recurso. */
    private static class Conflicto {
        final String recurso;
        final String camionPrimero;
        final long   finPrimero;
        final String camionSegundo;
        final long   inicioSegundo;

        Conflicto(String r, String cP, long fP, String cS, long iS) {
            this.recurso       = r;
            this.camionPrimero = cP;
            this.finPrimero    = fP;
            this.camionSegundo = cS;
            this.inicioSegundo = iS;
        }
    }
}
