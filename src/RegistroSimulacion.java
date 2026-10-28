import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton del MÓDULO SIMULADOR. Mantiene en memoria:
 *   - Los schedules planificados de cada CAMIÓN (los va llenando el LectorCSVSchedules).
 *   - Las ubicaciones iniciales de cada camión (las llena el InicializadorSimulador
 *     leyendo el OWL).
 *   - La ubicación física de cada PALA y la cantidad de palas por punto.
 *   - La capacidad de descarga de cada DESTINO (chancador/wastedump/stockpile).
 *   - Las velocidades nominales de cada camión (de Owl, para mostrar en dashboard).
 *   - Factores de velocidad propios de cada camión (para futuras incertidumbres).
 *
 * En la CAPA 1 el "schedule real" coincide exactamente con el plan, así que
 * la lista agendasReales se mantiene vacía (la ventana dibuja directo del plan).
 * En la Capa 3 se llenará vía CalculadorScheduleReal.
 *
 * Esta clase NO toca el código de los agentes JADE: ellos escriben sus
 * schedules al CSV (vía CsvSchedules) y este singleton los recoge desde ahí.
 */
public class RegistroSimulacion {

    private static RegistroSimulacion instancia;

    // Schedule PLANIFICADO por camión (el LectorCSVSchedules lo va llenando).
    private final ConcurrentHashMap<String, Schedule> agendasCamiones;
    private final ConcurrentHashMap<String, String> ubicacionesIniciales;

    // Velocidades nominales de cada camión (de Owl). Para mostrar en dashboard.
    private final ConcurrentHashMap<String, Double> velocidadVacioCamion;
    private final ConcurrentHashMap<String, Double> velocidadCargadoCamion;

    // Factor propio de velocidad (1.0 en Capa 1, aleatorio en Capa 3).
    private final ConcurrentHashMap<String, Double> factoresVelocidadCamion;

    // Mapa: nombre de pala -> ubicación física (id de vértice). Varias palas
    // pueden compartir ubicación (varias palas operando en un mismo punto).
    private final ConcurrentHashMap<String, String> ubicacionPalas;

    // Capacidad de descarga por destino (id con '#' -> maximumDischarge).
    private final ConcurrentHashMap<String, Integer> capacidadDestinos;

    // Schedule REAL: en Capa 1 queda vacío (real == plan). Se usa en Capa 3.
    private final ConcurrentHashMap<String, List<SlotReal>> agendasReales;

    // Semilla central (ParametrosSimulador.SEMILLA_ALEATORIA) → reproducible
    private final Random random = new Random(ParametrosSimulador.SEMILLA_ALEATORIA);

    private RegistroSimulacion() {
        agendasCamiones = new ConcurrentHashMap<>();
        ubicacionesIniciales = new ConcurrentHashMap<>();
        velocidadVacioCamion = new ConcurrentHashMap<>();
        velocidadCargadoCamion = new ConcurrentHashMap<>();
        factoresVelocidadCamion = new ConcurrentHashMap<>();
        ubicacionPalas = new ConcurrentHashMap<>();
        capacidadDestinos = new ConcurrentHashMap<>();
        agendasReales = new ConcurrentHashMap<>();
    }

    public static synchronized RegistroSimulacion getInstancia() {
        if (instancia == null) instancia = new RegistroSimulacion();
        return instancia;
    }

    // ================================================================
    //  CAMIONES — schedules planificados y datos básicos
    // ================================================================

    /**
     * Devuelve (creando si hace falta) el Schedule de un camión. Lo llama el
     * LectorCSVSchedules cuando detecta una fila nueva.
     */
    public Schedule obtenerOCrearSchedule(String nombreCamion) {
        return agendasCamiones.computeIfAbsent(nombreCamion, k -> {
            asignarFactorSiFalta(k); // fija el factor de velocidad del camión (ver metodo)
            return new Schedule();
        });
    }

    /**
     * Sortea (una sola vez, sembrado) el factor de velocidad propio de un
     * camión: 1.0 sin incertidumbre; con incertidumbre, en [1-V, 1+V] con
     * V = VARIACION_VELOCIDAD_CAMION.
     *
     * DETERMINISMO: el factor se deriva de SEMILLA + hash del NOMBRE (misma
     * técnica que TruckAgent con su Random propio), NO del Random compartido.
     * Con el Random compartido, el ORDEN de llegada de los hilos (TruckAgents
     * negociando vs LectorCSVSchedules) decidiría qué valor de la secuencia le
     * toca a cada camión → dos corridas con distinto método podrían asignar
     * factores distintos al mismo camión, rompiendo la comparabilidad que la
     * semilla promete. Por nombre, Truck5 recibe SIEMPRE el mismo factor sin
     * importar quién pregunte primero.
     */
    private void asignarFactorSiFalta(String camion) {
        factoresVelocidadCamion.computeIfAbsent(camion,
            kk -> {
                if (!ParametrosSimulador.INCERTIDUMBRES_ACTIVAS) return 1.0;
                double v = ParametrosSimulador.VARIACION_VELOCIDAD_CAMION;
                // Mezcla fuerte de la semilla + descarte del primer sorteo:
                // java.util.Random con semillas CERCANAS (hashes de "Truck5",
                // "Truck7"...) produce un PRIMER nextDouble casi identico
                // (correlacion conocida) → todos los factores saldrian ~iguales.
                Random r = new Random(ParametrosSimulador.SEMILLA_ALEATORIA
                        + kk.hashCode() * 0x9E3779B97F4A7C15L);
                r.nextDouble(); // descarta el primer valor (correlacionado)
                return (1.0 - v) + r.nextDouble() * (2.0 * v);
            });
    }

