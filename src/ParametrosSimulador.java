/**
 * Parámetros del MÓDULO SIMULADOR (separado de ParametrosConfiguracion para no
 * tocar el código de los agentes JADE).
 *
 * Editá estos valores antes de correr la simulación para identificar la corrida
 * en kpis_corridas.csv.
 */
public class ParametrosSimulador {

    /**
     * Etiqueta que identifica esta corrida en el archivo acumulativo
     * "kpis_corridas.csv". Cambiar antes de cada simulación para que cadas
     * corrida tenga una fila distinguible. Ejemplos:
     *   "pequeno_baseline_capa1"
     *   "mediano_baseline_capa1"
     *   "grande_baseline_capa1"
     */
    public static final String ETIQUETA_ESCENARIO = "grande_F1_COMPLETE";

    /**
     * Switch maestro de incertidumbre.
     *   false = baseline puro (Capa 1): real == plan, sin clima ni colas reales.
     *   true  = realismo de eventos (Capa 2): se aplican factores deS velocidad
     *           aleatorios por camión (±15%), 3 climas predeterminados, y las
     *           colas FIFO se forman dinámicamente según las llegadas reales.
     * Ponerlo en false vuelve EXACTAMENTE al comportamiento validado de la Capa 1.
     */
    public static final boolean INCERTIDUMBRES_ACTIVAS = true;

    /**
     * Switch del módulo de rescheduling reactivo (LEGACY — mantenido para
     * compatibilidad). Si está en false, fuerza METODO_RESCHEDULING = NINGUNO
     * sin importar lo que diga el enum. Para tu tesis controlas todo desde el
     * enum METODO_RESCHEDULING de abajo y dejas este flag en true.
     */
    public static final boolean RESCHEDULING_ACTIVO = true;

    /**
     * MÉTODO DE RESCHEDULING ACTIVO en esta corrida.
     *
     * Para tu tesis comparativa: corré 1 vez con cada valor y guardá el
     * "cascadas.csv" / "liberaciones.csv" + "kpis_corridas.csv" para
     * comparar los métodos puros sin contaminación.
     *
     *   NINGUNO              → solo observa retrasos y los loguea como
     *                          RIGHT_SHIFT_OMITIDO. Sirve para medir daño crudo.
     *   RIGHT_SHIFT_CASCADA  → empuja al camión disparador y propaga conflictos
     *                          hasta punto fijo (todo lo que vimos).
     *   PARTIAL_REPAIR       → libera los slots futuros del camión disparador
     *                          y deja que el CNP renegocie. No toca otros camiones.
     *   COMPLETE_REGENERATION→ ante una perturbación SISTÉMICA, descarta el plan
     *                          futuro de TODA la flota tomando el reloj actual
     *                          como t0 (respetando la tarea en curso de cada
     *                          camión) y deja que el CNP reconstruya el plan
     *                          global desde cero. Alto costo computacional: se
     *                          dispara solo cuando es necesario y con freno
     *                          anti-bucle (cooldown + tope por turno). Ver los
     *                          umbrales en ReschedulingAgent (UMBRAL_REGEN_*,
     *                          FRACCION_FLOTA_REGEN, COOLDOWN_REGEN_MS,
     *                          MAX_REGEN_POR_TURNO).
     */
    public enum MetodoRescheduling {
        NINGUNO, RIGHT_SHIFT_CASCADA, PARTIAL_REPAIR, COMPLETE_REGENERATION
    }

    public static final MetodoRescheduling METODO_RESCHEDULING =
            MetodoRescheduling.NINGUNO;

