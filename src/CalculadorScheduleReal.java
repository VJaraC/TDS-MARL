import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recalcula el schedule REAL de TODOS los camiones de forma coordinada.
 *
 * Por qué cross-camión: la cola en una pala averiada (o simplemente ocupada)
 * requiere conocer el estado global. Si Truck0 retiene la pala porque está
 * detenido por avería, Truck3 que viene a cargar a esa misma pala debe esperar.
 *
 * Algoritmo:
 *   1. Reunir todos los slots planificados de todos los camiones.
 *   2. Ordenarlos por hora de inicio planificada (esto preserva el orden
 *      interno de cada camión, porque dentro de un camión los slots ya son
 *      estrictamente crecientes).
 *   3. Procesar en ese orden, manteniendo dos mapas de estado:
 *        tiempoRealCamion[c]    = último fin_real conocido del camión c
 *        palasLibreDesde[punto] = arreglo con el instante en que se libera cada
 *                                 una de las N palas del punto (N servidores)
 *
 *   Para un slot del camión c:
 *      inicio_real = max(tiempoRealCamion[c], inicio_planificado)
 *      si es CARGA en un punto con N palas:
 *          el camión usa la pala que se libera MÁS TEMPRANO (cualquiera libre);
 *          inicio_real = max(inicio_real, instante en que se libera esa pala)
 *          si esa pala está en falla en inicio_real:
 *              inicio_real = fin de la falla
 *          (si las N palas están ocupadas, el camión espera → cola FIFO)
 *      fin_real = simularSlot(inicio_real, ...) // integra paso a paso
 *      registrar SlotReal(slot, inicio_real, fin_real)
 *      tiempoRealCamion[c] = fin_real
 *      si CARGA: la pala usada queda ocupada hasta fin_real
 */
public class CalculadorScheduleReal {

    private static final long PASO_INTEGRACION_MS = 30_000;

    public static Map<String, List<RegistroSimulacion.SlotReal>> calcularTodos(
            Map<String, Schedule> agendas,
            Map<String, Double> factoresCamion,
            GestorEventos gestor) {

        // ============================================================
        // MODO BASELINE (sin incertidumbre): la realidad ES el plan.
        // No hay nada que simular: copiamos cada slot planificado tal cual
        // como slot real (inicio real = inicio plan, fin real = fin plan).
        // Esto garantiza que el schedule real coincida EXACTAMENTE con el
        // planificado ("como reloj"), con retraso cero en todas las filas,
        // sin importar pequeñas imperfecciones del plan de la negociación
        // (p. ej. cargas que se solapan apenas en una misma pala).
        // ============================================================
        if (!ParametrosSimulador.INCERTIDUMBRES_ACTIVAS) {
            Map<String, List<RegistroSimulacion.SlotReal>> copiaPlan = new HashMap<>();
            for (Map.Entry<String, Schedule> e : agendas.entrySet()) {
                List<RegistroSimulacion.SlotReal> lista = new ArrayList<>();
                for (Slot s : e.getValue().getSlots()) {
                    lista.add(new RegistroSimulacion.SlotReal(
                            s, s.getHoraInicio(), s.getHoraFin()));
                }
                copiaPlan.put(e.getKey(), lista);
            }
            return copiaPlan;
        }

        // Estado por camión: tiempo real al que terminó su último slot procesado,
        // e índice del próximo slot pendiente en su Schedule.
        Map<String, Long> tiempoRealCamion = new HashMap<>();
        Map<String, Integer> indiceSlotPendiente = new HashMap<>();
        // Estado por PALA INDIVIDUAL: a cada camión la negociación le asignó una
        // pala concreta (queda en slot.getMaquina()). El camión va a ESA pala: si
        // está libre cuando llega, empieza; si está ocupada, hace cola para ESA
        // pala (cola por pala). Guardamos, por nombre de pala, el instante en que
        // se libera. Fallback (slots sin pala asignada): pool del punto.
        Map<String, Long> palaLibreDesde = new HashMap<>();
        Map<String, long[]> palasLibreDesdePunto = new HashMap<>();

        // Estado por DESTINO DE DESCARGA: cada destino (chancador o botadero)
        // admite hasta `maximumDischarge` descargas en paralelo (capacidad del
        // OWL). Guardamos, por cada destino, un arreglo con el instante en que
        // se libera cada uno de sus "puestos de descarga". Es exactamente el
        // mismo modelo de N servidores en paralelo que usamos para las palas:
        //   - Chancador CS03 (capacidad 1): el 2º camión que llega hace cola.
        //   - Botaderos (capacidad 99): en la práctica nunca hay cola.
        Map<String, long[]> destinosLibreDesde = new HashMap<>();

        // Nombres de las palas que operan en cada punto (del OWL, vía registro).
        // Se usan para consultar averías por pala individual: si PA08 se avería,
        // solo esa pala del punto queda fuera de servicio, las demás siguen.
        Map<String, String[]> nombresPalasPorUbic = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e :
                RegistroSimulacion.getInstancia().getPalasPorUbicacion().entrySet()) {
            nombresPalasPorUbic.put(e.getKey(), e.getValue().toArray(new String[0]));
        }