    public ConcurrentHashMap<String, Schedule> getAgendasCamiones() {
        return agendasCamiones;
    }

    public void registrarUbicacionInicialCamion(String camion, String ubicacion) {
        ubicacionesIniciales.put(camion, ubicacion);
    }

    public String getUbicacionInicial(String camion) {
        return ubicacionesIniciales.get(camion);
    }

    public ConcurrentHashMap<String, String> getUbicacionesIniciales() {
        return ubicacionesIniciales;
    }

    public void registrarVelocidadesNominales(String camion, double velVacio, double velCargado) {
        velocidadVacioCamion.put(camion, velVacio);
        velocidadCargadoCamion.put(camion, velCargado);
    }

    public double getVelocidadVacioCamion(String camion) {
        return velocidadVacioCamion.getOrDefault(camion, 0.0);
    }

    public double getVelocidadCargadoCamion(String camion) {
        return velocidadCargadoCamion.getOrDefault(camion, 0.0);
    }

    /**
     * Factor de velocidad propio del camión (perezoso: lo asigna si es la
     * primera vez que se pide, aunque el camión todavía no tenga slots
     * escritos). Lo usa TruckAgent al NEGOCIAR (para que sus propuestas de
     * tiempo reflejen su velocidad real, no la nominal del OWL) y
     * CalculadorScheduleReal al simular la ejecución — ambos ven el MISMO
     * valor sin importar quién lo pida primero.
     */
    public double getFactorVelocidadCamion(String camion) {
        asignarFactorSiFalta(camion);
        return factoresVelocidadCamion.getOrDefault(camion, 1.0);
    }

    /**
     * Factor de velocidad con el que se PLANIFICO cada viaje (factor propio del
     * camion x clima vigente al negociar), por identidad de slot.
     *
     * Hace falta porque TruckAgent.generaPropuesta ya calcula la duracion del
     * viaje con la velocidad efectiva del momento, y CalculadorScheduleReal
     * vuelve a aplicar el factor del camion y el clima al ejecutar. Sin
     * descontar el del plan, un viaje negociado en plena tormenta (x0.40)
     * quedaba 2,5 veces mas largo en el plan y ademas se ejecutaba a velocidad
     * de tormenta, aun despues de que la tormenta terminara.
     */
    private final Map<Slot, Double> factorPlanPorSlot =
            Collections.synchronizedMap(new java.util.IdentityHashMap<Slot, Double>());

    public void registrarFactorPlan(Slot slot, double factor) {
        factorPlanPorSlot.put(slot, factor);
    }

    /** 1.0 si el slot no tiene registro (por ejemplo, operaciones que no son viajes). */
    public double getFactorPlan(Slot slot) {
        Double f = factorPlanPorSlot.get(slot);
        return f == null ? 1.0 : f;
    }

    public ConcurrentHashMap<String, Double> getFactoresVelocidadCamion() {
        return factoresVelocidadCamion;
    }

    // ================================================================
    //  PALAS — ubicaciones físicas (varias palas pueden compartir punto)
    // ================================================================

    public void registrarPala(String nombrePala, String ubicacion) {
        ubicacionPalas.put(nombrePala, ubicacion);
    }

    public ConcurrentHashMap<String, String> getUbicacionPalas() {
        return ubicacionPalas;
    }

    /**
     * Agrupa las palas por ubicación física: ubicación -> lista de palas en
     * ese punto. La ventana lo usa para dibujar varias palas en abanico en
     * un mismo lugar de carga.
     */
    public Map<String, List<String>> getPalasPorUbicacion() {
        Map<String, List<String>> m = new TreeMap<>();
        for (Map.Entry<String, String> e : ubicacionPalas.entrySet()) {
            m.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (List<String> lista : m.values()) Collections.sort(lista);
        return m;
    }

    public int getCantidadPalasEnUbicacion(String ubicacion) {
        int n = 0;
        for (String u : ubicacionPalas.values()) if (u.equals(ubicacion)) n++;
        return Math.max(1, n);
    }

    // ================================================================
    //  DESTINOS DE DESCARGA — capacidad simultánea (maximumDischarge)
    // ================================================================

    public void registrarDestino(String destino, int capacidad) {
        capacidadDestinos.put(destino, Math.max(1, capacidad));
    }

    public int getCapacidadDestino(String destino) {
        return capacidadDestinos.getOrDefault(destino, 99);
    }

    public ConcurrentHashMap<String, Integer> getCapacidadDestinos() {
        return capacidadDestinos;
    }

    // ================================================================
    //  SCHEDULE REAL — usado en Capa 3 con incertidumbres
    // ================================================================

    public void setAgendaReal(String camion, List<SlotReal> slots) {
        agendasReales.put(camion, slots);
    }

    public List<SlotReal> getAgendaReal(String camion) {
        return agendasReales.getOrDefault(camion, new ArrayList<>());
    }

    public ConcurrentHashMap<String, List<SlotReal>> getAgendasReales() {
        return agendasReales;
    }

    /**
     * Versión "real" de un slot, con la hora a la que realmente sucedió tras
     * aplicar incertidumbres. En Capa 1 sin incertidumbres no se usa
     * (la ventana dibuja directo del plan).
     */
    public static class SlotReal {
        public final Slot slotPlanificado;
        public final long inicioReal;
        public final long finReal;
        public final long retraso; // finReal - slotPlanificado.getHoraFin()

        public SlotReal(Slot slotPlanificado, long inicioReal, long finReal) {
            this.slotPlanificado = slotPlanificado;
            this.inicioReal = inicioReal;
            this.finReal = finReal;
            this.retraso = finReal - slotPlanificado.getHoraFin();
        }
    }
}