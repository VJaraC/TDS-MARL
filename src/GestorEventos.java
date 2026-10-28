import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton que mantiene la lista de eventos de la simulación y responde
 * consultas sobre el estado en un instante dado.
 *
 * En esta capa se cargan 3 eventos de CLIMA predeterminados al iniciar (si las
 * incertidumbres están activas). Las fallas (camión/pala/chancador) están
 * soportadas por la API pero NO se cargan todavía: quedan para la próxima capa.
 *
 * El factor de velocidad efectivo de un camión X en el tiempo t es:
 *   factor_propio_X  ×  getFactorClimaEn(t)  ×  (estaCamionEnFalla(X,t) ? 0 : 1)
 */
public class GestorEventos {

    private static GestorEventos instancia;

    private final CopyOnWriteArrayList<EventoSimulacion> eventos;
    private volatile long version = 0;

    private GestorEventos() {
        this.eventos = new CopyOnWriteArrayList<>();
        cargarEventosPorDefecto();
    }

    public static synchronized GestorEventos getInstancia() {
        if (instancia == null) instancia = new GestorEventos();
        return instancia;
    }

    private void cargarEventosPorDefecto() {
        // Sin incertidumbre no se carga ningún evento: el real == plan.
        if (!ParametrosSimulador.INCERTIDUMBRES_ACTIVAS) return;

        // ============================================================
        // 3 CLIMAS PREDETERMINADOS (perturbaciones globales).
        // Cada uno reduce la velocidad de TODOS los camiones mientras dura.
        // Generan retrasos naturales y, al desincronizar las llegadas,
        // hacen aparecer colas reales en palas y destinos.
        //
        //   Lluvia ligera : a los 60 min, dura 30 min, velocidad x0.80
        //   Neblina densa : a los 150 min, dura 20 min, velocidad x0.60
        //   Tormenta      : a los 240 min, dura 45 min, velocidad x0.40
        // ============================================================
        eventos.add(new EventoSimulacion(EventoSimulacion.TipoEvento.LLUVIA_LIGERA,
                60L * 60 * 1000, 30L * 60 * 1000, 0.80));
        eventos.add(new EventoSimulacion(EventoSimulacion.TipoEvento.NEBLINA,
                150L * 60 * 1000, 20L * 60 * 1000, 0.60));
        eventos.add(new EventoSimulacion(EventoSimulacion.TipoEvento.TORMENTA,
                240L * 60 * 1000, 45L * 60 * 1000, 0.40));

        ordenar();
    }

    public void agregarEvento(EventoSimulacion e) { eventos.add(e); ordenar(); version++; }
    public void eliminarEvento(EventoSimulacion e) { eventos.remove(e); version++; }
    public void limpiarEventos() { eventos.clear(); version++; }

    private void ordenar() {
        List<EventoSimulacion> copia = new ArrayList<>(eventos);
        Collections.sort(copia, Comparator.comparingLong(EventoSimulacion::getHoraInicioMs));
        eventos.clear();
        eventos.addAll(copia);
    }

    public List<EventoSimulacion> getEventos() { return new ArrayList<>(eventos); }
    public long getVersion() { return version; }

    // ----------------- CLIMA -----------------

    /** Factor combinado de todos los climas activos en t. 1.0 si no hay clima. */
    public double getFactorClimaEn(long t) {
        double f = 1.0;
        for (EventoSimulacion e : eventos) {
            if (!e.getTipo().esFalla() && e.estaActivoEn(t)) f *= e.getFactorVelocidad();
        }
        return Math.max(f, 0.05);
    }

    // ----------------- CLIMA CONOCIDO AL PLANIFICAR -----------------
    //
    // Lo que sabe el despachador cuando negocia a la hora `reloj`: los climas
    // que YA empezaron y siguen activos, cada uno con su hora de termino. Los
    // que todavia no empiezan no se conocen (no se ve el futuro).
    //
    // Antes se planificaba todo el viaje con el clima del momento: un viaje
    // negociado a las 4:22, en plena tormenta (x0,40 hasta las 4:45), se
    // planificaba 2,5 veces mas largo aunque saliera a las 5:30. Ahora el
    // camion va lento solo mientras dura el clima y a velocidad normal despues.
    // Cambio del 2026-10-05; ajusta la regla de "velocidad del momento" de la
    // reunion del 3-jul-2026, pendiente de confirmar con el profesor.
    //
    // "trabajo" = duracion del viaje sin clima (a la velocidad propia del
    // camion). Si al reloj no hay clima activo todo da igual que antes.