    /**
     * Variacion maxima del factor de velocidad propio de cada camion, como
     * fraccion (0.03 = ±3%). El factor por camion se sortea UNA vez (sembrado)
     * en [1-V, 1+V] y multiplica sus tiempos de viaje reales.
     *
     * ── Por que ±3% y no ±15% ────────────────────────────────────────────────
     * Validado con un tesista de despacho minero: en la mina real los camiones
     * NO se adelantan entre si (caminos de un carril, adelantamiento prohibido
     * por seguridad) y la velocidad la fija el LIMITE DEL CAMINO (política de
     * la mina), no el camion. El ±15% original + velocidades heterogeneas del
     * OWL (15-35 km/h vacio) producia adelantamientos sistematicos implicitos
     * (el simulador no modela interaccion en ruta). Con velocidades OWL
     * homogeneas (25 vacio / 20 cargado) y ±3% (variabilidad de operador y
     * carga), los tiempos de viaje quedan casi identicos → el orden de salida
     * se preserva de facto, sin modelar follow-the-leader.
     *
     * ── Por que 0 y no ±3% ───────────────────────────────────────────────────
     * Medido en mediano_COMPLETE_v2_r8: con ±3% hubo 31 adelantamientos en 2.348
     * pares de camiones que compartian ruta al mismo tiempo (1,3%), de 50 a 120
     * s en viajes largos. Ademas el factor se aplica dos veces (en la propuesta
     * del TruckAgent y otra vez en CalculadorScheduleReal.simularSlot), asi
     * que en la practica era ±6%. Con 0 todos los camiones van a la misma
     * velocidad y el clima los frena a todos por igual en cada instante: en un
     * mismo camino ninguno adelanta a otro. Excepcion que sigue sin modelarse:
     * un camion averiado en ruta no detiene a los que vienen detras.
     */
    public static final double VARIACION_VELOCIDAD_CAMION = 0.0;

    /**
     * Semilla maestra de toda la aleatoriedad sembrable del simulador
     * (factores de velocidad de los camiones en RegistroSimulacion, desempates
     * de CFP en TruckAgent). Fijarla hace que dos corridas con el MISMO metodo
     * partan de la misma realidad de incertidumbre → comparables.
     *
     * IMPORTANTE para la tesis: para comparar metodos (NINGUNO vs CASCADA vs
     * PARTIAL) hay que correrlos con la MISMA semilla. Cambia solo
     * METODO_RESCHEDULING entre corridas, deja esta semilla fija.
     *
     * Limitacion conocida: JADE corre los agentes en hilos concurrentes, asi
     * que el ORDEN de las negociaciones no es 100% determinista aunque la
     * semilla este fija. La semilla elimina la fuente DOMINANTE de varianza
     * (las velocidades), no toda. Para promediar el ruido residual, conviene
     * correr 3-5 veces por metodo y reportar media ± desviacion.
     */
    public static final long SEMILLA_ALEATORIA = 42L;

    /**
     * PLAN INICIAL UNICO (reunion con el profesor, 14-oct-2026).
     *
     *   true  = no se negocia el plan inicial: cada agente carga su agenda de
     *           planes_iniciales/plan_inicial_<flota>.csv y la simulacion
     *           arranca apenas esta todo cargado. Todos los metodos y todas las
     *           replicas parten del mismo plan. Es lo que se usa para las
     *           corridas de la tesis.
     *   false = se negocia un plan nuevo, como antes. Sirve para generar el plan
     *           de un escenario que todavia no lo tiene (mediano, pequeno): al
     *           terminar la negociacion, si el plan quedo limpio, el programa lo
     *           guarda solo como plan unico del escenario (GuardarPlanInicial).
     *           Si el escenario ya tenia plan, no lo reemplaza; para eso:
     *             java -cp bin GuardarPlanInicial <etiqueta> --reemplazar
     *
     * El archivo va ligado a NOMBRE_ARCHIVO_FLOTA: al cambiar de escenario se
     * carga el plan de ese escenario. Si no existe, la simulacion no arranca y
     * lo avisa por consola. En cada corrida, corridas/<etiqueta>/
     * plan_inicial_verificacion.txt dice si partio exactamente del plan unico.
     * Ver PlanInicialFijo y GuardarPlanInicial.
     */
    public static final boolean USAR_PLAN_INICIAL_FIJO = false;

    /**
     * Cada cuántos milisegundos releé el CSV de schedules para detectar nuevos
     * slots agregados por los agentes. 500ms es un balance entre fluidez visual
     * y carga de I/O.
     */
    public static final long POLLING_CSV_MS = 500L;

    // ── Reproducibilidad del reloj ──────────────────────────────────────────