        // Resultado
        Map<String, List<RegistroSimulacion.SlotReal>> resultado = new HashMap<>();

        for (Map.Entry<String, Schedule> e : agendas.entrySet()) {
            String camion = e.getKey();
            tiempoRealCamion.put(camion, 0L);
            indiceSlotPendiente.put(camion, 0);
            resultado.put(camion, new ArrayList<>());
        }

        // Loop principal: en cada iteración procesamos el SIGUIENTE slot de un camión
        // que pueda empezar MÁS TEMPRANO en el tiempo real. Esto produce el FIFO
        // natural en las palas: si Truck42 llega físicamente antes que Truck12,
        // el slot CARGA de Truck42 se procesa primero porque su inicio real es menor,
        // sin importar quién la tenía planificada para antes.
        //
        // Cuando varios slots empatan en inicio (típico: varios camiones esperando
        // que se libere una pala en el mismo punto), desempatamos eligiendo el que
        // LLEGÓ ANTES — es decir, el de menor tiempoRealCamion. Eso produce el FIFO
        // físico correcto. Sin este desempate, el HashMap entrega los camiones en
        // orden no determinista y la cola se "saltea" arbitrariamente.
        while (true) {
            String mejorCamion = null;
            long mejorInicio = Long.MAX_VALUE;
            long mejorTiempoLlegada = Long.MAX_VALUE;
            Slot mejorSlot = null;

            for (Map.Entry<String, Schedule> e : agendas.entrySet()) {
                String camion = e.getKey();
                int idx = indiceSlotPendiente.get(camion);
                List<Slot> slots = e.getValue().getSlots();
                if (idx >= slots.size()) continue; // este camión ya terminó

                Slot slot = slots.get(idx);
                long inicio = calcularInicioRealSlot(slot, idx, camion,
                        tiempoRealCamion, palaLibreDesde, palasLibreDesdePunto, nombresPalasPorUbic,
                        destinosLibreDesde, gestor);
                long tiempoLlegada = tiempoRealCamion.get(camion);

                boolean esMejor = inicio < mejorInicio
                        || (inicio == mejorInicio && tiempoLlegada < mejorTiempoLlegada);
                if (esMejor) {
                    mejorInicio = inicio;
                    mejorTiempoLlegada = tiempoLlegada;
                    mejorCamion = camion;
                    mejorSlot = slot;
                }
            }

            if (mejorCamion == null) break; // no quedan slots pendientes

            // Procesar el slot elegido
            long durPlan = trabajoDelSlot(mejorSlot);
            double factor = factoresCamion.getOrDefault(mejorCamion, 1.0);
            long finReal = simularSlot(mejorInicio, durPlan, mejorCamion, factor,
                                       mejorSlot.getOperacion(), gestor);

            resultado.get(mejorCamion).add(
                    new RegistroSimulacion.SlotReal(mejorSlot, mejorInicio, finReal));

            // Actualizar la pala usada ANTES de tocar tiempoRealCamion, para
            // poder recomputar el mismo inicioBase que usó el cálculo y así
            // elegir exactamente la misma pala que se asignó.
            if (mejorSlot.getOperacion() == Slot.enum_operacion.CARGA) {
                // Cola POR PALA INDIVIDUAL: la pala asignada queda ocupada hasta
                // finReal. El siguiente camión asignado a ESA pala hará cola.
                String palaAsignada = mejorSlot.getMaquina();
                if (palaAsignada != null && !palaAsignada.trim().isEmpty()) {
                    palaLibreDesde.put(palaAsignada, finReal);
                } else {
                    String ubic = mejorSlot.getUbicacion();
                    long[] palas = obtenerPalasDelPunto(ubic, palasLibreDesdePunto, nombresPalasPorUbic);
                    String[] nombres = nombresPalasPorUbic.get(ubic);
                    long inicioBase = tiempoRealCamion.getOrDefault(mejorCamion, 0L);
                    if (indiceSlotPendiente.get(mejorCamion) == 0) {
                        inicioBase = Math.max(inicioBase, mejorSlot.getHoraInicio());
                    }
                    int idxPala = indicePalaMasTemprana(inicioBase, palas, nombres, gestor);
                    palas[idxPala] = finReal;
                }
            } else if (mejorSlot.getOperacion() == Slot.enum_operacion.DESCARGA) {
                // Análogo a la carga, pero sobre el punto de DESCARGA: marcamos
                // ocupado hasta finReal el puesto de descarga que se asignó, para
                // que el siguiente camión que llegue al mismo destino lo encuentre
                // ocupado y, si no hay otro puesto libre, haga cola.
                String dest = mejorSlot.getUbicacion();
                long[] puestos = obtenerPuestosDelDestino(dest, destinosLibreDesde);
                long inicioBase = tiempoRealCamion.getOrDefault(mejorCamion, 0L);
                if (indiceSlotPendiente.get(mejorCamion) == 0) {
                    inicioBase = Math.max(inicioBase, mejorSlot.getHoraInicio());
                }
                int idxPuesto = indicePuestoMasTemprano(inicioBase, puestos, dest, gestor);
                puestos[idxPuesto] = finReal; // ese puesto queda ocupado hasta finReal
            }

            tiempoRealCamion.put(mejorCamion, finReal);
            indiceSlotPendiente.put(mejorCamion, indiceSlotPendiente.get(mejorCamion) + 1);
        }

