import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metodo Partial Repair, tambien llamado AFFECTED OPERATIONS RESCHEDULING (AOR).
 *
 * ─── Definicion de la literatura ────────────────────────────────────────────
 * Abumaizar y Svestka (1997) y la taxonomia de Vieira, Herrmann y Lin (2003)
 * definen la reparacion parcial como: se reprograman UNICAMENTE las operaciones
 * afectadas por la perturbacion —de forma DIRECTA E INDIRECTA— y el resto del
 * cronograma conserva su programacion original. Eso es lo que la separa de la
 * regeneracion completa, que descarta el plan entero.
 *
 * "Afectada indirectamente" se determina propagando desde la operacion
 * perturbada por dos tipos de relacion:
 *
 *   - Arco conjuntivo (precedencia dentro del job): los slots siguientes del
 *     MISMO camion en su cadena de ciclo.
 *   - Arco disyuntivo (secuencia en la maquina): los slots de OTROS camiones
 *     en la misma pala o punto de descarga, que quedan infactibles porque el
 *     recurso ya no se libera a tiempo.
 *
 * ─── Que hace esta implementacion ──────────────────────────────────────────
 * Cuando un camion cruza el umbral de retraso:
 *
 *   1. Se calcula el CIERRE TRANSITIVO de camiones afectados: se propaga el
 *      retraso sobre una copia del plan y se anota a quien alcanza, hasta
 *      punto fijo (ver calcularAfectados).
 *   2. Se LIBERAN los slots futuros de TODOS esos camiones: se borran del plan
 *      y el CNP los re-asigna renegociando con las palas.
 *
 * El cierre se calcula igual que la cascada de right-shift (mismos recursos
 * compartidos, mismo criterio de solape, mismo punto fijo). Desde el
 * 2026-10-05 ese criterio respeta la capacidad de cada destino (un botadero
 * admite 99 descargas a la vez) e ignora los choques que ya ocurrieron: antes
 * el cierre alcanzaba a toda la flota por choques que no existian o que eran
 * del pasado y no se podian arreglar. La diferencia
 * entre los dos metodos no esta en COMO se encuentra el conjunto afectado sino
 * en QUE se hace con el: la cascada lo DESPLAZA, el AOR lo LIBERA para que
 * renegocie. Por eso la redireccion a palas menos saturadas ocurre sola: las
 * palas mas libres ofrecen mejores tiempos en los CFP.
 *
 * ─── Nota sobre una desviacion declarada ───────────────────────────────────
 * En el AOR de taller, reprogramar preserva la SECUENCIA en cada maquina y
 * solo recalcula tiempos. Aca reprogramar significa renegociar por CNP, asi
 * que la secuencia en cada pala tambien puede cambiar. Es una adaptacion
 * necesaria al enfoque multi-agente y esta declarada como tal en el informe.
 *
 * ─── Costo ─────────────────────────────────────────────────────────────────
 * Latencia de renegociacion y posibilidad de que un camion quede "huerfano"
 * si ninguna pala logra colocarlo. Eso se mide con VerificadorRescheduling.
 * Cuantos mas camiones libera un disparo, mayor es la presion sobre el CNP:
 * ese es justamente el eje que separa a los tres metodos.
 *
 * ─── Punto de corte ────────────────────────────────────────────────────────
 * Si el camion esta ejecutando un slot AHORA (horaInicio <= tNow < horaFin),
 * el corte = horaFin de ese slot. Asi NO se interrumpe la tarea en curso:
 * el camion termina lo que esta haciendo y despues empieza a renegociar.
 *
 * Si el camion esta en hueco/idle, el corte = tNow.
 *
 * ─── PROTECCION DE CICLO (validado con el profesor) ───────────────────────
 * Un ciclo de camion = VIAJE_VACIO -> CARGA -> VIAJE_CARGADO -> DESCARGA. Si
 * el corte cae dentro de CARGA, VIAJE_CARGADO o DESCARGA, el camion ya paso
 * el "punto de no retorno": ya se comprometio con una pala especifica y el
 * material ya esta (o va a estar) en su tolva. Fisicamente NO puede cancelar
 * el resto de ese ciclo — debe terminar de cargar, transportarlo y
 * descargarlo. Por eso el corte se EXTIENDE hasta el fin de la DESCARGA de
 * ese ciclo; solo el PROXIMO ciclo (proximo VIAJE_VACIO en adelante) queda
 * libre para renegociar.
 *
 * Si el corte cae dentro de VIAJE_VACIO (el camion todavia no llego a
 * ninguna pala) o el camion esta idle entre ciclos, el corte NO se extiende:
 * en ese punto el camion aun puede redirigirse sin costo fisico.
 *
 * ─── Slots eliminados ──────────────────────────────────────────────────────
 * Todos los slots de RegistroSimulacion.agendasCamiones[truck] cuyo
 * horaInicio > corte se ELIMINAN (Iterator.remove). No se shiftean ni se
 * preservan: el truck arranca con plan vacio post-corte y arma uno nuevo
 * a traves de la negociacion.
 *
 * Las palas y destinos afectados se reportan para que ReschedulingAgent
 * les mande INFORM "LIBERAR-" y ellos hagan lo propio (borrar slots del
 * truck en sus agendas internas).
 */