    /** Factor de clima en t segun lo conocido a la hora reloj. */
    public double getFactorClimaConocido(long t, long reloj) {
        double f = 1.0;
        for (EventoSimulacion e : eventos) {
            if (e.getTipo().esFalla() || !e.estaActivoEn(reloj)) continue;
            if (t >= e.getHoraInicioMs() && t < e.getHoraFinMs()) f *= e.getFactorVelocidad();
        }
        return Math.max(f, 0.05);
    }

    /** Primer instante > t en que cambia el clima conocido; MAX_VALUE si no cambia mas. */
    private long proximoCambioConocido(long t, long reloj) {
        long sig = Long.MAX_VALUE;
        for (EventoSimulacion e : eventos) {
            if (e.getTipo().esFalla() || !e.estaActivoEn(reloj)) continue;
            if (e.getHoraInicioMs() > t) sig = Math.min(sig, e.getHoraInicioMs());
            if (e.getHoraFinMs()    > t) sig = Math.min(sig, e.getHoraFinMs());
        }
        return sig;
    }

    /** Ultimo instante < t en que cambia el clima conocido; MIN_VALUE si no hay. */
    private long anteriorCambioConocido(long t, long reloj) {
        long ant = Long.MIN_VALUE;
        for (EventoSimulacion e : eventos) {
            if (e.getTipo().esFalla() || !e.estaActivoEn(reloj)) continue;
            if (e.getHoraInicioMs() < t) ant = Math.max(ant, e.getHoraInicioMs());
            if (e.getHoraFinMs()    < t) ant = Math.max(ant, e.getHoraFinMs());
        }
        return ant;
    }

    /** Cuanto dura un viaje que sale en `inicio` y necesita `trabajo` ms sin clima. */
    public long duracionConClimaConocido(long inicio, long trabajo, long reloj) {
        if (trabajo <= 0) return 0L;
        double resta = trabajo;
        long t = inicio;
        while (true) {
            double f = getFactorClimaConocido(t, reloj);
            long sig = proximoCambioConocido(t, reloj);
            if (sig == Long.MAX_VALUE || (sig - t) * f >= resta) {
                return t + (long) Math.ceil(resta / f) - inicio;
            }
            resta -= (sig - t) * f;
            t = sig;
        }
    }

    /** A que hora salir para llegar justo a `llegada` con `trabajo` ms sin clima. */
    public long salidaParaLlegarConClimaConocido(long llegada, long trabajo, long reloj) {
        if (trabajo <= 0) return llegada;
        double resta = trabajo;
        long t = llegada;
        while (true) {
            double f = getFactorClimaConocido(t - 1, reloj);
            long ant = anteriorCambioConocido(t, reloj);
            if (ant == Long.MIN_VALUE || (t - ant) * f >= resta) {
                return t - (long) Math.ceil(resta / f);
            }
            resta -= (t - ant) * f;
            t = ant;
        }
    }

    /**
     * Trabajo que un camion YA HIZO entre a y b (b = ahora), con el clima real
     * de ese intervalo y sus fallas (detenido = 0). Es el pasado, asi que se
     * conoce. Para carga y descarga (esViaje = false) el clima no influye.
     * Mismo criterio que CalculadorScheduleReal.simularSlot (2026-10-07).
     */
    public double trabajoRealizado(String camion, long a, long b, boolean esViaje, double factorPropio) {
        if (b <= a) return 0.0;
        java.util.TreeSet<Long> cortes = new java.util.TreeSet<>();
        cortes.add(a); cortes.add(b);
        for (EventoSimulacion e : eventos) {
            if (e.getHoraInicioMs() > a && e.getHoraInicioMs() < b) cortes.add(e.getHoraInicioMs());
            if (e.getHoraFinMs()    > a && e.getHoraFinMs()    < b) cortes.add(e.getHoraFinMs());
        }
        double w = 0.0;
        Long previo = null;
        for (long c : cortes) {
            if (previo != null) {
                long medio = (previo + c) / 2;
                double f = estaCamionEnFalla(camion, medio) ? 0.0
                        : (esViaje ? factorPropio * getFactorClimaEn(medio) : 1.0);
                w += (c - previo) * f;
            }
            previo = c;
        }
        return w;
    }