        return resultado;
    }

    /**
     * Calcula a qué hora real puede comenzar un slot, sin procesarlo todavía.
     *
     * Regla central: el camión RESPETA SU PLAN como referencia. El inicio real
     * de cada slot nunca es anterior a su hora planificada:
     *
     *     inicio = max(fin real del slot anterior,  hora planificada del slot)
     *
     * Esto preserva los "tiempos muertos" (idle) que la negociación colocó a
     * propósito entre actividades. Consecuencias:
     *
     *   - SIN incertidumbre: no se usa este método. El modo baseline copia el
     *     plan literal (ver inicio de calcularTodos), así que real == plan exacto.
     *
     *   - CON incertidumbre (única vía que usa este método): el camión opera de
     *     forma "greedy", haciendo cada actividad apenas puede, sin esperar horas
     *     planificadas. Solo el PRIMER slot respeta su hora de inicio (arranque
     *     del turno). Consecuencias:
     *       · Si el camión llega a un punto y hay una pala libre, CARGA YA mismo
     *         (no espera su hora planificada). Esto es el comportamiento físico
     *         que se espera: palas libres ⇒ se usa de inmediato.
     *       · Si todas las palas del punto están ocupadas, espera a que se libere
     *         una → cola FIFO (el desempate del bucle ordena por hora de llegada).
     */
    private static long calcularInicioRealSlot(Slot slot, int idxEnCamion, String camion,
                                               Map<String, Long> tiempoRealCamion,
                                               Map<String, Long> palaLibreDesde,
                                               Map<String, long[]> palasLibreDesdePunto,
                                               Map<String, String[]> nombresPalasPorUbic,
                                               Map<String, long[]> destinosLibreDesde,
                                               GestorEventos gestor) {
        long inicio = tiempoRealCamion.getOrDefault(camion, 0L);
        // Solo el primer slot respeta su hora planificada (inicio del turno).
        // Del segundo en adelante, el camión es greedy: actúa apenas puede.
        if (idxEnCamion == 0) {
            inicio = Math.max(inicio, slot.getHoraInicio());
        }
        if (slot.getOperacion() == Slot.enum_operacion.CARGA) {
            String palaAsignada = slot.getMaquina();
            if (palaAsignada != null && !palaAsignada.trim().isEmpty()) {
                // El camión va a SU pala: si está libre cuando llega (inicio),
                // empieza nomás; si está ocupada, espera a que se libere (cola
                // por pala). disponibilidadPala además salta tramos de avería.
                long libre = palaLibreDesde.getOrDefault(palaAsignada, 0L);
                inicio = disponibilidadPala(inicio, libre, palaAsignada, gestor);
            } else {
                // Fallback: pool del punto (slots sin pala asignada).
                String ubic = slot.getUbicacion();
                long[] palas = obtenerPalasDelPunto(ubic, palasLibreDesdePunto, nombresPalasPorUbic);
                String[] nombres = nombresPalasPorUbic.get(ubic);
                int idxPala = indicePalaMasTemprana(inicio, palas, nombres, gestor);
                String nombre = (nombres != null && idxPala < nombres.length) ? nombres[idxPala] : null;
                inicio = disponibilidadPala(inicio, palas[idxPala], nombre, gestor);
            }
        } else if (slot.getOperacion() == Slot.enum_operacion.DESCARGA) {
            // Simétrico a la carga: el camión puede empezar a descargar en cuanto
            // haya UN puesto de descarga libre en el destino. Si todos los puestos
            // están ocupados (caso típico del chancador, capacidad 1), espera al
            // que se libere primero → cola FIFO. Además, si el destino está en
            // falla, espera a que se repare.
            String dest = slot.getUbicacion();
            long[] puestos = obtenerPuestosDelDestino(dest, destinosLibreDesde);
            int idxPuesto = indicePuestoMasTemprano(inicio, puestos, dest, gestor);
            inicio = disponibilidadDestino(inicio, puestos[idxPuesto], dest, gestor);
        }
        return inicio;
    }

    /**
     * Devuelve (creándolo si hace falta) el arreglo de tiempos de liberación de
     * las palas que operan en un punto. El tamaño es la cantidad de palas que el
     * OWL ubicó en ese punto (N servidores en paralelo). Si por algún motivo el
     * punto no tiene palas registradas, se asume capacidad 1.
     */
    private static long[] obtenerPalasDelPunto(String ubic,
                                               Map<String, long[]> palasLibreDesde,
                                               Map<String, String[]> nombresPalasPorUbic) {
        long[] palas = palasLibreDesde.get(ubic);
        if (palas == null) {
            String[] nombres = nombresPalasPorUbic.get(ubic);
            int n = (nombres != null && nombres.length > 0) ? nombres.length : 1;
            palas = new long[n]; // todas libres desde t=0
            palasLibreDesde.put(ubic, palas);
        }
        return palas;
    }

    /**
     * Índice de la pala del punto que estará disponible más temprano, a partir de
     * inicioBase, considerando averías por pala individual. Es la pala que se le
     * asignará al camión.
     */
    private static int indicePalaMasTemprana(long inicioBase, long[] palas,
                                             String[] nombres, GestorEventos gestor) {
        int mejorIdx = 0;
        long mejorT = Long.MAX_VALUE;
        for (int i = 0; i < palas.length; i++) {
            String nombre = (nombres != null && i < nombres.length) ? nombres[i] : null;
            long t = disponibilidadPala(inicioBase, palas[i], nombre, gestor);
            if (t < mejorT) {
                mejorT = t;
                mejorIdx = i;
            }
        }
        return mejorIdx;
    }

    /**
     * Momento en que una pala concreta puede empezar a atender: no antes de
     * inicioBase, no antes de que se libere, y saltando sus tramos de avería.
     */
    private static long disponibilidadPala(long inicioBase, long tiempoLiberacion,
                                           String nombrePala, GestorEventos gestor) {
        long t = Math.max(inicioBase, tiempoLiberacion);
        if (nombrePala != null) {
            int safety = 0;
            while (gestor.estaPalaEnFalla(nombrePala, t) && safety++ < 100) {
                t = gestor.getFinFallaPalaActiva(nombrePala, t);
            }
        }
        return t;
    }

    // ---- Helpers para puntos de DESCARGA (chancadores y botaderos) ----
    // Son el espejo de los helpers de palas, con dos diferencias:
    //   1. La capacidad (cantidad de servidores) viene del maximumDischarge del
    //      OWL (RegistroSimulacion.getCapacidadDestino), no de contar máquinas.
    //   2. La avería afecta al DESTINO COMPLETO (un chancador que se rompe deja
    //      fuera de servicio todos sus puestos), no a un servidor con nombre.

    /**
     * Devuelve (creándolo si hace falta) el arreglo de tiempos de liberación de
     * los puestos de descarga de un destino. El tamaño es su capacidad de
     * descarga simultánea (maximumDischarge del OWL).
     */
    private static long[] obtenerPuestosDelDestino(String destino,
                                                   Map<String, long[]> destinosLibreDesde) {
        long[] puestos = destinosLibreDesde.get(destino);
        if (puestos == null) {
            int n = RegistroSimulacion.getInstancia().getCapacidadDestino(destino);
            if (n < 1) n = 1;
            puestos = new long[n]; // todos libres desde t=0
            destinosLibreDesde.put(destino, puestos);
        }
        return puestos;
    }

    /**
     * Índice del puesto de descarga del destino que estará disponible más
     * temprano a partir de inicioBase, considerando la avería del destino.
     */
    private static int indicePuestoMasTemprano(long inicioBase, long[] puestos,
                                               String destino, GestorEventos gestor) {
        int mejorIdx = 0;
        long mejorT = Long.MAX_VALUE;
        for (int i = 0; i < puestos.length; i++) {
            long t = disponibilidadDestino(inicioBase, puestos[i], destino, gestor);
            if (t < mejorT) {
                mejorT = t;
                mejorIdx = i;
            }
        }
        return mejorIdx;
    }

    /**
     * Momento en que un puesto de descarga concreto puede empezar a atender: no
     * antes de inicioBase, no antes de que se libere, y saltando los tramos en
     * que el destino completo está averiado (chancador fuera de servicio).
     */
    private static long disponibilidadDestino(long inicioBase, long tiempoLiberacion,
                                              String destino, GestorEventos gestor) {
        long t = Math.max(inicioBase, tiempoLiberacion);
        if (destino != null) {
            int safety = 0;
            while (gestor.estaChancadorEnFalla(destino, t) && safety++ < 100) {
                t = gestor.getFinFallaChancadorActiva(destino, t);
            }
        }
        return t;
    }

    /**
     * Integra paso a paso la "deuda" de un slot. La operación termina cuando
     * la deuda acumulada iguala a la duración planificada.
     *
     * Factor instantáneo:
     *   - Si el camión está en falla en t → 0  (no avanza, sea lo que sea que haga)
     *   - Si es viaje                     → factorCamion × factorClima(t)
     *   - Si es CARGA o DESCARGA          → 1.0 (no se afecta por clima)
     *
     * Optimización: si el factor es 0 (camión en falla), saltamos directamente
     * al fin de la falla en lugar de iterar paso a paso. Esto evita ciclos
     * innecesarios cuando la falla es larga.
     */
    /**
     * Trabajo de un slot expresado a velocidad nominal: lo que simularSlot debe
     * "consumir" aplicando el factor del camion y el clima minuto a minuto.
     *
     * La duracion planificada de un viaje ya viene dividida por el factor con
     * que se negocio (factor propio x clima de ese momento, ver
     * RegistroSimulacion.getFactorPlan). Si se integrara tal cual, el clima y
     * el factor propio se aplicarian dos veces. Multiplicando por el factor del
     * plan se vuelve a la duracion nominal del recorrido. Las operaciones que
     * no son viajes no dependen de la velocidad y quedan igual.
     */
    static long trabajoDelSlot(Slot s) {   // package: PartialRepair estima con el mismo criterio
        long dur = s.getHoraFin() - s.getHoraInicio();
        if (s.getOperacion() == Slot.enum_operacion.VIAJE_VACIO
                || s.getOperacion() == Slot.enum_operacion.VIAJE_CARGADO) {
            dur = Math.round(dur * RegistroSimulacion.getInstancia().getFactorPlan(s));
        }
        return dur;
    }

    private static long simularSlot(long inicio, long duracionPlanificada,
                                    String camion, double factorCamion,
                                    Slot.enum_operacion op, GestorEventos gestor) {
        if (duracionPlanificada <= 0) return inicio;

        boolean esViaje = (op == Slot.enum_operacion.VIAJE_VACIO
                       || op == Slot.enum_operacion.VIAJE_CARGADO);

        long t = inicio;
        double deuda = 0.0;
        long tMax = inicio + duracionPlanificada * 100L; // tope de seguridad

        while (deuda < duracionPlanificada && t < tMax) {
            // Si está en falla, saltar al fin de la falla (no avanza la deuda)
            if (gestor.estaCamionEnFalla(camion, t)) {
                long finFalla = gestor.getFinFallaCamionActiva(camion, t);
                t = (finFalla > t) ? finFalla : t + PASO_INTEGRACION_MS;
                continue;
            }

            double factor = esViaje ? (factorCamion * gestor.getFactorClimaEn(t)) : 1.0;
            long dt = PASO_INTEGRACION_MS;
            if (deuda + dt * factor > duracionPlanificada) {
                dt = (long) Math.ceil((duracionPlanificada - deuda) / factor);
                if (dt < 1) dt = 1;
            }
            deuda += dt * factor;
            t += dt;
        }
        return t;
    }

    /**
     * Calcula el progreso visual [0,1] del camión dentro de un slot real en
     * el instante tActual, usando la misma lógica de deuda paso a paso.
     *
     * Esto es lo que permite que el camión se vea PARADO durante una falla:
     * mientras está en falla, la deuda no avanza, así que el progreso
     * tampoco. El camión queda inmóvil en pantalla.
     */
    public static double calcularProgresoVisual(RegistroSimulacion.SlotReal sr,
                                                String camion,
                                                double factorCamion,
                                                long tActual,
                                                GestorEventos gestor) {
        Slot slot = sr.slotPlanificado;
        long duracionPlanificada = trabajoDelSlot(slot);
        if (duracionPlanificada <= 0) return 1.0;
        if (tActual <= sr.inicioReal) return 0.0;
        if (tActual >= sr.finReal) return 1.0;

        boolean esViaje = (slot.getOperacion() == Slot.enum_operacion.VIAJE_VACIO
                        || slot.getOperacion() == Slot.enum_operacion.VIAJE_CARGADO);
        long t = sr.inicioReal;
        double deuda = 0.0;

        while (t < tActual && deuda < duracionPlanificada) {
            if (gestor.estaCamionEnFalla(camion, t)) {
                long finFalla = gestor.getFinFallaCamionActiva(camion, t);
                long siguienteT = Math.min(finFalla, tActual);
                t = (siguienteT > t) ? siguienteT : t + PASO_INTEGRACION_MS;
                continue;
            }
            double factor = esViaje ? (factorCamion * gestor.getFactorClimaEn(t)) : 1.0;
            long dt = Math.min(PASO_INTEGRACION_MS, tActual - t);
            if (deuda + dt * factor > duracionPlanificada) {
                dt = (long) Math.ceil((duracionPlanificada - deuda) / factor);
                if (dt < 1) dt = 1;
            }
            deuda += dt * factor;
            t += dt;
        }
        return Math.min(1.0, deuda / duracionPlanificada);
    }
}