public class PartialRepair {

    /** Resultado de una liberacion para reporte. */
    public static class Resultado {
        public final String       camion;
        public final long         corte;             // tiempo de corte aplicado
        public final int          slotsLiberados;    // count de slots borrados
        public final Set<String>  palasAfectadas;    // palas que tenian carga del truck
        public final Set<String>  destinosAfectados; // destinos que tenian descarga
        public final boolean      tareaEnCurso;      // true si respetamos slot activo
        public final long         duracionMs;        // overhead de computo
        /** Hora real en que el camion queda libre; -1 si no se calculo
         *  (ver liberarDesdeRealidad). Viaja en el DocumentoLiberacion. */
        public final long         disponibleDesde;

        Resultado(String camion, long corte, int slotsLiberados,
                  Set<String> palas, Set<String> destinos,
                  boolean tareaEnCurso, long duracionMs) {
            this(camion, corte, slotsLiberados, palas, destinos, tareaEnCurso, duracionMs, -1L);
        }

        Resultado(String camion, long corte, int slotsLiberados,
                  Set<String> palas, Set<String> destinos,
                  boolean tareaEnCurso, long duracionMs, long disponibleDesde) {
            this.camion            = camion;
            this.corte             = corte;
            this.slotsLiberados    = slotsLiberados;
            this.palasAfectadas    = palas;
            this.destinosAfectados = destinos;
            this.tareaEnCurso      = tareaEnCurso;
            this.duracionMs        = duracionMs;
            this.disponibleDesde   = disponibleDesde;
        }
    }

    /** Resultado agregado de una reparacion AOR sobre todos los afectados. */
    public static class ResultadoAOR {
        public final String        camionDisparador;
        /** Una entrada por camion liberado (el disparador incluido). */
        public final List<Resultado> porCamion;
        public final Set<String>   palasAfectadas;
        public final Set<String>   destinosAfectados;
        public final int           slotsLiberados;
        public final int           camionesAfectadosIndirectos;
        public final int           iteracionesCierre;
        public final boolean       cierreConvergio;
        public final long          duracionMs;

        ResultadoAOR(String disparador, List<Resultado> porCamion,
                     Set<String> palas, Set<String> destinos, int slots,
                     int indirectos, int iteraciones, boolean convergio,
                     long duracionMs) {
            this.camionDisparador            = disparador;
            this.porCamion                   = porCamion;
            this.palasAfectadas              = palas;
            this.destinosAfectados           = destinos;
            this.slotsLiberados              = slots;
            this.camionesAfectadosIndirectos = indirectos;
            this.iteracionesCierre           = iteraciones;
            this.cierreConvergio             = convergio;
            this.duracionMs                  = duracionMs;
        }

        public int totalCamionesLiberados() { return porCamion.size(); }
    }

    /** Tope de iteraciones del cierre transitivo (mismo criterio que la cascada). */
    public static final int MAX_ITERACIONES_CIERRE = 200;