    /** Trabajo sin clima que cabe en [inicio, fin] (inversa de duracionConClimaConocido). */
    public double trabajoConClimaConocido(long inicio, long fin, long reloj) {
        double w = 0.0;
        long t = inicio;
        while (t < fin) {
            double f = getFactorClimaConocido(t, reloj);
            long sig = Math.min(fin, proximoCambioConocido(t, reloj));
            w += (sig - t) * f;
            t = sig;
        }
        return w;
    }

    // ----------------- FALLAS DE CAMIÓN -----------------

    public boolean estaCamionEnFalla(String idCamion, long t) {
        for (EventoSimulacion e : eventos) {
            if (e.getTipo() == EventoSimulacion.TipoEvento.FALLA_CAMION
                    && idCamion.equals(e.getTargetId()) && e.estaActivoEn(t)) return true;
        }
        return false;
    }

    public long getFinFallaCamionActiva(String idCamion, long t) {
        for (EventoSimulacion e : eventos) {
            if (e.getTipo() == EventoSimulacion.TipoEvento.FALLA_CAMION
                    && idCamion.equals(e.getTargetId()) && e.estaActivoEn(t)) return e.getHoraFinMs();
        }
        return t;
    }

    // ----------------- FALLAS DE PALA -----------------

    public boolean estaPalaEnFalla(String idPala, long t) {
        for (EventoSimulacion e : eventos) {
            if (e.getTipo() == EventoSimulacion.TipoEvento.FALLA_PALA
                    && idPala.equals(e.getTargetId()) && e.estaActivoEn(t)) return true;
        }
        return false;
    }

    public long getFinFallaPalaActiva(String idPala, long t) {
        for (EventoSimulacion e : eventos) {
            if (e.getTipo() == EventoSimulacion.TipoEvento.FALLA_PALA
                    && idPala.equals(e.getTargetId()) && e.estaActivoEn(t)) return e.getHoraFinMs();
        }
        return t;
    }

    // ----------------- FALLAS DE CHANCADOR / DESTINO -----------------

    public boolean estaChancadorEnFalla(String destino, long t) {
        for (EventoSimulacion e : eventos) {
            if (e.getTipo() == EventoSimulacion.TipoEvento.FALLA_CHANCADOR
                    && destino.equals(e.getTargetId()) && e.estaActivoEn(t)) return true;
        }
        return false;
    }

    public long getFinFallaChancadorActiva(String destino, long t) {
        for (EventoSimulacion e : eventos) {
            if (e.getTipo() == EventoSimulacion.TipoEvento.FALLA_CHANCADOR
                    && destino.equals(e.getTargetId()) && e.estaActivoEn(t)) return e.getHoraFinMs();
        }
        return t;
    }

    // ----------------- UI -----------------

    /** Evento activo "más severo" en t (para el banner). Fallas priman sobre clima. */
    public EventoSimulacion getEventoActivo(long t) {
        EventoSimulacion fallaActiva = null, peorClima = null;
        for (EventoSimulacion e : eventos) {
            if (!e.estaActivoEn(t)) continue;
            if (e.getTipo().esFalla()) {
                if (fallaActiva == null) fallaActiva = e;
            } else if (peorClima == null || e.getFactorVelocidad() < peorClima.getFactorVelocidad()) {
                peorClima = e;
            }
        }
        return (fallaActiva != null) ? fallaActiva : peorClima;
    }

    public List<EventoSimulacion> getFallasActivas(long t) {
        List<EventoSimulacion> res = new ArrayList<>();
        for (EventoSimulacion e : eventos)
            if (e.getTipo().esFalla() && e.estaActivoEn(t)) res.add(e);
        return res;
    }
}