    /**
     * Velocidad de reproduccion FIJA de la corrida (multiplicador del cursor
     * de simulacion). Antes se leia del JSlider en cada tick, asi que moverlo
     * a mitad de corrida cambiaba la velocidad sin dejar rastro. Ahora sale de
     * aca, se registra en los CSV de la corrida y el slider queda solo como
     * indicador visual.
     *
     * Con el congelamiento por episodio (EpisodioRescheduling) y la compuerta
     * de granularidad del detector, este numero NO deberia alterar los
     * resultados: esa es justamente la prueba de aceptacion (correr el mismo
     * escenario a 20x y a 100x y comparar). Se fija igual porque la
     * comparabilidad entre corridas no debe depender de que nadie toque el
     * control.
     *
     * Cota: debe cumplir INTERVALO_CHEQUEO x VELOCIDAD <=
     * GRANULARIDAD_DETECCION_SIM_MS, es decir VELOCIDAD <= 120.
     */
    public static final int VELOCIDAD_REPRODUCCION = 100;

    /**
     * Arranque automatico al converger la negociacion inicial.
     *
     * El simulador arranca en pausa mientras los agentes construyen el plan del
     * turno. Si se le da PLAY antes de que termine, el cursor avanza sobre un
     * plan incompleto: camiones sin asignacion y disparos espurios del detector.
     * Pero si se espera "a ojo", CUANTO se espero pasa a ser una variable oculta
     * distinta en cada corrida.
     *
     * Con esto activado la simulacion arranca sola cuando TODAS las palas
     * llenaron su turno (ShovelAgent.negociacionInicialCompleta), es decir
     * cuando la negociacion inicial no tiene nada mas que asignar. El instante
     * de arranque deja de depender del operador.
     *
     * El plan NO se arma de una sola vez: cada pala negocia un ciclo cada 2
     * segundos REALES y sigue hasta llenar las 6 horas. MEDIDO en el escenario
     * mediano: construir las ~1360 slots del turno toma unos 26 MINUTOS de
     * reloj de pared, no dos como decia este comentario antes de medirlo. El
     * 90% de las rondas no coloca a nadie (una pala sin postulantes avanza su
     * oferta 60 s y reintenta), de ahi el costo.
     */
    public static final boolean ARRANQUE_AUTOMATICO = true;

    /**
     * TOPE de tiempo total de la negociacion inicial. Es un anti-cuelgue: si a
     * los N minutos no todas las palas declararon su turno lleno, se arranca
     * igual y se avisa por stderr que el plan quedo incompleto.
     *
     * Reemplaza a la ventana de "quietud del plan" que se uso antes, y que
     * resulto inservible como criterio: una pala sin postulantes avanza su
     * oferta 60 s y reintenta SIN agregar slots, asi que el plan se queda quieto
     * varios minutos mientras la negociacion sigue trabajando. Con ventanas de
     * 10 s, 90 s y 5 min las corridas arrancaban con 2/10, 3/10, 4/10 y 9/10
     * palas terminadas, dando planes iniciales de 940 a 1372 slots — 31% de
     * dispersion que arrastraba toda la produccion.
     *
     * Medido: llegar a 10/10 toma unos 26 minutos en el escenario mediano. En
     * el grande, con 40 minutos, 2 de 4 corridas arrancaron por el tope con el
     * plan a medias: una pala sin postulantes recorre su turno de a 60 s por
     * intento (~360 negociaciones fallidas, casi media hora), y con 105
     * camiones cada negociacion tarda mas. 90 minutos deja margen para el
     * grande; el tope solo debe actuar si algo quedo realmente colgado.
     */
    public static final long TOPE_NEGOCIACION_INICIAL_MS = 5_400_000L; // 90 min

    /**
     * Terminar la corrida apenas se genera el schedule inicial (nov-2026).
     *
     *   true  = al capturar el plan inicial NO se ejecuta el turno: se escriben
     *           los registros y estadisticas del plan en corridas/<etiqueta>/,
     *           se muestra un resumen y se apaga JADE (CierrePlanInicial).
     *           Ademas, si corridas/<etiqueta>/ ya existe, la corrida no
     *           arranca (para no sobrescribir resultados).
     *   false = comportamiento anterior: la simulacion del turno arranca sola.
     *
     * Ver DOCUMENTACION_REGISTROS.md.
     */
    public static final boolean TERMINAR_TRAS_PLAN_INICIAL = true;
}