    /**
     * REPARACION PARCIAL CANONICA (AOR). Libera al camion disparador y a todos
     * los que quedan afectados de forma indirecta por su retraso.
     *
     * @param camionDisparador camion que cruzo el umbral.
     * @param delta            retraso a propagar para calcular el cierre.
     * @param tiempoCorte      ancla de la perturbacion del disparador, ya
     *                         clampeada al reloj de simulacion por el llamador.
     * @param relojSim         reloj de simulacion, para que ningun corte de los
     *                         afectados indirectos caiga en el pasado.
     */
    public ResultadoAOR liberarAfectados(String camionDisparador, long delta,
                                          long tiempoCorte, long relojSim) {
        long t0 = System.currentTimeMillis();

        Cierre cierre = calcularAfectados(camionDisparador, delta, tiempoCorte, relojSim);

        List<Resultado> resultados       = new ArrayList<>();
        Set<String>     palasAfectadas   = new HashSet<>();
        Set<String>     destinosAfectados = new HashSet<>();
        int             slotsTotal       = 0;

        for (Map.Entry<String, Long> e : cierre.cortePorCamion.entrySet()) {
            // Corte en el reloj (el disparador): se libera segun lo que el
            // camion esta haciendo DE VERDAD. Corte futuro (afectado indirecto
            // por un choque mas adelante): se libera desde ese choque, como
            // pide el AOR; lo anterior al choque no esta afectado.
            Resultado r;
            if (e.getValue() <= relojSim) {
                r = liberarDesdeRealidad(e.getKey(), relojSim);
            } else {
                r = liberar(e.getKey(), alInicioDelCicloSiNoEmpezo(e.getKey(), e.getValue(), relojSim));
            }
            if (r.slotsLiberados == 0) continue;   // no tenia futuro que liberar
            resultados.add(r);
            palasAfectadas.addAll(r.palasAfectadas);
            destinosAfectados.addAll(r.destinosAfectados);
            slotsTotal += r.slotsLiberados;
        }

        int indirectos = Math.max(0, cierre.cortePorCamion.size() - 1);
        return new ResultadoAOR(camionDisparador, resultados,
                palasAfectadas, destinosAfectados, slotsTotal, indirectos,
                cierre.iteraciones, cierre.convergio,
                System.currentTimeMillis() - t0);
    }

    /**
     * Corte de un afectado indirecto cuyo choque esta en el FUTURO.
     *
     * La proteccion de ciclo de liberar() es fisica: protege el ciclo que el
     * camion ya empezo ("si ya partio, que no se le borre su ciclo"). Pero el
     * corte de un afectado indirecto (inicio del slot que choca - 1) suele caer
     * dentro de un ciclo que el camion todavia NO empezo, y liberar() protegia
     * ese ciclo entero: la tarea que chocaba quedaba en el plan sin renegociar
     * y, si era su ultimo ciclo, el camion no liberaba nada (32 de 59 afectados
     * en el primer disparo de grande_V3_PARTIAL).
     *
     * Si el ciclo que contiene el corte no empezo antes del reloj, el corte se
     * lleva justo antes de su viaje vacio y el ciclo se libera completo (sin
     * dejar un viaje vacio colgando). Si ya empezo, se deja el corte en el
     * reloj y liberar() protege ese ciclo como siempre.
     */
    private static long alInicioDelCicloSiNoEmpezo(String camion, long corte, long relojSim) {
        if (corte <= relojSim) return corte;
        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        if (sch == null) return corte;
        synchronized (sch) {
            List<Slot> slots = sch.getSlots();
            int idx = -1;
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                if (s.getHoraInicio() <= corte && corte < s.getHoraFin()) { idx = i; break; }
            }
            if (idx < 0) return corte;               // entre ciclos: nada que ajustar
            for (int i = idx; i >= 0; i--) {
                Slot s = slots.get(i);
                if (s.getOperacion() == Slot.enum_operacion.VIAJE_VACIO) {
                    return Math.max(s.getHoraInicio() - 1, relojSim);
                }
                if (i < idx && s.getOperacion() == Slot.enum_operacion.DESCARGA) break;
            }
        }
        return corte;
    }

    // ── Cierre transitivo de operaciones afectadas ──────────────────────────

    /** Copia mutable de un slot, para propagar el retraso sin tocar el plan. */
    private static class Copia {
        final String camion;
        final String recurso;   // null si no es recurso compartido
        long inicio, fin;
        Copia(String camion, String recurso, long inicio, long fin) {
            this.camion = camion; this.recurso = recurso;
            this.inicio = inicio; this.fin = fin;
        }
    }

    private static class Cierre {
        final Map<String, Long> cortePorCamion = new LinkedHashMap<>();
        int     iteraciones;
        boolean convergio;
    }

    /**
     * Calcula que camiones quedan afectados, directa e indirectamente, y desde
     * que instante lo esta cada uno.
     *
     * Se trabaja sobre COPIAS de los slots: se propaga el retraso hasta punto
     * fijo para descubrir a quien alcanza, pero el plan real no se toca. Recien
     * despues se liberan los afectados. Asi el calculo del conjunto afectado
     * queda separado de la accion sobre el plan.
     *
     * Criterio de solape y recursos compartidos: los mismos que usa la cascada
     * (CascadingRightShift.keyDeRecurso), de modo que los dos metodos vean
     * exactamente el mismo grafo de conflictos.
     */
    private Cierre calcularAfectados(String camionDisparador, long delta,
                                      long tiempoCorte, long relojSim) {
        Cierre cierre = new Cierre();
        cierre.cortePorCamion.put(camionDisparador, tiempoCorte);

        if (delta <= 0) { cierre.convergio = true; return cierre; }

        // 1. Copiar el plan completo.
        Map<String, List<Copia>> porCamion = new LinkedHashMap<>();
        for (Map.Entry<String, Schedule> e :
                RegistroSimulacion.getInstancia().getAgendasCamiones().entrySet()) {
            List<Copia> lista = new ArrayList<>();
            Schedule sch = e.getValue();
            synchronized (sch) {
                for (Slot s : sch.getSlots()) {
                    lista.add(new Copia(e.getKey(),
                            CascadingRightShift.keyDeRecurso(s),
                            s.getHoraInicio(), s.getHoraFin()));
                }
            }
            porCamion.put(e.getKey(), lista);
        }

        // 2. Retraso directo sobre el disparador.
        desplazar(porCamion.get(camionDisparador), tiempoCorte, delta);

        // 3. Punto fijo: propagar por los recursos compartidos.
        boolean huboCambios;
        do {
            huboCambios = false;
            cierre.iteraciones++;

            Map<String, Long> deltaPorCamion = new HashMap<>();
            Map<String, Long> cortePorCamion = new HashMap<>();

            for (Map.Entry<String, List<Copia>> eg : agruparPorRecurso(porCamion).entrySet()) {
                List<Copia> grupo = eg.getValue();
                Collections.sort(grupo, new Comparator<Copia>() {
                    @Override public int compare(Copia a, Copia b) {
                        return Long.compare(a.inicio, b.inicio);
                    }
                });
                long[] ini = new long[grupo.size()], fin = new long[grupo.size()];
                for (int i = 0; i < grupo.size(); i++) {
                    ini[i] = grupo.get(i).inicio;
                    fin[i] = grupo.get(i).fin;
                }
                for (int[] p : CascadingRightShift.paresEnConflicto(ini, fin,
                        CascadingRightShift.capacidadDeRecurso(eg.getKey()))) {
                    Copia a = grupo.get(p[0]), b = grupo.get(p[1]);
                    if (a.camion.equals(b.camion)) continue;
                    if (b.inicio >= a.fin) continue;          // sin solape
                    // Choque del pasado: b ya empezo y no se puede mover. Antes
                    // se seguia empujando el futuro de b en cada vuelta (200
                    // veces) sin resolverlo nunca.
                    if (b.inicio <= relojSim) continue;
                    long d = a.fin - b.inicio;
                    Long prev = deltaPorCamion.get(b.camion);
                    if (prev == null || d > prev) {
                        deltaPorCamion.put(b.camion, d);
                        cortePorCamion.put(b.camion, b.inicio - 1);
                    }
                }
            }

            for (Map.Entry<String, Long> e : deltaPorCamion.entrySet()) {
                String camion = e.getKey();
                long corte = cortePorCamion.get(camion);
                // El pasado es inmutable tambien para los afectados indirectos.
                corte = Math.max(corte, relojSim);
                desplazar(porCamion.get(camion), corte, e.getValue());
                // El corte que se registra es el mas TEMPRANO en que este camion
                // quedo afectado: desde ahi hay que liberarlo.
                Long previo = cierre.cortePorCamion.get(camion);
                if (previo == null || corte < previo) {
                    cierre.cortePorCamion.put(camion, corte);
                }
                huboCambios = true;
            }
        } while (huboCambios && cierre.iteraciones < MAX_ITERACIONES_CIERRE);

        cierre.convergio = cierre.iteraciones < MAX_ITERACIONES_CIERRE;
        return cierre;
    }

    private static void desplazar(List<Copia> slots, long corte, long delta) {
        if (slots == null) return;
        for (Copia c : slots) {
            if (c.inicio <= corte) continue;
            c.inicio += delta;
            c.fin    += delta;
        }
    }

    private static Map<String, List<Copia>> agruparPorRecurso(
            Map<String, List<Copia>> porCamion) {
        Map<String, List<Copia>> porRecurso = new HashMap<>();
        for (List<Copia> lista : porCamion.values()) {
            for (Copia c : lista) {
                if (c.recurso == null) continue;
                List<Copia> g = porRecurso.get(c.recurso);
                if (g == null) { g = new ArrayList<>(); porRecurso.put(c.recurso, g); }
                g.add(c);
            }
        }
        return porRecurso;
    }

    /**
     * Libera los slots futuros del camion respetando la tarea en curso Y el
     * ciclo en curso (ver PROTECCION DE CICLO en el javadoc de la clase).
     *
     * @param camion       camion que cruzo el umbral.
     * @param tiempoCorte  anchor de la perturbacion calculado en chequearUmbrales:
     *                     es (horaInicio del primer slot del camion con retraso
     *                     >= UMBRAL_NIVEL2) - 1. Slots con horaInicio > tiempoCorte
     *                     se eliminan; el slot que ABARCA tiempoCorte (horaInicio
     *                     <= tiempoCorte < horaFin) se preserva → respeta "tarea
     *                     en curso" naturalmente. Si esa tarea es CARGA/
     *                     VIAJE_CARGADO/DESCARGA, el corte EFECTIVO (ver
     *                     Resultado.corte) se extiende al fin de ese ciclo.
     */
    public Resultado liberar(String camion, long tiempoCorte) {
        long t0 = System.currentTimeMillis();

        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        if (sch == null) {
            return new Resultado(camion, tiempoCorte, 0,
                    new HashSet<>(), new HashSet<>(), false,
                    System.currentTimeMillis() - t0);
        }

        // 1. Detectar la tarea en curso en el corte ORIGINAL.
        boolean tareaEnCurso = false;
        Slot slotActivo = null;
        synchronized (sch) {
            for (Slot s : sch.getSlots()) {
                if (s.getHoraInicio() <= tiempoCorte && tiempoCorte < s.getHoraFin()) {
                    tareaEnCurso = true;
                    slotActivo = s;
                    break;
                }
            }
        }

        // 2. PROTECCION DE CICLO: si el camion ya inicio un ciclo (va vacio
        //    hacia la pala, carga, viaja cargado o descarga), ese ciclo no se
        //    borra: se extiende el corte hasta el fin de la DESCARGA que lo
        //    cierra y solo se libera lo posterior (reunion con el profesor del
        //    2-oct-2026: "si ya partio, que no se le borre su ciclo").
        //    Antes el VIAJE_VACIO no se protegia: se conservaba el viaje pero
        //    se borraba su carga, la ultima tarea del camion quedaba como un
        //    viaje vacio y Schedule.camionPuedeRealizarCicloPropuesto (que
        //    exige que la tarea anterior sea una descarga) le hacia rechazar
        //    todo ciclo nuevo hasta el fin del turno.
        //    Si no hay tarea en curso (el camion espera entre ciclos) no se
        //    extiende: su proximo ciclo todavia no empezo y se libera entero.
        long corteEfectivo = tiempoCorte;
        if (slotActivo != null) {
            synchronized (sch) {
                List<Slot> slots = sch.getSlots();
                int idx = slots.indexOf(slotActivo);
                for (int i = idx; i >= 0 && i < slots.size(); i++) {
                    Slot s = slots.get(i);
                    if (s.getOperacion() == Slot.enum_operacion.DESCARGA) {
                        corteEfectivo = Math.max(corteEfectivo, s.getHoraFin());
                        break;
                    }
                }
                // Si no se encontro DESCARGA hacia adelante (schedule corrupto
                // o borde de simulacion), corteEfectivo queda en tiempoCorte
                // (fallback defensivo, no bloquea la liberacion).
            }
        }

        // 3. Borrar slots con horaInicio > corteEfectivo y recolectar palas/destinos.
        return borrarPosteriores(camion, sch, corteEfectivo, tareaEnCurso, t0, -1L);
    }

    /** Borra del plan los slots con horaInicio > corte y arma el Resultado. */
    private static Resultado borrarPosteriores(String camion, Schedule sch, long corte,
                                               boolean tareaEnCurso, long t0,
                                               long disponibleDesde) {
        List<Slot>  slotsLiberados   = new ArrayList<>();
        Set<String> palasAfectadas   = new HashSet<>();
        Set<String> destinosAfectados = new HashSet<>();

        synchronized (sch) {
            Iterator<Slot> it = sch.getSlots().iterator();
            while (it.hasNext()) {
                Slot s = it.next();
                if (s.getHoraInicio() > corte) {
                    slotsLiberados.add(s);

                    if (s.getOperacion() == Slot.enum_operacion.CARGA
                            && s.getMaquina() != null && !s.getMaquina().trim().isEmpty()) {
                        palasAfectadas.add(s.getMaquina());
                    } else if (s.getOperacion() == Slot.enum_operacion.DESCARGA
                            && s.getUbicacion() != null) {
                        String d = s.getUbicacion().replace("#", "").trim();
                        if (!d.isEmpty()) destinosAfectados.add(d);
                    }
                    // Borramos del plan: el truck queda "libre" desde el corte.
                    it.remove();
                }
            }
        }

        long duracion = System.currentTimeMillis() - t0;
        return new Resultado(camion, corte, slotsLiberados.size(),
                palasAfectadas, destinosAfectados, tareaEnCurso, duracion, disponibleDesde);
    }

    /**
     * Hora en que el camion termina de verdad el ciclo que esta haciendo,
     * estimada solo con lo que se sabe AHORA (2026-10-07):
     *   - lo que ya hizo de la tarea en curso, con el clima y las fallas
     *     reales de ese tramo (es pasado);
     *   - lo que le falta, con el clima conocido: un clima que ya empezo
     *     sigue hasta su hora de termino y despues se va a velocidad normal
     *     (GestorEventos.duracionConClimaConocido, la misma regla que usan los
     *     viajes nuevos);
     *   - si esta detenido por una falla, retoma cuando la falla termina.
     * Antes se sumaban las duraciones planificadas: en grande_V6_COMPLETE la
     * regeneracion fue a las 4:22 con tormenta hasta las 4:45 y Truck19, en un
     * viaje de 20,7 min planificado sin tormenta, se estimaba libre a las 5:10
     * cuando en realidad el viaje duro 36,9 min. Lo que no se puede saber (la
     * cola en la pala, que depende de los otros camiones) no se estima.
     */
    private static long finEsperadoDelCiclo(String camion, List<Slot> slots, int i, int fin,
                                            RegistroSimulacion.SlotReal ult, long relojSim) {
        GestorEventos ge = GestorEventos.getInstancia();
        double p = RegistroSimulacion.getInstancia().getFactorVelocidadCamion(camion);
        if (p <= 0) p = 1.0;

        long t;
        Slot su = slots.get(i);
        if (ult.finReal <= relojSim) {
            t = Math.max(ult.finReal, relojSim);               // la termino; espera la siguiente
        } else {
            boolean viaje = esViaje(su);
            double hecho = ge.trabajoRealizado(camion, ult.inicioReal, relojSim, viaje, p);
            double falta = Math.max(0.0, CalculadorScheduleReal.trabajoDelSlot(su) - hecho);
            t = relojSim;
            if (ge.estaCamionEnFalla(camion, relojSim)) t = Math.max(t, ge.getFinFallaCamionActiva(camion, relojSim));
            t += viaje ? ge.duracionConClimaConocido(t, Math.round(falta / p), relojSim) : Math.round(falta);
        }
        if (ult.finReal <= relojSim && ge.estaCamionEnFalla(camion, relojSim)) {
            t = Math.max(t, ge.getFinFallaCamionActiva(camion, relojSim));
        }
        for (int j = i + 1; j <= fin; j++) {
            Slot s = slots.get(j);
            long trabajo = CalculadorScheduleReal.trabajoDelSlot(s);
            t += esViaje(s) ? ge.duracionConClimaConocido(t, Math.round(trabajo / p), relojSim) : trabajo;
        }
        return t;
    }

    private static boolean esViaje(Slot s) {
        return s.getOperacion() == Slot.enum_operacion.VIAJE_VACIO
            || s.getOperacion() == Slot.enum_operacion.VIAJE_CARGADO;
    }

    /**
     * LIBERACION ANCLADA A LA REALIDAD (2026-10-07).
     *
     * liberar() decide que ciclo esta en curso mirando el PLAN. Pero un camion
     * atrasado va, en la realidad, por detras de su plan: el plan lo cree un
     * ciclo mas adelante y cree que termina antes. Entonces se protegia un
     * ciclo equivocado y las tareas nuevas se planificaban desde el fin
     * planificado (viejo) del ciclo: el plan nuevo nacia con todo el atraso.
     * En grande_V5_COMPLETE, 60 camiones terminaban su ciclo con mediana
     * 41,5 min de atraso y su primera tarea nueva quedaba 41,7 min atrasada;
     * eso disparo la 2a y la 3a regeneracion.
     *
     * Aqui se usa lo que el camion esta haciendo DE VERDAD segun el simulador,
     * y solo lo que ya ocurrio (causal):
     *   1. La ultima tarea que ya empezo en la realidad.
     *   2. Se protege el ciclo real que la contiene, hasta su descarga
     *      ("si ya partio, que no se le borre su ciclo").
     *   3. Se borra todo lo que viene despues de ese ciclo, aunque en el plan
     *      figure en el pasado: en la realidad todavia no ocurrio.
     *   4. disponibleDesde = cuando termina de verdad ese ciclo, estimado con
     *      lo ya ocurrido mas las duraciones planificadas de lo que le falta
     *      (sin ver el futuro). Las tareas nuevas no pueden salir antes.
     */
    public Resultado liberarDesdeRealidad(String camion, long relojSim) {
        long t0 = System.currentTimeMillis();
        RegistroSimulacion reg = RegistroSimulacion.getInstancia();
        Schedule sch = reg.getAgendasCamiones().get(camion);
        List<RegistroSimulacion.SlotReal> reales = reg.getAgendasReales().get(camion);
        if (sch == null) return liberar(camion, relojSim);

        long corte;
        long disponible;
        boolean enCurso;
        synchronized (sch) {
            List<Slot> slots = new ArrayList<>(sch.getSlots());
            Collections.sort(slots, new Comparator<Slot>() {
                @Override public int compare(Slot a, Slot b) {
                    return Long.compare(a.getHoraInicio(), b.getHoraInicio());
                }
            });

            // 1. Ultima tarea que ya empezo en la realidad.
            RegistroSimulacion.SlotReal ult = null;
            if (reales != null) {
                for (RegistroSimulacion.SlotReal sr : reales) {
                    if (sr.inicioReal <= relojSim && (ult == null || sr.inicioReal >= ult.inicioReal)) ult = sr;
                }
            }
            int i = -1;
            if (ult != null) {
                for (int k = 0; k < slots.size(); k++) {
                    if (slots.get(k) == ult.slotPlanificado) { i = k; break; }
                }
            }
            if (i < 0) {
                // No empezo nada o no se ubico: como antes, desde el reloj.
                Resultado r = liberar(camion, relojSim);
                return new Resultado(r.camion, r.corte, r.slotsLiberados, r.palasAfectadas,
                        r.destinosAfectados, r.tareaEnCurso, r.duracionMs, relojSim);
            }

            // 2. Fin del ciclo real en curso: la descarga que lo cierra.
            boolean cicloTerminado = slots.get(i).getOperacion() == Slot.enum_operacion.DESCARGA
                    && ult.finReal <= relojSim;
            int fin = i;
            if (!cicloTerminado) {
                for (int j = i; j < slots.size(); j++) {
                    fin = j;
                    if (slots.get(j).getOperacion() == Slot.enum_operacion.DESCARGA) break;
                }
            }
            enCurso = !cicloTerminado;

            // 4. Cuando queda libre de verdad (causal).
            disponible = finEsperadoDelCiclo(camion, slots, i, fin, ult, relojSim);

            // 3. Corte equivalente a "borrar todo lo posterior al ciclo": la
            //    siguiente tarea puede empezar justo cuando termina la descarga,
            //    por eso el corte queda 1 ms antes de ella.
            corte = slots.get(fin).getHoraFin();
            if (fin + 1 < slots.size()) {
                corte = Math.min(corte, slots.get(fin + 1).getHoraInicio() - 1);
            }
        }
        return borrarPosteriores(camion, sch, corte, enCurso, t0, disponible);
    }
}
