import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;

/**
 * Ventana de simulación — CAPA 1 (baseline sin incertidumbres).
 *
 * Replica la interfaz visual del simulador anterior (dashboard de telemetría a
 * la izquierda, mapa de la mina con pan/zoom a la derecha, controles abajo),
 * pero dibuja los camiones siguiendo DIRECTAMENTE el schedule planificado que
 * produce la negociación. Como en la Capa 1 no hay incertidumbre, el "real"
 * coincide con el "plan", así que no se usa CalculadorScheduleReal ni
 * GestorEventos (eso entra en la Capa 3).
 *
 * No toca el código de los agentes JADE. Lee los schedules del
 * RegistroSimulacion, que a su vez los recoge del CSV vía LectorCSVSchedules.
 */
public class VentanaSimulacion extends JFrame {
    private static final long serialVersionUID = 1L;

    private long tiempoSimulacionMs = 0;
    private long tiempoMaximoSimulacionMs = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO;

    /**
     * Espejo estatico del reloj de simulacion, legible desde los agentes JADE
     * (que no tienen referencia a la ventana). Lo actualiza el timer de la
     * ventana cada 50ms. Vale 0 hasta que la ventana arranca — inocuo, porque
     * se usa solo como cota inferior (Math.max) en las ofertas de las palas.
     */
    public static volatile long tiempoSimulacionGlobalMs = 0;

    public long getTiempoSimulacionMs() { return tiempoSimulacionMs; }
    public boolean isPausado() { return pausado; }
    private int multiplicadorVelocidad = 100;
    private boolean pausado = true;
    private boolean yaExporto = false; // exportar el KPI una sola vez por corrida
    private Graph<Vertice, DefaultWeightedEdge> grafoMina;

    private double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
    private double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

    private final int ANCHO_DASHBOARD = 550;
    private final int ALTO_BANNER_EVENTO = 60;

    // Cámara (mapa)
    private double zoomFactor = 1.0;
    private int offsetX = 0;
    private int offsetY = 0;
    private Point dragStartPoint;
    // El encuadre inicial se ajusta una vez (de forma perezosa) cuando ya están
    // registradas las palas/destinos, para que TODOS los puntos de operación
    // queden dentro del viewport (incluso los geográficamente alejados como #UB1).
    private boolean encuadreAjustado = false;

    // Scroll del panel de telemetría (rueda del ratón sobre el dashboard)
    private int dashboardScrollY = 0;

    // Cache de rutas Dijkstra (evita recalcular el camino en cada frame)
    private Map<String, GraphPath<Vertice, DefaultWeightedEdge>> cacheRutas = new HashMap<>();

    // --- Incertidumbre (clima + velocidad + colas reales) ---
    // Solo se usan si ParametrosSimulador.INCERTIDUMBRES_ACTIVAS == true.
    private final boolean conIncertidumbre = ParametrosSimulador.INCERTIDUMBRES_ACTIVAS;
    private GestorEventos gestor;
    private long ultimaFirmaPlan = 0L; // firma de contenido: recalcular el real cuando cambia

    // ── Arranque automatico al converger la negociacion inicial ─────────────
    private boolean yaArranco          = false;
    private long    firmaPlanArranque  = 0L;
    private long    tRealFirmaEstable  = 0L;
    private long    tRealPrimerSlot    = 0L;
    private long    tRealUltimaTraza   = 0L;
    // Plan inicial unico (ver PlanInicialFijo)
    private long    tRealInicioPlanFijo = 0L;
    private boolean planFijoAvisado     = false;
    private boolean planFijoListoImpreso = false;
    private long    tRealPalasCompletas = 0L;
    private boolean avisoCopias         = false;
    private long    tRealFinDetectado   = 0L;   // fin de la negociacion detectado (CierrePlanInicial)
    private long    tRealInicioSinSlots = 0L;   // inicio de la espera sin ningun slot (evaluarCierreSinSlots)
    private long ultimaVersionEventos = -1; // recalcular también si cambian los eventos

    public VentanaSimulacion(Graph<Vertice, DefaultWeightedEdge> grafo) {
        this.grafoMina = grafo;
        if (conIncertidumbre) gestor = GestorEventos.getInstancia();
        setTitle(conIncertidumbre
                ? "Dashboard MAS - Simulación  [CON INCERTIDUMBRE: clima + velocidad + colas reales]"
                : "Dashboard MAS - Simulación  [CAPA 1 - SIN INCERTIDUMBRE (baseline)]");
        setSize(1440, 820);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        calcularLimitesMapaRobustos();

        // ---------- PANEL SUR (timeline + controles) ----------
        JPanel panelSur = new JPanel(new BorderLayout());
        panelSur.setBackground(new Color(40, 44, 52));

        JPanel panelTimeline = new JPanel(new BorderLayout());
        panelTimeline.setBackground(new Color(40, 44, 52));
        panelTimeline.setBorder(BorderFactory.createEmptyBorder(10, 20, 5, 20));

        JLabel lblInicio = new JLabel("00:00:00");
        lblInicio.setForeground(Color.WHITE);
        JLabel lblFin = new JLabel(Utilidades.longToHHMMSS(tiempoMaximoSimulacionMs));
        lblFin.setForeground(Color.WHITE);

        JSlider sliderTiempo = new JSlider(0, (int) tiempoMaximoSimulacionMs, 0);
        sliderTiempo.setBackground(new Color(40, 44, 52));

        panelTimeline.add(lblInicio, BorderLayout.WEST);
        panelTimeline.add(sliderTiempo, BorderLayout.CENTER);
        panelTimeline.add(lblFin, BorderLayout.EAST);

        JPanel panelControles = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        panelControles.setBackground(new Color(40, 44, 52));

        JButton btnPlayPause = new JButton("\u25B6 PLAY SIMULACIÓN");
        btnPlayPause.setBackground(new Color(97, 175, 239));
        btnPlayPause.setFont(new Font("Arial", Font.BOLD, 14));
        if (ParametrosSimulador.ARRANQUE_AUTOMATICO) {
            // El boton queda BLOQUEADO hasta que la negociacion inicial
            // converge. Si se pudiera arrancar antes, el cursor avanzaria sobre
            // un plan incompleto y, ademas, no se tomaria la fotografia del
            // plan predictivo (PlanBase) que sirve de referencia a la
            // estabilidad. Fue exactamente lo que paso en la primera corrida.
            btnPlayPause.setEnabled(false);
            btnPlayPause.setText("⏳ NEGOCIANDO PLAN INICIAL...");
        }

        JSlider sliderVelocidad = new JSlider(1, 2000, 100);
        sliderVelocidad.setBackground(new Color(40, 44, 52));

        JLabel lblTiempoReloj = new JLabel("RELOJ: 00:00:00");
        lblTiempoReloj.setForeground(new Color(152, 195, 121));
        lblTiempoReloj.setFont(new Font("Consolas", Font.BOLD, 22));

        btnPlayPause.addActionListener(e -> {
            pausado = !pausado;
            btnPlayPause.setText(pausado ? "\u25B6 PLAY SIMULACIÓN" : "\u23F8 PAUSAR");
            btnPlayPause.setBackground(pausado ? new Color(97, 175, 239) : new Color(224, 108, 117));
        });

        JLabel lblVel = new JLabel("Velocidad sim.:");
        lblVel.setForeground(Color.WHITE);

        JButton btnExportar = new JButton("\uD83D\uDCBE EXPORTAR AUDITORÍA");
        btnExportar.setBackground(new Color(152, 195, 121));
        btnExportar.setFont(new Font("Arial", Font.BOLD, 12));
        btnExportar.addActionListener(e -> CsvSimulacionAuditor.exportarDatos());

        panelControles.add(btnPlayPause);
        panelControles.add(lblVel);
        panelControles.add(sliderVelocidad);
        panelControles.add(lblTiempoReloj);
        // Botón para agregar eventos a mano (solo con incertidumbre activa).
        if (conIncertidumbre) {
            JButton btnAgregarEvento = new JButton("\u26A0 AGREGAR EVENTO");
            btnAgregarEvento.setBackground(new Color(229, 192, 123));
            btnAgregarEvento.setFont(new Font("Arial", Font.BOLD, 12));
            btnAgregarEvento.addActionListener(e -> mostrarDialogoAgregarEvento());
            panelControles.add(btnAgregarEvento);
        }
        panelControles.add(btnExportar);

        panelSur.add(panelTimeline, BorderLayout.NORTH);
        panelSur.add(panelControles, BorderLayout.SOUTH);
        add(panelSur, BorderLayout.SOUTH);

        // ---------- PANEL MAPA (centro) ----------
        PanelMapa panelMapa = new PanelMapa();
        panelMapa.setBackground(new Color(28, 30, 36));

        panelMapa.addMouseWheelListener(e -> {
            if (e.getX() < ANCHO_DASHBOARD) {
                // Rueda sobre el dashboard → scroll de telemetría
                dashboardScrollY = Math.max(0, dashboardScrollY + e.getWheelRotation() * 25);
            } else {
                // Rueda sobre el mapa → zoom
                if (e.getWheelRotation() < 0) zoomFactor *= 1.15;
                else zoomFactor /= 1.15;
            }
            panelMapa.repaint();
        });
        panelMapa.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragStartPoint = e.getPoint(); }
        });
        panelMapa.addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (dragStartPoint != null) {
                    offsetX += e.getX() - dragStartPoint.x;
                    offsetY += e.getY() - dragStartPoint.y;
                    dragStartPoint = e.getPoint();
                    panelMapa.repaint();
                }
            }
        });

        add(panelMapa, BorderLayout.CENTER);

        // ---------- Slider de tiempo (arrastre manual) ----------
        sliderTiempo.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (sliderTiempo.getValueIsAdjusting()) {
                    tiempoSimulacionMs = sliderTiempo.getValue();
                    lblTiempoReloj.setText("RELOJ: " + Utilidades.longToHHMMSS(tiempoSimulacionMs));
                    panelMapa.repaint();
                }
            }
        });

        // ---------- Hilo de simulación (reloj) ----------
        Timer timer = new Timer(50, e -> {
            tiempoSimulacionGlobalMs = tiempoSimulacionMs;  // espejo para los agentes
            actualizarTiempoMaximo(sliderTiempo, lblFin);
            if (conIncertidumbre) recalcularRealSiCambio();

            // CONGELAMIENTO POR NEGOCIACION: mientras hay un episodio de
            // rescheduling abierto, el cursor NO avanza. La realidad esta
            // precalculada, asi que congelar es seguro: el cursor es un puntero
            // de lectura, no hay fisica corriendo en vivo. Las decisiones se
            // toman en un INSTANTE (semantica de eventos discretos) y el costo
            // de decidir se cobra despues como LATENCIA_DECISION_MS, un
            // parametro del modelo en vez de un accidente de la CPU.
            boolean congelado = conIncertidumbre && EpisodioRescheduling.congelado();

            // ARRANQUE AUTOMATICO: se despausa sola cuando la negociacion
            // inicial converge (la firma del plan deja de cambiar). Elimina como
            // variable oculta el momento en que el operador aprieta PLAY.
            if (pausado && !yaArranco) evaluarArranqueAutomatico(btnPlayPause);

            // Red de seguridad: si la simulacion empezo a avanzar por cualquier
            // via sin haber pasado por el arranque automatico, se toma igual la
            // fotografia del plan predictivo. Sin ella no hay con que comparar
            // la estabilidad al final de la corrida.
            if (!pausado && conIncertidumbre && !PlanBase.hayPlan()) {
                PlanBase.capturar(tiempoSimulacionMs, "arranque_manual");
            }

            if (!pausado && !congelado && tiempoSimulacionMs < tiempoMaximoSimulacionMs) {
                // Velocidad FIJA por codigo, no del slider: moverlo a mitad de
                // corrida cambiaba la velocidad sin dejar rastro en ningun CSV.
                multiplicadorVelocidad = ParametrosSimulador.VELOCIDAD_REPRODUCCION;
                // La latencia acumulada de los episodios ya cerrados se suma una
                // sola vez, al reanudar.
                tiempoSimulacionMs += EpisodioRescheduling.consumirLatencia()
                                    + (50L * multiplicadorVelocidad);
                if (tiempoSimulacionMs > tiempoMaximoSimulacionMs) {
                    // La simulacion corre hasta el FINAL real (fin de la ultima
                    // actividad de cualquier camion), NO se corta a las 6h. Recien
                    // aca se hace auto-stop y se exporta el KPI, una sola vez.
                    tiempoSimulacionMs = tiempoMaximoSimulacionMs;
                    pausado = true;
                    autoStopYExportar(btnPlayPause, "simulacion completa a t="
                            + Utilidades.longToHHMMSS(tiempoSimulacionMs));
                }
                lblTiempoReloj.setText("RELOJ: " + Utilidades.longToHHMMSS(tiempoSimulacionMs)
                        + "   vel: " + multiplicadorVelocidad + "x");
                sliderTiempo.setValue((int) tiempoSimulacionMs);
            } else if (congelado) {
                lblTiempoReloj.setText("RELOJ: " + Utilidades.longToHHMMSS(tiempoSimulacionMs)
                        + "   [NEGOCIANDO]");
            }
            panelMapa.repaint();
        });
        timer.start();
    }

    /**
     * Helper para auto-stop: cambia el boton a estado PLAY, opcional log de
     * razon y exporta la auditoria. Usa escape Unicode para el caracter PLAY
     * (sino el editor tiene problemas con la fuente del IDE).
     */
    private void autoStopYExportar(javax.swing.JButton btnPlayPause, String razon) {
        btnPlayPause.setText("▶ PLAY SIMULACIÓN");
        if (razon != null && !razon.isEmpty()) {
            System.out.println("[sim] auto-stop: " + razon);
        }
        // Exportar el KPI una sola vez por corrida (evita filas duplicadas si el
        // auto-stop se reevalua o el usuario vuelve a dar play tras el final).
        if (yaExporto) return;
        yaExporto = true;
        CsvSimulacionAuditor.exportarDatos();
    }

    /**
     * Auto-stop: true si NO hay actividad en curso Y NO hay slot por arrancar
     * en los proximos 15 min de sim. Los slots empujados muy lejos por cascadas
     * (horizonte &gt; 15 min) se consideran "huerfanos" y se ignoran — el truck
     * real ya termino su jornada visible.
     *
     * Criterio anterior (todos los slots terminados) no funcionaba porque las
     * cascadas dejan slots planificados a 8-9h que mantenian tiempoMaximoSimulacionMs
     * extendido aunque ningun truck estuviera operando.
     */
    private boolean todosLosTrucksTerminaron() {
        java.util.Map<String, Schedule> agendas =
                RegistroSimulacion.getInstancia().getAgendasCamiones();
        if (agendas.isEmpty()) return false;

        long horizonteCercano = tiempoSimulacionMs + 15 * 60_000L;  // 15 min sim
        boolean hayAlMenosUnSchedule = false;

        for (Schedule sch : agendas.values()) {
            synchronized (sch) {
                if (sch.getSlots().isEmpty()) continue;
                hayAlMenosUnSchedule = true;
                for (Slot s : sch.getSlots()) {
                    // Slot en curso ahora.
                    if (s.getHoraInicio() <= tiempoSimulacionMs
                            && tiempoSimulacionMs < s.getHoraFin()) {
                        return false;
                    }
                    // Slot por arrancar dentro del horizonte cercano.
                    if (s.getHoraInicio() > tiempoSimulacionMs
                            && s.getHoraInicio() <= horizonteCercano) {
                        return false;
                    }
                }
            }
        }
        return hayAlMenosUnSchedule;
    }

    /**
     * Extiende el tiempo máximo de la simulación si algún schedule (plan o real)
     * termina más tarde que el turno nominal.
     */
    private void actualizarTiempoMaximo(JSlider sliderTiempo, JLabel lblFin) {
        long maximoEncontrado = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO;
        for (Schedule schedule : RegistroSimulacion.getInstancia().getAgendasCamiones().values()) {
            synchronized (schedule) {
                if (!schedule.getSlots().isEmpty()) {
                    long ultima = schedule.getSlots().get(schedule.getSlots().size() - 1).getHoraFin();
                    if (ultima > maximoEncontrado) maximoEncontrado = ultima;
                }
            }
        }
        // Con incertidumbre, el real puede terminar más tarde que el plan.
        if (conIncertidumbre) {
            for (List<RegistroSimulacion.SlotReal> lista
                    : RegistroSimulacion.getInstancia().getAgendasReales().values()) {
                if (!lista.isEmpty()) {
                    long ultima = lista.get(lista.size() - 1).finReal;
                    if (ultima > maximoEncontrado) maximoEncontrado = ultima;
                }
            }
        }
        if (maximoEncontrado > tiempoMaximoSimulacionMs) {
            tiempoMaximoSimulacionMs = maximoEncontrado;
            sliderTiempo.setMaximum((int) tiempoMaximoSimulacionMs);
            lblFin.setText(Utilidades.longToHHMMSS(tiempoMaximoSimulacionMs));
        }
    }

    /**
     * Recalcula el schedule real (con colas FIFO, velocidad y clima) solo cuando
     * cambió el número total de slots planificados — es decir, cuando la
     * negociación agregó actividades nuevas. Evita recalcular en cada frame.
     */
    private void recalcularRealSiCambio() {
        Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
        long firmaPlan = firmaDelPlan(agendas);
        long versionEventos = gestor.getVersion();
        if (firmaPlan == ultimaFirmaPlan && versionEventos == ultimaVersionEventos) return; // nada nuevo
        ultimaFirmaPlan = firmaPlan;
        ultimaVersionEventos = versionEventos;
        recalcularReal(agendas);
    }

    /**
     * Despausa la simulacion cuando la negociacion inicial converge.
     *
     * Criterio: TODAS las palas declararon su turno lleno, es decir la
     * negociacion inicial no tiene nada mas que asignar. Con tope de tiempo
     * total como unico respaldo (ver TOPE_NEGOCIACION_INICIAL_MS).
     *
     * Motivo: darle PLAY antes de que el plan este completo hace que el cursor
     * avance sobre camiones sin asignacion y el detector dispare por retrasos
     * que en realidad son plan faltante. Esperar "a ojo" tampoco sirve, porque
     * entonces el tiempo de espera es una variable distinta en cada corrida.
     */
    private void evaluarArranqueAutomatico(javax.swing.JButton btnPlayPause) {
        if (!ParametrosSimulador.ARRANQUE_AUTOMATICO) return;

        // Con plan inicial unico no hay negociacion: se espera a que todos los
        // agentes carguen su agenda y el simulador la lea entera.
        if (PlanInicialFijo.usar() && !planFijoListo(btnPlayPause)) return;

        Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
        if (agendas.isEmpty()) {            // todavia no llego ningun slot
            if (ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL && !PlanInicialFijo.usar()) {
                evaluarCierreSinSlots(btnPlayPause);
            }
            return;
        }

        long firma = firmaDelPlan(agendas);
        long ahora = System.currentTimeMillis();

        // TRAZA DE LA NEGOCIACION INICIAL. Sin esto no se ve nada mientras se
        // construye el plan: una pala sin postulantes avanza su oferta 60 s y
        // reintenta, sin agregar slots, asi que el plan puede quedar quieto
        // muchos minutos aunque la negociacion siga trabajando.
        if (tRealPrimerSlot == 0L) tRealPrimerSlot = ahora;
        if (ahora - tRealUltimaTraza >= 15_000L) {
            tRealUltimaTraza = ahora;
            int slots = 0;
            for (Schedule sch : agendas.values()) {
                synchronized (sch) { slots += sch.getSlots().size(); }
            }
            System.out.printf("[Negociacion inicial] t+%.0fs  palas %d/%d  "
                    + "camiones %d  slots %d  (quieto hace %.0fs)%n",
                    (ahora - tRealPrimerSlot) / 1000.0,
                    ShovelAgent.getPalasConTurnoLleno(),
                    ShovelAgent.getPalasRegistradas(),
                    agendas.size(), slots,
                    tRealFirmaEstable == 0L ? 0.0
                            : (ahora - tRealFirmaEstable) / 1000.0);
        }

        if (firma != firmaPlanArranque) {   // el plan sigue creciendo
            firmaPlanArranque = firma;
            tRealFirmaEstable = ahora;
            return;
        }
        if (tRealFirmaEstable == 0L) { tRealFirmaEstable = ahora; return; }

        // CRITERIO EXACTO Y UNICO: todas las palas llenaron su turno, o sea que
        // la negociacion inicial no tiene nada mas que asignar.
        //
        // La QUIETUD DEL PLAN no sirve como criterio, medido: una pala sin
        // postulantes avanza su oferta 60 s y reintenta sin agregar slots, asi
        // que el plan se queda quieto MINUTOS mientras la negociacion sigue
        // teniendo trabajo productivo por delante. Con ventanas de 10 s, 90 s y
        // hasta 5 min la simulacion arrancaba con 2/10, 3/10, 4/10 y 9/10 palas
        // terminadas — es decir en un punto arbitrario — y eso produjo planes
        // iniciales de 940 a 1372 slots (31% de dispersion), que arrastraba toda
        // la produccion y hacia incomparables las corridas.
        //
        // El respaldo pasa a ser un TOPE DE TIEMPO TOTAL de negociacion, que es
        // predecible: se mide que llegar a 10/10 toma unos 26 min, asi que el
        // tope solo actua si algo quedo realmente colgado.
        boolean porPalas = ShovelAgent.negociacionInicialCompleta();
        boolean porRespaldo = (ahora - tRealPrimerSlot)
                >= ParametrosSimulador.TOPE_NEGOCIACION_INICIAL_MS;
        // Instante (reloj de pared) en que se detecto el fin de la negociacion,
        // antes de esperar las copias. Solo se registra (espera_copias_ms).
        if ((porPalas || porRespaldo) && tRealFinDetectado == 0L) tRealFinDetectado = ahora;

        // Negociacion terminada: antes de fotografiar el plan, se espera a que
        // las cuatro copias de la agenda tengan las mismas tareas. El lector
        // del CSV y el ultimo INFORM al punto de descarga pueden ir unos
        // instantes atrasados (grande_V7_PARTIAL arranco sin el ultimo ciclo de
        // Truck31 en la copia del simulador). Tope de 30 s: si no calzan, se
        // arranca igual y GuardarPlanInicial lo deja anotado.
        if (porPalas && !PlanInicialFijo.usar() && !PlanInicialFijo.copiasAlDia()) {
            if (tRealPalasCompletas == 0L) tRealPalasCompletas = ahora;
            if (ahora - tRealPalasCompletas < 30_000L) return;
            if (!avisoCopias) {
                avisoCopias = true;
                System.err.println("[VentanaSimulacion] AVISO: 30 s despues de terminar la negociacion"
                        + " las copias de la agenda todavia no coinciden; se arranca igual."
                        + " Ver plan_inicial_verificacion.txt de la corrida.");
            }
        }

        if (porPalas || porRespaldo) {
            if (!porPalas) {
                System.err.printf("[VentanaSimulacion] AVISO: se arranca por TOPE "
                        + "DE TIEMPO (%.0f min de negociacion) con solo %d/%d palas "
                        + "con el turno lleno. EL PLAN INICIAL ESTA INCOMPLETO y "
                        + "esta corrida NO es comparable con las demas.%n",
                        ParametrosSimulador.TOPE_NEGOCIACION_INICIAL_MS / 60_000.0,
                        ShovelAgent.getPalasConTurnoLleno(),
                        ShovelAgent.getPalasRegistradas());
            }
            yaArranco = true;
            if (ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL) {
                // Solo interesa el schedule inicial: se fotografia el plan
                // (igual que antes, PlanBase.capturar escribe plan_inicial.csv),
                // NO se arranca el turno (el reloj queda en 0) y se cierra la
                // corrida: registros, resumen y apagado de JADE.
                String motivo = !porPalas ? "respaldo_estabilidad"
                        : PlanInicialFijo.usar() ? "plan_inicial_fijo" : "negociacion_completa";
                long tCaptura = System.currentTimeMillis();
                PlanBase.capturar(tiempoSimulacionMs, motivo);
                btnPlayPause.setEnabled(false);
                btnPlayPause.setText("✔ PLAN INICIAL GENERADO - cerrando");
                CierrePlanInicial.cerrar(motivo, tCaptura, tRealFinDetectado);
                return;
            }
            pausado   = false;
            // Fotografia del PLAN PREDICTIVO justo antes de que el cursor avance:
            // es la referencia contra la que se mide la estabilidad al final.
            PlanBase.capturar(tiempoSimulacionMs,
                    !porPalas ? "respaldo_estabilidad"
                            : PlanInicialFijo.usar() ? "plan_inicial_fijo" : "negociacion_completa");
            btnPlayPause.setEnabled(true);
            btnPlayPause.setText("⏸ PAUSAR");
            btnPlayPause.setBackground(new Color(224, 108, 117));
            int slots = 0;
            for (Schedule sch : agendas.values()) {
                synchronized (sch) { slots += sch.getSlots().size(); }
            }
            System.out.printf("[VentanaSimulacion] ARRANQUE AUTOMATICO: %d camiones, "
                    + "%d slots, %d/%d palas con el turno lleno (motivo=%s). "
                    + "velocidad=%dx%n",
                    agendas.size(), slots,
                    ShovelAgent.getPalasConTurnoLleno(),
                    ShovelAgent.getPalasRegistradas(),
                    !porPalas ? "respaldo de estabilidad"
                            : PlanInicialFijo.usar() ? "plan inicial fijo" : "negociacion completa",
                    ParametrosSimulador.VELOCIDAD_REPRODUCCION);
        }
    }

    /**
     * Negociacion inicial SIN NINGUN SLOT (p. ej. DEADLINE_MS tan bajo que
     * ninguna negociacion tiene ganador). Sin esto la corrida quedaba abierta
     * para siempre: evaluarArranqueAutomatico sale antes de mirar el criterio
     * de palas llenas y el tope, porque las agendas del simulador se crean
     * recien con el primer slot. Solo actua con TERMINAR_TRAS_PLAN_INICIAL y
     * solo mientras no hay slots, asi que no cambia nada cuando hay plan.
     *
     * Cierra con plan vacio cuando todas las palas llenaron su turno (motivo
     * negociacion_completa) o, si eso no pasa, al cumplirse el tope contado
     * desde la primera evaluacion de la ventana (motivo respaldo_estabilidad).
     */
    private void evaluarCierreSinSlots(javax.swing.JButton btnPlayPause) {
        long ahora = System.currentTimeMillis();
        if (tRealInicioSinSlots == 0L) tRealInicioSinSlots = ahora;
        if (ahora - tRealInicioSinSlots >= 15_000L && ahora - tRealUltimaTraza >= 15_000L) {
            tRealUltimaTraza = ahora;
            System.out.printf("[Negociacion inicial] t+%.0fs  palas %d/%d  camiones 0  slots 0  (sin slots todavia)%n",
                    (ahora - tRealInicioSinSlots) / 1000.0,
                    ShovelAgent.getPalasConTurnoLleno(), ShovelAgent.getPalasRegistradas());
        }
        boolean porPalas = ShovelAgent.negociacionInicialCompleta();
        boolean porRespaldo = (ahora - tRealInicioSinSlots) >= ParametrosSimulador.TOPE_NEGOCIACION_INICIAL_MS;
        // Si los agentes tienen tareas que el simulador aun no lee, se espera:
        // el camino normal se hace cargo apenas aparezca el primer slot.
        if (porPalas && !PlanInicialFijo.copiasAlDia()) return;
        if (!porPalas && !porRespaldo) return;
        if (tRealFinDetectado == 0L) tRealFinDetectado = ahora;
        yaArranco = true;
        String motivo = porPalas ? "negociacion_completa" : "respaldo_estabilidad";
        System.err.printf("[VentanaSimulacion] AVISO: la negociacion inicial termino SIN NINGUN SLOT "
                + "(%d/%d palas con el turno lleno, motivo=%s). Se cierra con plan vacio.%n",
                ShovelAgent.getPalasConTurnoLleno(), ShovelAgent.getPalasRegistradas(), motivo);
        long tCaptura = System.currentTimeMillis();
        PlanBase.capturar(tiempoSimulacionMs, motivo);
        btnPlayPause.setEnabled(false);
        btnPlayPause.setText("✔ PLAN INICIAL (VACIO) GENERADO - cerrando");
        CierrePlanInicial.cerrar(motivo, tCaptura, tRealFinDetectado);
    }

    /**
     * Plan inicial unico: true cuando todos los agentes cargaron su agenda del
     * archivo y el simulador ya leyo todas las tareas. Si el archivo falta o no
     * calza con la flota, la simulacion no arranca y se avisa una vez.
     */
    private boolean planFijoListo(javax.swing.JButton btnPlayPause) {
        long ahora = System.currentTimeMillis();
        if (tRealInicioPlanFijo == 0L) tRealInicioPlanFijo = ahora;
        String error = PlanInicialFijo.getError();
        if (error == null && PlanInicialFijo.completo()) {
            if (!planFijoListoImpreso) {
                planFijoListoImpreso = true;
                System.out.printf("[PlanInicialFijo] plan cargado en %.1f s: %s%n",
                        (ahora - tRealInicioPlanFijo) / 1000.0, PlanInicialFijo.estado());
            }
            return true;
        }
        if (error != null) {
            if (!planFijoAvisado) {
                planFijoAvisado = true;
                System.err.println("[PlanInicialFijo] ERROR: " + error + " LA SIMULACION NO ARRANCA.");
                btnPlayPause.setText("✖ ERROR EN EL PLAN INICIAL (ver consola)");
            }
            return false;
        }
        // El lector del CSV de schedules tarda unos segundos en leer el plan entero.
        if (ahora - tRealUltimaTraza >= 5_000L) {
            tRealUltimaTraza = ahora;
            System.out.println("[PlanInicialFijo] cargando: " + PlanInicialFijo.estado());
        }
        if (ahora - tRealInicioPlanFijo >= 60_000L && !planFijoAvisado) {
            planFijoAvisado = true;
            System.err.println("[PlanInicialFijo] AVISO: al minuto todavia no termina de cargar ("
                    + PlanInicialFijo.estado() + "). Si no avanza, revisa que el archivo sea de esta"
                    + " flota. La simulacion arranca recien cuando este todo cargado.");
        }
        return false;
    }

    /**
     * Firma de CONTENIDO del plan (FNV-1a de 64 bits sobre horas, maquina,
     * ubicacion y operacion de cada slot).
     *
     * Antes se comparaba el CONTEO de slots, y eso dejaba pasar cambios reales:
     * un Partial Repair que libera 8 slots seguido de una renegociacion que
     * agrega 8 da el mismo conteo, asi que la realidad NO se recalculaba y
     * quedaba desactualizada respecto del plan nuevo. Todo lo medido a partir
     * de ahi salia mal. Un right-shift tiene el mismo problema: mueve las horas
     * sin cambiar la cantidad.
     *
     * La firma detecta cualquier mutacion: alta, baja, desplazamiento o
     * reasignacion de pala/destino. Cuesta O(slots) por tick, igual que el
     * conteo que reemplaza.
     */
    private long firmaDelPlan(Map<String, Schedule> agendas) {
        long h = 0xcbf29ce484222325L;              // offset basis FNV-1a
        final long PRIMO = 0x100000001b3L;
        for (Map.Entry<String, Schedule> e : agendas.entrySet()) {
            h = (h ^ e.getKey().hashCode()) * PRIMO;
            Schedule sch = e.getValue();
            synchronized (sch) {
                for (Slot s : sch.getSlots()) {
                    h = (h ^ s.getHoraInicio()) * PRIMO;
                    h = (h ^ s.getHoraFin())    * PRIMO;
                    String maq = s.getMaquina();
                    h = (h ^ (maq == null ? 0 : maq.hashCode())) * PRIMO;
                    String ubi = s.getUbicacion();
                    h = (h ^ (ubi == null ? 0 : ubi.hashCode())) * PRIMO;
                    h = (h ^ (s.getOperacion() == null ? 0
                                                       : s.getOperacion().ordinal())) * PRIMO;
                }
            }
        }
        return h;
    }

    private void recalcularReal(Map<String, Schedule> agendas) {
        // Snapshot sincronizado: copiamos los slots de cada camión para que el
        // cálculo no choque con el LectorCSVSchedules, que los modifica en vivo.
        Map<String, Schedule> snapshot = new HashMap<>();
        for (Map.Entry<String, Schedule> e : agendas.entrySet()) {
            Schedule copia = new Schedule();
            synchronized (e.getValue()) {
                copia.getSlots().addAll(e.getValue().getSlots());
            }
            snapshot.put(e.getKey(), copia);
        }
        Map<String, List<RegistroSimulacion.SlotReal>> reales =
                CalculadorScheduleReal.calcularTodos(
                        snapshot,
                        RegistroSimulacion.getInstancia().getFactoresVelocidadCamion(),
                        gestor);
        for (Map.Entry<String, List<RegistroSimulacion.SlotReal>> e : reales.entrySet()) {
            RegistroSimulacion.getInstancia().setAgendaReal(e.getKey(), e.getValue());
        }
    }

    private void calcularLimitesMapaRobustos() {
        double sumLat = 0, sumLon = 0;
        int count = 0;
        for (Vertice v : grafoMina.vertexSet()) {
            if (v.getLatitud() != 0.0 && v.getLongitud() != 0.0) {
                sumLat += v.getLatitud(); sumLon += v.getLongitud(); count++;
            }
        }
        if (count == 0) return;
        double avgLat = sumLat / count, avgLon = sumLon / count, umbral = 0.05;
        minLat = Double.MAX_VALUE; maxLat = -Double.MAX_VALUE;
        minLon = Double.MAX_VALUE; maxLon = -Double.MAX_VALUE;
        for (Vertice v : grafoMina.vertexSet()) {
            if (Math.abs(v.getLatitud() - avgLat) > umbral
                    || Math.abs(v.getLongitud() - avgLon) > umbral) continue;
            if (v.getLatitud() < minLat) minLat = v.getLatitud();
            if (v.getLatitud() > maxLat) maxLat = v.getLatitud();
            if (v.getLongitud() < minLon) minLon = v.getLongitud();
            if (v.getLongitud() > maxLon) maxLon = v.getLongitud();
        }
        // Forzar que los puntos de operación (palas/destinos) entren en el mapa,
        // aunque estén lejos del grueso del grafo: el filtro de outliers de arriba
        // los excluiría (p. ej. #UB1), pero son justamente donde está la acción.
        expandirLimitesConPuntosDeOperacion();
        zoomFactor = 1.2; offsetX = 100; offsetY = 100;
    }

    /** Expande min/max lat-lon para incluir las ubicaciones de palas y destinos. */
    private void expandirLimitesConPuntosDeOperacion() {
        java.util.Set<String> ubics = new java.util.HashSet<>();
        ubics.addAll(RegistroSimulacion.getInstancia().getPalasPorUbicacion().keySet());
        ubics.addAll(RegistroSimulacion.getInstancia().getCapacidadDestinos().keySet());
        for (String u : ubics) {
            Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, u);
            if (v == null) continue;
            if (v.getLatitud() < minLat) minLat = v.getLatitud();
            if (v.getLatitud() > maxLat) maxLat = v.getLatitud();
            if (v.getLongitud() < minLon) minLon = v.getLongitud();
            if (v.getLongitud() > maxLon) maxLon = v.getLongitud();
        }
    }

    /**
     * Ajuste perezoso del encuadre: una vez que están registradas las palas y
     * destinos (lo hacen los agentes al arrancar, de forma asíncrona), recalcula
     * los límites incluyéndolos y elige zoom/offset para que todo el rango quepa
     * dentro del viewport del mapa (a la derecha del dashboard). Se ejecuta una
     * sola vez; después el usuario maneja la cámara con rueda y arrastre.
     */
    private void ajustarEncuadreSiNecesario(int width, int height) {
        if (encuadreAjustado) return;
        boolean hayPuntos = !RegistroSimulacion.getInstancia().getPalasPorUbicacion().isEmpty()
                || !RegistroSimulacion.getInstancia().getCapacidadDestinos().isEmpty();
        if (!hayPuntos || width <= 0 || height <= 0) return;
        if (minLon == Double.MAX_VALUE) return;

        expandirLimitesConPuntosDeOperacion();

        // Zona útil: a la derecha del dashboard, con márgenes; deja aire abajo
        // para las filas de cola que cuelgan de cada pala.
        int margen = 80;
        double anchoUtil = width - ANCHO_DASHBOARD - 2 * margen;
        double altoUtil = height - 2 * margen - 60; // 60: aire para colas/etiquetas
        if (anchoUtil < 50 || altoUtil < 50) return;

        // geoAPixeles escala normX por (width*zoom) y normY por (height*zoom).
        double zoomX = anchoUtil / width;
        double zoomY = altoUtil / height;
        zoomFactor = Math.min(zoomX, zoomY);

        // Centrar el bloque [0,1]x[0,1] dentro de la zona útil.
        double anchoMapa = width * zoomFactor;
        double altoMapa = height * zoomFactor;
        offsetX = (int) (ANCHO_DASHBOARD + margen + (anchoUtil - anchoMapa) / 2.0);
        // y = height - normY*altoMapa + offsetY ; centro vertical del bloque en height/2
        offsetY = (int) (height / 2.0 - (height - altoMapa / 2.0));

        encuadreAjustado = true;
    }

    private Point geoAPixeles(double lat, double lon, int width, int height) {
        int w = width, h = height;
        double normX = (lon - minLon) / (maxLon - minLon);
        double normY = (lat - minLat) / (maxLat - minLat);
        int x = (int) (normX * w * zoomFactor) + offsetX;
        int y = height - (int) (normY * h * zoomFactor) + offsetY;
        return new Point(x, y);
    }

    // ====================================================================
    //  Diálogo para agregar eventos a mano (clima o avería)
    // ====================================================================
    private void mostrarDialogoAgregarEvento() {
        JDialog dlg = new JDialog(this, "Agregar evento de simulación", true);
        dlg.setLayout(new GridLayout(0, 2, 8, 8));
        dlg.setSize(470, 320);
        dlg.setLocationRelativeTo(this);

        JComboBox<EventoSimulacion.TipoEvento> cmbTipo =
                new JComboBox<>(EventoSimulacion.TipoEvento.values());

        JComboBox<String> cmbTarget = new JComboBox<>();
        cmbTarget.setEnabled(false);
        JLabel lblTarget = new JLabel("Target:");
        lblTarget.setVisible(false);
        cmbTarget.setVisible(false);

        cmbTipo.addActionListener(ev -> {
            EventoSimulacion.TipoEvento t = (EventoSimulacion.TipoEvento) cmbTipo.getSelectedItem();
            cmbTarget.removeAllItems();
            if (t == EventoSimulacion.TipoEvento.FALLA_CAMION) {
                for (String c : new java.util.TreeSet<>(
                        RegistroSimulacion.getInstancia().getAgendasCamiones().keySet())) {
                    cmbTarget.addItem(c);
                }
                cmbTarget.setEnabled(true);
                lblTarget.setVisible(true); cmbTarget.setVisible(true);
                lblTarget.setText("Camión:");
            } else if (t == EventoSimulacion.TipoEvento.FALLA_PALA) {
                Map<String, java.util.List<String>> porUbic =
                        RegistroSimulacion.getInstancia().getPalasPorUbicacion();
                for (Map.Entry<String, java.util.List<String>> e : porUbic.entrySet()) {
                    String ubic = e.getKey();
                    String ubicCorta = ubic.length() > 20 ? ubic.substring(0, 20) : ubic;
                    for (String pala : e.getValue()) cmbTarget.addItem(pala + "  (" + ubicCorta + ")");
                }
                cmbTarget.setEnabled(true);
                lblTarget.setVisible(true); cmbTarget.setVisible(true);
                lblTarget.setText("Pala a averiar:");
            } else if (t == EventoSimulacion.TipoEvento.FALLA_CHANCADOR) {
                Map<String, Integer> caps = new java.util.TreeMap<>(
                        RegistroSimulacion.getInstancia().getCapacidadDestinos());
                for (Map.Entry<String, Integer> e : caps.entrySet()) {
                    cmbTarget.addItem(e.getKey() + "  (cap " + e.getValue() + ")");
                }
                cmbTarget.setEnabled(true);
                lblTarget.setVisible(true); cmbTarget.setVisible(true);
                lblTarget.setText("Chancador/destino:");
            } else {
                cmbTarget.setEnabled(false);
                lblTarget.setVisible(false); cmbTarget.setVisible(false);
            }
            dlg.revalidate(); dlg.repaint();
        });

        JTextField txtInicio = new JTextField(Utilidades.longToHHMMSS(tiempoSimulacionMs));
        JTextField txtDuracionMin = new JTextField("30");
        JSlider sldFactor = new JSlider(10, 100, 60);
        JLabel lblFactor = new JLabel("Factor: 0.60");
        sldFactor.addChangeListener(e -> lblFactor.setText(
                String.format(java.util.Locale.US, "Factor: %.2f", sldFactor.getValue() / 100.0)));

        dlg.add(new JLabel("Tipo de evento:"));         dlg.add(cmbTipo);
        dlg.add(lblTarget);                             dlg.add(cmbTarget);
        dlg.add(new JLabel("Hora inicio (HH:MM:SS):")); dlg.add(txtInicio);
        dlg.add(new JLabel("Duración (minutos):"));     dlg.add(txtDuracionMin);
        dlg.add(lblFactor);                             dlg.add(sldFactor);

        JButton ok = new JButton("Agregar");
        JButton cancel = new JButton("Cancelar");
        ok.addActionListener(ev -> {
            try {
                long inicioMs = parseHHMMSS(txtInicio.getText().trim());
                long durMs = Long.parseLong(txtDuracionMin.getText().trim()) * 60_000L;
                double factor = sldFactor.getValue() / 100.0;
                EventoSimulacion.TipoEvento tipo = (EventoSimulacion.TipoEvento) cmbTipo.getSelectedItem();
                EventoSimulacion nuevo;
                if (tipo == EventoSimulacion.TipoEvento.FALLA_CAMION) {
                    String target = (String) cmbTarget.getSelectedItem();
                    if (target == null) throw new IllegalArgumentException("seleccione un camión");
                    nuevo = EventoSimulacion.crearFallaCamion(target, inicioMs, durMs);
                } else if (tipo == EventoSimulacion.TipoEvento.FALLA_PALA) {
                    String raw = (String) cmbTarget.getSelectedItem();
                    if (raw == null) throw new IllegalArgumentException("seleccione una pala");
                    int sep = raw.indexOf("  ");
                    nuevo = EventoSimulacion.crearFallaPala((sep > 0) ? raw.substring(0, sep) : raw, inicioMs, durMs);
                } else if (tipo == EventoSimulacion.TipoEvento.FALLA_CHANCADOR) {
                    String raw = (String) cmbTarget.getSelectedItem();
                    if (raw == null) throw new IllegalArgumentException("seleccione un chancador/destino");
                    int sep = raw.indexOf("  ");
                    nuevo = EventoSimulacion.crearFallaChancador((sep > 0) ? raw.substring(0, sep) : raw, inicioMs, durMs);
                } else {
                    nuevo = new EventoSimulacion(tipo, inicioMs, durMs, factor);
                }
                GestorEventos.getInstancia().agregarEvento(nuevo);
                dlg.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg, "Datos inválidos: " + ex.getMessage());
            }
        });
        cancel.addActionListener(ev -> dlg.dispose());
        dlg.add(ok); dlg.add(cancel);

        cmbTipo.setSelectedIndex(0);
        dlg.setVisible(true);
    }

    private long parseHHMMSS(String s) {
        String[] p = s.split(":");
        long h = Long.parseLong(p[0]);
        long m = (p.length > 1) ? Long.parseLong(p[1]) : 0;
        long sec = (p.length > 2) ? Long.parseLong(p[2]) : 0;
        return (h * 3600 + m * 60 + sec) * 1000L;
    }

    // ====================================================================
    //  PANEL DEL MAPA
    // ====================================================================
    private class PanelMapa extends JPanel {
        private static final long serialVersionUID = 1L;
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Encuadre inicial: incluir todos los puntos de operación (una vez).
            ajustarEncuadreSiNecesario(getWidth(), getHeight());

            // CAPA 1: GRAFO (red de caminos)
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(60, 64, 72));
            for (DefaultWeightedEdge edge : grafoMina.edgeSet()) {
                Vertice o = grafoMina.getEdgeSource(edge);
                Vertice d = grafoMina.getEdgeTarget(edge);
                if (o.getLatitud() > maxLat || o.getLatitud() < minLat) continue;
                Point p1 = geoAPixeles(o.getLatitud(), o.getLongitud(), getWidth(), getHeight());
                Point p2 = geoAPixeles(d.getLatitud(), d.getLongitud(), getWidth(), getHeight());
                g2.drawLine(p1.x, p1.y, p2.x, p2.y);
            }

            // CAPA 2: marcadores de palas y destinos
            dibujarPalas(g2);
            dibujarDestinos(g2);

            // CAPA 3: CAMIONES (calculamos info de todos primero para resolver colas)
            Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
            Map<String, InfoEstado> infos = new HashMap<>();
            for (String camion : agendas.keySet()) {
                infos.put(camion, calcularPosicionCamion(camion, tiempoSimulacionMs));
            }
            distribuirCamionesEnPuntos(infos);
            for (Map.Entry<String, InfoEstado> e : infos.entrySet()) {
                dibujarCamion(g2, e.getKey(), e.getValue());
            }

            // CAPA 3b: indicadores de avería de instalación (pala/chancador) por
            // ENCIMA de los camiones, para que el rojo no quede tapado.
            if (conIncertidumbre) dibujarAveriasInstalacion(g2);

            // CAPA 4: DASHBOARD CON SCROLL (clip para que no se derrame sobre el mapa)
            java.awt.Shape clipAnterior = g2.getClip();
            g2.setClip(0, 0, ANCHO_DASHBOARD, getHeight());
            dibujarDashboard(g2, agendas, infos);
            g2.setClip(clipAnterior);

            // CAPA 5: banner de evento ancho en la parte superior (solo con incertidumbre)
            if (conIncertidumbre) dibujarBannerEvento(g2);
        }

        /**
         * Banner ancho en la parte superior que cubre todo el ancho de la ventana.
         * Solo se muestra cuando hay un evento activo (clima o, en la próxima capa,
         * averías). Muestra tipo, ventana temporal, impacto, barra de progreso del
         * evento y tiempo restante.
         */
        private void dibujarBannerEvento(Graphics2D g2) {
            EventoSimulacion activo = gestor.getEventoActivo(tiempoSimulacionMs);
            if (activo == null) return;
            // El banner arranca a la derecha del dashboard, para no taparlo.
            int x = ANCHO_DASHBOARD + 15, y = 10;
            int ancho = getWidth() - x - 15;
            int alto = ALTO_BANNER_EVENTO;

            Color base = activo.getTipo().getColor();
            g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 220));
            g2.fillRoundRect(x, y, ancho, alto, 12, 12);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(x, y, ancho, alto, 12, 12);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 18));
            String titulo = "\u26A0 EVENTO ACTIVO: " + activo.getTipo().getDescripcion();
            if (activo.getTargetId() != null) titulo += "  [" + activo.getTargetId() + "]";
            g2.drawString(titulo, x + 15, y + 23);

            g2.setFont(new Font("Consolas", Font.PLAIN, 12));
            String detalle;
            if (activo.getTipo().esFalla()) {
                detalle = String.format("Inicio: %s   Fin: %s   Agente detenido",
                        Utilidades.longToHHMMSS(activo.getHoraInicioMs()),
                        Utilidades.longToHHMMSS(activo.getHoraFinMs()));
            } else {
                detalle = String.format(java.util.Locale.US,
                        "Inicio: %s   Fin: %s   Impacto en velocidad: x%.2f",
                        Utilidades.longToHHMMSS(activo.getHoraInicioMs()),
                        Utilidades.longToHHMMSS(activo.getHoraFinMs()),
                        activo.getFactorVelocidad());
            }
            g2.drawString(detalle, x + 15, y + 40);

            int barX = x + 15, barY = y + 47, barW = ancho - 30, barH = 8;
            g2.setColor(new Color(0, 0, 0, 120));
            g2.fillRoundRect(barX, barY, barW, barH, 4, 4);
            double progreso = Math.max(0, Math.min(1, activo.progresoEn(tiempoSimulacionMs)));
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(barX, barY, (int) (barW * progreso), barH, 4, 4);

            long restante = activo.getHoraFinMs() - tiempoSimulacionMs;
            g2.setFont(new Font("Consolas", Font.BOLD, 11));
            g2.drawString("Restante: " + Utilidades.longToHHMMSS(Math.max(0, restante)),
                    barX + barW - 140, barY - 2);
        }

        /**
         * Dibuja, por ENCIMA de los camiones, un indicador prominente para cada
         * pala o destino averiado: un anillo rojo grande y un "⚠", para que la
         * avería de una instalación se vea aunque haya camiones sobre el punto.
         */
        private void dibujarAveriasInstalacion(Graphics2D g2) {
            // Palas averiadas (en su ranura dentro del punto)
            Map<String, java.util.List<String>> palasPorUbic =
                    RegistroSimulacion.getInstancia().getPalasPorUbicacion();
            for (Map.Entry<String, java.util.List<String>> e : palasPorUbic.entrySet()) {
                Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, e.getKey());
                if (v == null) continue;
                Point p = geoAPixeles(v.getLatitud(), v.getLongitud(), getWidth(), getHeight());
                java.util.List<String> palas = e.getValue();
                int n = palas.size();
                for (int i = 0; i < n; i++) {
                    if (!gestor.estaPalaEnFalla(palas.get(i), tiempoSimulacionMs)) continue;
                    int px = p.x + (int) Math.round((i - (n - 1) / 2.0) * SEP_RANURA);
                    marcarAveria(g2, px, p.y, palas.get(i));
                }
            }
            // Destinos averiados
            Map<String, Integer> caps = RegistroSimulacion.getInstancia().getCapacidadDestinos();
            for (String dest : caps.keySet()) {
                if (!gestor.estaChancadorEnFalla(dest, tiempoSimulacionMs)) continue;
                Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, dest);
                if (v == null) continue;
                Point p = geoAPixeles(v.getLatitud(), v.getLongitud(), getWidth(), getHeight());
                marcarAveria(g2, p.x, p.y, null);
            }
        }

        private void marcarAveria(Graphics2D g2, int cx, int cy, String etiqueta) {
            g2.setColor(new Color(220, 20, 60));
            g2.setStroke(new BasicStroke(3f));
            g2.drawOval(cx - 18, cy - 18, 36, 36);
            g2.setColor(new Color(220, 20, 60, 60));
            g2.fillOval(cx - 18, cy - 18, 36, 36);
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(255, 80, 110));
            g2.setFont(new Font("Arial", Font.BOLD, 18));
            g2.drawString("\u26A0", cx - 7, cy + 6);
        }

        private void dibujarPalas(Graphics2D g2) {
            Map<String, java.util.List<String>> palasPorUbic =
                    RegistroSimulacion.getInstancia().getPalasPorUbicacion();

            // Fallback: si todavía no se registraron palas, usamos las ubicaciones
            // de carga detectadas en los schedules.
            if (palasPorUbic.isEmpty()) {
                for (String ubic : obtenerUbicacionesPalas()) {
                    palasPorUbic.put(ubic, java.util.Collections.singletonList("?"));
                }
            }

            for (Map.Entry<String, java.util.List<String>> entry : palasPorUbic.entrySet()) {
                String ubic = entry.getKey();
                java.util.List<String> palasAqui = entry.getValue();
                Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, ubic);
                if (v == null) continue;
                Point p = geoAPixeles(v.getLatitud(), v.getLongitud(), getWidth(), getHeight());

                // Dibujamos una pala por cada una que opera en el punto, alineadas
                // con las ranuras donde se ubican los camiones (misma separación).
                int n = palasAqui.size();
                int sep = SEP_RANURA;
                int x0 = p.x - (n - 1) * sep / 2;
                for (int i = 0; i < n; i++) {
                    int px = x0 + i * sep;
                    String nombrePala = palasAqui.get(i);
                    boolean averiada = conIncertidumbre
                            && gestor.estaPalaEnFalla(nombrePala, tiempoSimulacionMs);
                    if (averiada) {
                        // Pala fuera de servicio: rojo fuerte con borde grueso.
                        g2.setColor(new Color(220, 20, 60));
                        g2.fillRect(px - 6, p.y - 6, 12, 12);
                        g2.setColor(new Color(255, 120, 140));
                        g2.setStroke(new BasicStroke(2.2f));
                        g2.drawRect(px - 7, p.y - 7, 14, 14);
                        g2.setStroke(new BasicStroke(1f));
                    } else {
                        g2.setColor(new Color(229, 192, 123));
                        g2.fillRect(px - 5, p.y - 5, 10, 10);
                        g2.setColor(new Color(120, 100, 60));
                        g2.drawRect(px - 5, p.y - 5, 10, 10);
                    }
                }

                // Etiqueta arriba: cantidad de palas (+ aviso si alguna averiada)
                int averiadas = 0;
                if (conIncertidumbre)
                    for (String np : palasAqui)
                        if (gestor.estaPalaEnFalla(np, tiempoSimulacionMs)) averiadas++;
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 12));
                String titulo = n + (n == 1 ? " pala" : " palas");
                if (averiadas > 0) titulo += "  \u26A0" + averiadas + " averiada" + (averiadas > 1 ? "s" : "");
                g2.drawString(titulo, x0, p.y - 16);

                // Etiqueta de estado ARRIBA (la cola ahora se ve abajo con camiones).
                int[] estado = contarCargandoYCola(ubic);
                int cargando = estado[0], enCola = estado[1];
                g2.setFont(new Font("Consolas", Font.BOLD, 11));
                g2.setColor(new Color(152, 195, 121));
                g2.drawString("Cargando: " + cargando + "/" + n, x0, p.y - 30);
                if (enCola > 0) {
                    g2.setColor(new Color(231, 197, 100));
                    g2.drawString("  En cola: " + enCola, x0 + 95, p.y - 30);
                }
            }
        }

        /**
         * Cuenta, en una ubicación de carga y para el instante actual, cuántos
         * camiones están CARGANDO y cuántos EN COLA (llegaron pero su carga aún
         * no empieza). Recorre los schedules PLANIFICADOS. En Capa 1 sin
         * incertidumbre la cola debería ser siempre 0 (el plan ya respeta la
         * capacidad). Si aparece cola, es señal de un problema.
         */
        private int[] contarCargandoYCola(String ubic) {
            if (conIncertidumbre) return contarEnPuntoReal(ubic, Slot.enum_operacion.CARGA);
            int cargando = 0, enCola = 0;
            Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
            for (Schedule sch : agendas.values()) {
                List<Slot> slots;
                synchronized (sch) { slots = new ArrayList<>(sch.getSlots()); }
                Slot anterior = null;
                for (Slot s : slots) {
                    boolean esCargaAqui = s.getOperacion() == Slot.enum_operacion.CARGA
                            && s.getUbicacion().equals(ubic);
                    if (esCargaAqui) {
                        if (tiempoSimulacionMs >= s.getHoraInicio()
                                && tiempoSimulacionMs <= s.getHoraFin()) { cargando++; break; }
                        if (anterior != null && anterior.getHoraFin() <= tiempoSimulacionMs
                                && tiempoSimulacionMs < s.getHoraInicio()) { enCola++; break; }
                    }
                    if (s.getHoraInicio() > tiempoSimulacionMs) break;
                    anterior = s;
                }
            }
            return new int[]{cargando, enCola};
        }

        /**
         * Cuenta atendidos y en cola en un punto, usando el schedule REAL (con
         * colas FIFO ya resueltas por CalculadorScheduleReal). Sirve tanto para
         * CARGA (palas) como para DESCARGA (destinos).
         */
        private int[] contarEnPuntoReal(String ubic, Slot.enum_operacion op) {
            int atendiendo = 0, enCola = 0;
            for (List<RegistroSimulacion.SlotReal> reales
                    : RegistroSimulacion.getInstancia().getAgendasReales().values()) {
                RegistroSimulacion.SlotReal anterior = null;
                for (RegistroSimulacion.SlotReal sr : reales) {
                    Slot s = sr.slotPlanificado;
                    boolean aqui = s.getOperacion() == op && s.getUbicacion().equals(ubic);
                    if (aqui) {
                        if (tiempoSimulacionMs >= sr.inicioReal
                                && tiempoSimulacionMs <= sr.finReal) { atendiendo++; break; }
                        if (anterior != null && anterior.finReal <= tiempoSimulacionMs
                                && tiempoSimulacionMs < sr.inicioReal) { enCola++; break; }
                    }
                    if (sr.inicioReal > tiempoSimulacionMs) break;
                    anterior = sr;
                }
            }
            return new int[]{atendiendo, enCola};
        }

        /**
         * Dibuja los puntos de DESCARGA (chancadores y botaderos). Espejo visual
         * de dibujarPalas: un rombo por destino, azul si es chancador (capacidad
         * 1), gris si es botadero (capacidad alta). Debajo, descargando / en cola.
         */
        private void dibujarDestinos(Graphics2D g2) {
            Map<String, Integer> caps = RegistroSimulacion.getInstancia().getCapacidadDestinos();
            if (caps.isEmpty()) return;

            for (Map.Entry<String, Integer> entry : caps.entrySet()) {
                String dest = entry.getKey();
                int capacidad = entry.getValue();
                Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, dest);
                if (v == null) continue;
                Point p = geoAPixeles(v.getLatitud(), v.getLongitud(), getWidth(), getHeight());

                boolean esChancador = capacidad <= 1;
                boolean averiado = conIncertidumbre
                        && gestor.estaChancadorEnFalla(dest, tiempoSimulacionMs);

                // Marcador en forma de rombo (rojo fuerte si está averiado).
                Color base = averiado ? new Color(220, 20, 60)
                        : (esChancador ? new Color(97, 175, 239) : new Color(130, 130, 140));
                int r = averiado ? 11 : 8;
                int[] xs = {p.x, p.x + r, p.x, p.x - r};
                int[] ys = {p.y - r, p.y, p.y + r, p.y};
                g2.setColor(base);
                g2.fillPolygon(xs, ys, 4);
                if (averiado) {
                    g2.setColor(new Color(255, 120, 140));
                    g2.setStroke(new BasicStroke(2.2f));
                    g2.drawPolygon(xs, ys, 4);
                    g2.setStroke(new BasicStroke(1f));
                } else {
                    g2.setColor(new Color(40, 50, 70));
                    g2.drawPolygon(xs, ys, 4);
                }

                // Etiqueta arriba: tipo + capacidad (+ aviso si averiado)
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 11));
                String tipoTxt = esChancador ? "Chancador" : "Botadero";
                String etiqueta = tipoTxt + " (cap " + capacidad + ")";
                if (averiado) etiqueta += "  \u26A0 AVERIADO";
                g2.drawString(etiqueta, p.x + 12, p.y - 6);

                // Etiqueta abajo: descargando / en cola
                int[] estado = contarDescargandoYCola(dest);
                int descargando = estado[0], enCola = estado[1];
                g2.setFont(new Font("Consolas", Font.BOLD, 11));
                g2.setColor(new Color(152, 195, 121));
                g2.drawString("Descargando: " + descargando
                        + "/" + (capacidad >= 99 ? "\u221E" : capacidad), p.x + 12, p.y + 8);
                if (enCola > 0) {
                    g2.setColor(new Color(231, 197, 100));
                    g2.drawString("En cola: " + enCola, p.x + 12, p.y + 22);
                }
            }
        }

        /** Análogo a contarCargandoYCola pero para puntos de descarga. */
        private int[] contarDescargandoYCola(String dest) {
            if (conIncertidumbre) return contarEnPuntoReal(dest, Slot.enum_operacion.DESCARGA);
            int descargando = 0, enCola = 0;
            Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
            for (Schedule sch : agendas.values()) {
                List<Slot> slots;
                synchronized (sch) { slots = new ArrayList<>(sch.getSlots()); }
                Slot anterior = null;
                for (Slot s : slots) {
                    boolean esDescargaAqui = s.getOperacion() == Slot.enum_operacion.DESCARGA
                            && s.getUbicacion().equals(dest);
                    if (esDescargaAqui) {
                        if (tiempoSimulacionMs >= s.getHoraInicio()
                                && tiempoSimulacionMs <= s.getHoraFin()) { descargando++; break; }
                        if (anterior != null && anterior.getHoraFin() <= tiempoSimulacionMs
                                && tiempoSimulacionMs < s.getHoraInicio()) { enCola++; break; }
                    }
                    if (s.getHoraInicio() > tiempoSimulacionMs) break;
                    anterior = s;
                }
            }
            return new int[]{descargando, enCola};
        }

        /**
         * Para los camiones "en cola" en un punto, calcula su orden FIFO según
         * la hora a la que llegaron. El primero queda al lado del punto; los
         * siguientes se apilan hacia abajo.
         */
        // Separación horizontal entre palas/ranuras y vertical entre filas de cola.
        // dibujarPalas usa la misma SEP_RANURA para que el camión quede sobre su pala.
        private static final int SEP_RANURA = 34;
        private static final int FILA_COLA = 28;

        /**
         * Distribuye visualmente los camiones que están en un punto, para que no se
         * amontonen: los que ATIENDEN (cargando/descargando) se reparten en las N
         * ranuras del punto (una por pala/puesto), y los que están EN COLA se ubican
         * en filas debajo, repartidos por ranura (cola por pala). Calcula offX/offY.
         */
        private void distribuirCamionesEnPuntos(Map<String, InfoEstado> infos) {
            Map<String, List<Map.Entry<String, InfoEstado>>> atendiendo = new HashMap<>();
            Map<String, List<Map.Entry<String, InfoEstado>>> enCola = new HashMap<>();
            for (Map.Entry<String, InfoEstado> e : infos.entrySet()) {
                InfoEstado i = e.getValue();
                if (i.atendiendoEnPunto != null)
                    atendiendo.computeIfAbsent(i.atendiendoEnPunto, k -> new ArrayList<>()).add(e);
                else if (i.enColaEnPala != null)
                    enCola.computeIfAbsent(i.enColaEnPala, k -> new ArrayList<>()).add(e);
            }

            Map<String, java.util.List<String>> palasPorUbic =
                    RegistroSimulacion.getInstancia().getPalasPorUbicacion();
            Map<String, Integer> caps = RegistroSimulacion.getInstancia().getCapacidadDestinos();

            java.util.Set<String> puntos = new java.util.HashSet<>();
            puntos.addAll(atendiendo.keySet());
            puntos.addAll(enCola.keySet());

            for (String punto : puntos) {
                List<Map.Entry<String, InfoEstado>> at =
                        atendiendo.getOrDefault(punto, new ArrayList<>());
                List<Map.Entry<String, InfoEstado>> cola =
                        enCola.getOrDefault(punto, new ArrayList<>());

                boolean esPuntoCarga = palasPorUbic.containsKey(punto);

                if (esPuntoCarga) {
                    // ---- PUNTO DE CARGA: cada camión va a SU pala asignada ----
                    java.util.List<String> palas = palasPorUbic.get(punto);
                    int n = Math.max(1, palas.size());

                    // Atendiendo: ubicar cada camión en la ranura de SU pala.
                    for (Map.Entry<String, InfoEstado> e : at) {
                        int ranura = indiceDePala(palas, e.getValue().palaAsignada);
                        e.getValue().offX = (int) Math.round((ranura - (n - 1) / 2.0) * SEP_RANURA);
                        e.getValue().offY = 0;
                    }

                    // En cola: agrupar por pala asignada; apilar por orden de llegada
                    // detrás de ESA pala (cola por pala).
                    Map<String, List<Map.Entry<String, InfoEstado>>> colaPorPala = new HashMap<>();
                    for (Map.Entry<String, InfoEstado> e : cola) {
                        String p = e.getValue().palaAsignada;
                        colaPorPala.computeIfAbsent(p == null ? "?" : p, k -> new ArrayList<>()).add(e);
                    }
                    for (Map.Entry<String, List<Map.Entry<String, InfoEstado>>> ce : colaPorPala.entrySet()) {
                        int ranura = indiceDePala(palas, ce.getKey());
                        List<Map.Entry<String, InfoEstado>> fila = ce.getValue();
                        fila.sort(Comparator.comparingLong(en -> en.getValue().horaLlegadaCola));
                        for (int k = 0; k < fila.size(); k++) {
                            InfoEstado info = fila.get(k).getValue();
                            info.offX = (int) Math.round((ranura - (n - 1) / 2.0) * SEP_RANURA);
                            info.offY = (k + 1) * FILA_COLA;
                            info.posicionEnCola = k; // # dentro de la cola de su pala
                        }
                    }
                } else {
                    // ---- DESTINO (descarga): pool de N puestos, round-robin ----
                    int n;
                    if (caps.containsKey(punto)) {
                        int c = caps.get(punto);
                        n = (c >= 99) ? Math.max(1, at.size()) : c;
                    } else {
                        n = 1;
                    }
                    if (n < 1) n = 1;
                    at.sort(Comparator.comparing(Map.Entry::getKey));
                    for (int i = 0; i < at.size(); i++) {
                        int ranura = i % n;
                        InfoEstado info = at.get(i).getValue();
                        info.offX = (int) Math.round((ranura - (n - 1) / 2.0) * SEP_RANURA);
                        info.offY = 0;
                    }
                    cola.sort(Comparator.comparingLong(en -> en.getValue().horaLlegadaCola));
                    for (int k = 0; k < cola.size(); k++) {
                        int ranura = k % n;
                        int fila = k / n + 1;
                        InfoEstado info = cola.get(k).getValue();
                        info.offX = (int) Math.round((ranura - (n - 1) / 2.0) * SEP_RANURA);
                        info.offY = fila * FILA_COLA;
                        info.posicionEnCola = k;
                    }
                }
            }
        }

        /** Índice de una pala dentro de la lista del punto (0 si no se encuentra). */
        private int indiceDePala(java.util.List<String> palas, String pala) {
            if (palas == null || pala == null) return 0;
            int idx = palas.indexOf(pala);
            return idx < 0 ? 0 : idx;
        }

        private void dibujarCamion(Graphics2D g2, String nombre, InfoEstado info) {
            if (!info.tienePosicionGeografica) return;
            Point pos = geoAPixeles(info.latitud, info.longitud, getWidth(), getHeight());
            int cx = pos.x + info.offX;
            int cy = pos.y + info.offY;

            // Si el camión está averiado, se pinta de rojo (cuerpo + halo) y se le
            // agrega un borde rojo grueso, para localizar la avería en el mapa.
            Color cuerpo = info.enFalla ? new Color(220, 20, 60) : info.color;

            // Halo
            g2.setColor(new Color(cuerpo.getRed(), cuerpo.getGreen(), cuerpo.getBlue(), 50));
            g2.fillOval(cx - 15, cy - 15, 30, 30);
            // Cuerpo
            g2.setColor(cuerpo);
            g2.fillOval(cx - 8, cy - 8, 16, 16);
            // Indicador de avería: borde rojo grueso
            if (info.enFalla) {
                g2.setColor(new Color(220, 20, 60));
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawOval(cx - 12, cy - 12, 24, 24);
            }
            // Etiqueta
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 12));
            g2.drawString(nombre + (info.enFalla ? " \u26A0" : ""), cx + 12, cy + 4);
            if (info.posicionEnCola >= 0) {
                g2.setColor(new Color(255, 200, 100));
                g2.setFont(new Font("Arial", Font.PLAIN, 10));
                g2.drawString("#" + (info.posicionEnCola + 1), cx - 16, cy + 4);
            }
        }

        private void dibujarDashboard(Graphics2D g2, Map<String, Schedule> agendas,
                                      Map<String, InfoEstado> infos) {
            final int HEADER_H = 50;
            final int PX_CAMION = 55;
            final int PX_EVENTO = 18;

            // Fondo + borde
            g2.setColor(new Color(33, 37, 43, 255));
            g2.fillRect(0, 0, ANCHO_DASHBOARD, getHeight());
            g2.setColor(new Color(97, 175, 239));
            g2.drawLine(ANCHO_DASHBOARD, 0, ANCHO_DASHBOARD, getHeight());

            // Tamaño total del contenido (camiones + sección de eventos programados)
            int numCamiones = agendas.size();
            int numEventos = conIncertidumbre ? GestorEventos.getInstancia().getEventos().size() : 0;
            int altoEventos = conIncertidumbre ? (38 + numEventos * PX_EVENTO) : 0;
            int altoContenido = numCamiones * PX_CAMION + 30 + altoEventos;
            int areaVisible = getHeight() - HEADER_H;
            int maxScroll = Math.max(0, altoContenido - areaVisible);
            dashboardScrollY = Math.max(0, Math.min(dashboardScrollY, maxScroll));

            // ===== CONTENIDO SCROLLEABLE =====
            // Lo recortamos a la zona bajo el header para que, al hacer scroll,
            // nunca se dibuje por encima del título (que queda fijo arriba).
            java.awt.Shape clipPrevio = g2.getClip();
            g2.clipRect(0, HEADER_H, ANCHO_DASHBOARD, getHeight() - HEADER_H);
            g2.translate(0, HEADER_H - dashboardScrollY);
            int yPos = 10;

            List<String> nombres = new ArrayList<>(agendas.keySet());
            nombres.sort(Comparator.naturalOrder());

            for (String nombre : nombres) {
                InfoEstado info = infos.get(nombre);
                if (info == null) continue;
                double velCargado = RegistroSimulacion.getInstancia().getVelocidadCargadoCamion(nombre);

                // Fila 1: nombre + velocidad
                g2.setFont(new Font("Arial", Font.BOLD, 15));
                g2.setColor(info.color);
                g2.drawString("\u25A0 " + nombre, 20, yPos + 14);

                g2.setFont(new Font("Consolas", Font.PLAIN, 11));
                g2.setColor(new Color(180, 180, 180));
                if (velCargado > 0) {
                    double vel = conIncertidumbre
                            ? velCargado * RegistroSimulacion.getInstancia().getFactorVelocidadCamion(nombre)
                            : velCargado;
                    g2.drawString(String.format(java.util.Locale.US, "Vel: %.1f km/h", vel),
                            130, yPos + 14);
                }

                // Estado de puntualidad (solo con incertidumbre; en baseline real==plan).
                if (conIncertidumbre) {
                    String estado; Color colorR;
                    if (info.enFalla) {
                        estado = "AVERIADO"; colorR = new Color(220, 20, 60);
                    } else if (info.retrasoAcumuladoMs > 60_000) {
                        estado = "Retraso: " + Utilidades.longToHHMMSS(info.retrasoAcumuladoMs);
                        colorR = new Color(224, 108, 117); // rojo
                    } else if (info.retrasoAcumuladoMs > 0) {
                        estado = "Retraso: " + Utilidades.longToHHMMSS(info.retrasoAcumuladoMs);
                        colorR = new Color(229, 192, 123); // amarillo
                    } else {
                        estado = "A tiempo"; colorR = new Color(152, 195, 121); // verde
                    }
                    g2.setFont(new Font("Consolas", Font.BOLD, 12));
                    g2.setColor(colorR);
                    g2.drawString(estado, 270, yPos + 14);
                }

                // Fila 2: descripción de la acción
                g2.setFont(new Font("Consolas", Font.PLAIN, 12));
                g2.setColor(Color.LIGHT_GRAY);
                g2.drawString(info.accion, 30, yPos + 30);

                // Barra de progreso
                g2.setColor(new Color(60, 60, 60));
                g2.fillRect(20, yPos + 36, ANCHO_DASHBOARD - 40, 8);
                g2.setColor(info.color);
                g2.fillRect(20, yPos + 36, (int) ((ANCHO_DASHBOARD - 40) * info.progreso), 8);

                yPos += PX_CAMION;
            }

            // ===== Sección de EVENTOS PROGRAMADOS (al final, como antes) =====
            if (conIncertidumbre) {
                yPos += 8;
                g2.setColor(new Color(97, 175, 239));
                g2.setFont(new Font("Arial", Font.BOLD, 14));
                g2.drawString("EVENTOS PROGRAMADOS", 20, yPos + 14);
                g2.drawLine(20, yPos + 18, ANCHO_DASHBOARD - 25, yPos + 18);
                yPos += 30;

                g2.setFont(new Font("Consolas", Font.PLAIN, 11));
                for (EventoSimulacion ev : GestorEventos.getInstancia().getEventos()) {
                    boolean activo = ev.estaActivoEn(tiempoSimulacionMs);
                    g2.setColor(activo ? ev.getTipo().getColor() : new Color(120, 120, 120));
                    g2.drawString((activo ? "\u25CF " : "\u25CB ") + ev.toString(), 20, yPos);
                    yPos += PX_EVENTO;
                }
            }

            g2.translate(0, dashboardScrollY - HEADER_H);
            g2.setClip(clipPrevio);

            // ===== HEADER FIJO (opaco, dibujado al final → siempre encima) =====
            g2.setColor(new Color(33, 37, 43, 255));
            g2.fillRect(0, 0, ANCHO_DASHBOARD, HEADER_H);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 20));
            g2.drawString("TELEMETRÍA DE CAMIONES", 20, 30);
            g2.setColor(new Color(97, 175, 239));
            g2.drawLine(20, 42, ANCHO_DASHBOARD - 25, 42);

            // ===== Barra de scroll lateral =====
            if (maxScroll > 0) {
                int barX = ANCHO_DASHBOARD - 7;
                double thumb = (double) areaVisible / altoContenido;
                int thumbH = Math.max(20, (int) (areaVisible * thumb));
                int thumbY = HEADER_H + (int) ((areaVisible - thumbH) * (double) dashboardScrollY / maxScroll);
                g2.setColor(new Color(55, 60, 68));
                g2.fillRoundRect(barX - 3, HEADER_H, 6, areaVisible, 4, 4);
                g2.setColor(new Color(97, 175, 239, 180));
                g2.fillRoundRect(barX - 3, thumbY, 6, thumbH, 4, 4);
                g2.setColor(new Color(97, 175, 239, 120));
                g2.setFont(new Font("Arial", Font.PLAIN, 9));
                g2.drawString("\u25B2 scroll \u25BC", barX - 28, HEADER_H + 12);
            }
        }

        /** Ubicaciones donde hay cargas planificadas (fallback si no hay palas registradas). */
        private java.util.Set<String> obtenerUbicacionesPalas() {
            java.util.Set<String> ubics = new java.util.TreeSet<>();
            for (Schedule sch : RegistroSimulacion.getInstancia().getAgendasCamiones().values()) {
                List<Slot> slots;
                synchronized (sch) { slots = new ArrayList<>(sch.getSlots()); }
                for (Slot s : slots) {
                    if (s.getOperacion() == Slot.enum_operacion.CARGA) ubics.add(s.getUbicacion());
                }
            }
            return ubics;
        }

        // ============================================================
        //  POSICIÓN DEL CAMIÓN — sigue el schedule PLANIFICADO
        // ============================================================
        private InfoEstado calcularPosicionCamion(String nombreCamion, long tActual) {
            if (conIncertidumbre) return calcularPosicionCamionReal(nombreCamion, tActual);

            InfoEstado info = new InfoEstado();
            String ubicacionInicial = RegistroSimulacion.getInstancia().getUbicacionInicial(nombreCamion);
            Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(nombreCamion);

            List<Slot> slots;
            if (sch == null) slots = new ArrayList<>();
            else synchronized (sch) { slots = new ArrayList<>(sch.getSlots()); }

            if (slots.isEmpty()) {
                return generarInfoInactivo(info, ubicacionInicial, null, null);
            }

            Slot anterior = null;
            for (Slot s : slots) {
                // ¿Estamos DENTRO de este slot?
                if (tActual >= s.getHoraInicio() && tActual <= s.getHoraFin()) {
                    return llenarInfoDesdeSlot(info, s, anterior, ubicacionInicial, tActual);
                }
                // ¿Estamos en un HUECO antes de este slot?
                if (s.getHoraInicio() > tActual) {
                    if (anterior != null && anterior.getHoraFin() <= tActual
                            && s.getOperacion() == Slot.enum_operacion.CARGA) {
                        return llenarInfoEnEspera(info, s, anterior, true);
                    }
                    if (anterior != null && anterior.getHoraFin() <= tActual
                            && s.getOperacion() == Slot.enum_operacion.DESCARGA) {
                        return llenarInfoEnEspera(info, s, anterior, false);
                    }
                    // Hueco idle antes de un viaje: detenido donde terminó lo anterior.
                    if (anterior != null) {
                        info.color = new Color(150, 150, 150);
                        info.progreso = 0.0;
                        info.accion = "En espera (detenido en " + abreviar(anterior.getUbicacion()) + ")";
                        Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, anterior.getUbicacion());
                        if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
                        return info;
                    }
                    break; // todavía en su punto inicial
                }
                anterior = s;
            }
            return generarInfoInactivo(info, ubicacionInicial, slots, anterior);
        }

        /**
         * El camión llegó a un punto de carga o descarga pero la operación aún
         * no empieza (cola). En Capa 1 sin incertidumbre esto no debería pasar,
         * pero mantenemos el mecanismo de apilamiento para que sea visible si
         * llegara a ocurrir.
         */
        private InfoEstado llenarInfoEnEspera(InfoEstado info, Slot s, Slot anterior, boolean esCarga) {
            String ubic = s.getUbicacion();
            Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, ubic);
            if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
            info.enColaEnPala = ubic;
            info.horaLlegadaCola = anterior.getHoraFin();
            info.color = new Color(231, 197, 100);
            info.accion = (esCarga ? "En cola - carga " : "En cola - descarga ")
                    + Utilidades.longToHHMMSS(s.getHoraInicio());
            return info;
        }

        private InfoEstado llenarInfoDesdeSlot(InfoEstado info, Slot s, Slot anterior,
                                               String ubicacionInicial, long tActual) {
            long dur = s.getHoraFin() - s.getHoraInicio();
            info.progreso = (dur <= 0) ? 1.0 : (double) (tActual - s.getHoraInicio()) / dur;
            info.progreso = Math.max(0.0, Math.min(1.0, info.progreso));

            if (s.getOperacion() == Slot.enum_operacion.CARGA) {
                info.color = new Color(224, 108, 117);
                info.accion = "Cargando en: " + abreviar(s.getUbicacion());
                info.atendiendoEnPunto = s.getUbicacion();
            } else if (s.getOperacion() == Slot.enum_operacion.DESCARGA) {
                info.color = new Color(152, 195, 121);
                info.accion = "Descargando en: " + abreviar(s.getUbicacion());
                info.atendiendoEnPunto = s.getUbicacion();
            } else if (s.getOperacion() == Slot.enum_operacion.VIAJE_VACIO) {
                info.color = new Color(229, 192, 123);
                info.accion = "Viaje Vacío -> " + abreviar(s.getUbicacion());
            } else {
                info.color = new Color(97, 175, 239);
                info.accion = "Viaje Cargado -> " + abreviar(s.getUbicacion());
            }

            if (s.getOperacion() == Slot.enum_operacion.CARGA
                    || s.getOperacion() == Slot.enum_operacion.DESCARGA) {
                Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, s.getUbicacion());
                if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
            } else {
                String idOrigen = (anterior == null) ? ubicacionInicial : anterior.getUbicacion();
                Vertice vOrigen = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, idOrigen);
                Vertice vDestino = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, s.getUbicacion());
                if (vOrigen != null && vDestino != null) {
                    ubicarSobreRuta(info, vOrigen, vDestino, info.progreso);
                }
            }
            return info;
        }

        private InfoEstado generarInfoInactivo(InfoEstado info, String ubicacionInicial,
                                               List<Slot> slots, Slot anterior) {
            info.color = Color.GRAY;
            info.progreso = 0.0;
            info.accion = "Inactivo / Esperando asignación";
            Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, ubicacionInicial);
            if (slots != null && !slots.isEmpty()
                    && tiempoSimulacionMs > slots.get(slots.size() - 1).getHoraFin()) {
                v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina,
                        slots.get(slots.size() - 1).getUbicacion());
                info.accion = "Fin de Turno (Detenido)";
                info.progreso = 1.0;
            }
            if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
            return info;
        }

        private String abreviar(String s) {
            if (s == null) return "";
            return s.length() > 22 ? s.substring(0, 19) + "..." : s;
        }

        private void ubicarSobreRuta(InfoEstado info, Vertice vOrigen, Vertice vDestino, double progreso) {
            String cacheKey = vOrigen.getId() + "-" + vDestino.getId();
            GraphPath<Vertice, DefaultWeightedEdge> path = cacheRutas.get(cacheKey);
            if (path == null && !cacheRutas.containsKey(cacheKey)) {
                DijkstraShortestPath<Vertice, DefaultWeightedEdge> dijkstra = new DijkstraShortestPath<>(grafoMina);
                path = dijkstra.getPath(vOrigen, vDestino);
                cacheRutas.put(cacheKey, path);
            }
            if (path != null && path.getVertexList().size() > 1) {
                java.util.List<Vertice> vertices = path.getVertexList();
                double totalWeight = path.getWeight();
                double targetWeight = totalWeight * progreso;
                double accumulatedWeight = 0.0;
                Vertice vSegmentoInicio = vertices.get(0);
                Vertice vSegmentoFin = vertices.get(1);
                double localProgreso = 0.0;
                for (int i = 0; i < vertices.size() - 1; i++) {
                    Vertice vA = vertices.get(i);
                    Vertice vB = vertices.get(i + 1);
                    DefaultWeightedEdge edge = grafoMina.getEdge(vA, vB);
                    double edgeWeight = (edge != null) ? grafoMina.getEdgeWeight(edge) : 0;
                    if (accumulatedWeight + edgeWeight >= targetWeight || i == vertices.size() - 2) {
                        vSegmentoInicio = vA; vSegmentoFin = vB;
                        localProgreso = (edgeWeight > 0) ? (targetWeight - accumulatedWeight) / edgeWeight : 1.0;
                        localProgreso = Math.max(0.0, Math.min(1.0, localProgreso));
                        break;
                    }
                    accumulatedWeight += edgeWeight;
                }
                double latActual = vSegmentoInicio.getLatitud()
                        + (vSegmentoFin.getLatitud() - vSegmentoInicio.getLatitud()) * localProgreso;
                double lonActual = vSegmentoInicio.getLongitud()
                        + (vSegmentoFin.getLongitud() - vSegmentoInicio.getLongitud()) * localProgreso;
                info.setPosGeo(latActual, lonActual);
            } else {
                double latActual = vOrigen.getLatitud()
                        + (vDestino.getLatitud() - vOrigen.getLatitud()) * progreso;
                double lonActual = vOrigen.getLongitud()
                        + (vDestino.getLongitud() - vOrigen.getLongitud()) * progreso;
                info.setPosGeo(latActual, lonActual);
            }
        }

        // ============================================================
        //  POSICIÓN DEL CAMIÓN — modo CON INCERTIDUMBRE (usa SlotReal)
        // ============================================================
        private InfoEstado calcularPosicionCamionReal(String nombreCamion, long tActual) {
            InfoEstado info = new InfoEstado();
            String ubicacionInicial = RegistroSimulacion.getInstancia().getUbicacionInicial(nombreCamion);
            double factor = RegistroSimulacion.getInstancia().getFactorVelocidadCamion(nombreCamion);
            // La avería del camión aplica en cualquier estado (viajando, en cola, etc.)
            info.enFalla = gestor.estaCamionEnFalla(nombreCamion, tActual);
            List<RegistroSimulacion.SlotReal> reales =
                    RegistroSimulacion.getInstancia().getAgendaReal(nombreCamion);

            if (reales == null || reales.isEmpty()) {
                info.color = Color.GRAY; info.progreso = 0.0;
                info.accion = "Inactivo / Esperando asignación";
                Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, ubicacionInicial);
                if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
                return info;
            }

            RegistroSimulacion.SlotReal anterior = null;
            for (RegistroSimulacion.SlotReal sr : reales) {
                // ¿Dentro de este slot real?
                if (tActual >= sr.inicioReal && tActual <= sr.finReal) {
                    return llenarInfoDesdeSlotReal(info, sr, anterior, ubicacionInicial, nombreCamion, factor, tActual);
                }
                // ¿En un hueco antes de este slot? (llegó pero aún no empieza → cola)
                if (sr.inicioReal > tActual) {
                    Slot s = sr.slotPlanificado;
                    if (anterior != null && anterior.finReal <= tActual
                            && (s.getOperacion() == Slot.enum_operacion.CARGA
                             || s.getOperacion() == Slot.enum_operacion.DESCARGA)) {
                        boolean esCarga = s.getOperacion() == Slot.enum_operacion.CARGA;
                        Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, s.getUbicacion());
                        if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
                        info.enColaEnPala = s.getUbicacion();
                        if (esCarga) info.palaAsignada = s.getMaquina(); // cola de SU pala
                        info.horaLlegadaCola = anterior.finReal;
                        info.color = new Color(231, 197, 100);
                        info.retrasoAcumuladoMs = Math.max(0, sr.inicioReal - s.getHoraInicio());
                        info.accion = (esCarga ? "En cola - carga " : "En cola - descarga ")
                                + Utilidades.longToHHMMSS(sr.inicioReal);
                        return info;
                    }
                    if (anterior != null) {
                        info.color = new Color(150, 150, 150); info.progreso = 0.0;
                        info.retrasoAcumuladoMs = Math.max(0, anterior.retraso);
                        info.accion = "En espera (detenido en " + abreviar(anterior.slotPlanificado.getUbicacion()) + ")";
                        Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, anterior.slotPlanificado.getUbicacion());
                        if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
                        return info;
                    }
                    break;
                }
                anterior = sr;
            }

            // Después del último slot real: detenido en el último destino.
            info.color = Color.GRAY; info.progreso = 1.0; info.accion = "Fin de Turno (Detenido)";
            RegistroSimulacion.SlotReal ult = reales.get(reales.size() - 1);
            info.retrasoAcumuladoMs = Math.max(0, ult.retraso);
            Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, ult.slotPlanificado.getUbicacion());
            if (v == null) v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, ubicacionInicial);
            if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
            return info;
        }

        private InfoEstado llenarInfoDesdeSlotReal(InfoEstado info, RegistroSimulacion.SlotReal sr,
                                                   RegistroSimulacion.SlotReal anterior,
                                                   String ubicacionInicial, String camion,
                                                   double factor, long tActual) {
            Slot s = sr.slotPlanificado;
            info.progreso = CalculadorScheduleReal.calcularProgresoVisual(sr, camion, factor, tActual, gestor);
            info.retrasoAcumuladoMs = Math.max(0, sr.inicioReal - s.getHoraInicio());
            info.enFalla = gestor.estaCamionEnFalla(camion, tActual);

            if (s.getOperacion() == Slot.enum_operacion.CARGA) {
                info.color = new Color(224, 108, 117);
                info.accion = "Cargando en: " + abreviar(s.getUbicacion());
                info.atendiendoEnPunto = s.getUbicacion();
                info.palaAsignada = s.getMaquina(); // pala concreta donde carga
            } else if (s.getOperacion() == Slot.enum_operacion.DESCARGA) {
                info.color = new Color(152, 195, 121);
                info.accion = "Descargando en: " + abreviar(s.getUbicacion());
                info.atendiendoEnPunto = s.getUbicacion();
            } else if (s.getOperacion() == Slot.enum_operacion.VIAJE_VACIO) {
                info.color = new Color(229, 192, 123);
                info.accion = "Viaje Vacío -> " + abreviar(s.getUbicacion());
            } else {
                info.color = new Color(97, 175, 239);
                info.accion = "Viaje Cargado -> " + abreviar(s.getUbicacion());
            }

            if (s.getOperacion() == Slot.enum_operacion.CARGA
                    || s.getOperacion() == Slot.enum_operacion.DESCARGA) {
                Vertice v = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, s.getUbicacion());
                if (v != null) info.setPosGeo(v.getLatitud(), v.getLongitud());
            } else {
                String idOrigen = (anterior == null) ? ubicacionInicial : anterior.slotPlanificado.getUbicacion();
                Vertice vOrigen = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, idOrigen);
                Vertice vDestino = Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, s.getUbicacion());
                if (vOrigen != null && vDestino != null) ubicarSobreRuta(info, vOrigen, vDestino, info.progreso);
            }
            return info;
        }
    }

    // ====================================================================
    //  Estado visual de un camión en un instante dado
    // ====================================================================
    private static class InfoEstado {
        double latitud, longitud;
        boolean tienePosicionGeografica = false;
        Color color = Color.GRAY;
        String accion = "";
        double progreso = 0.0;
        String enColaEnPala = null;
        long horaLlegadaCola = Long.MAX_VALUE;
        int posicionEnCola = -1;
        long retrasoAcumuladoMs = 0;   // cuánto se atrasó la actividad actual respecto al plan
        boolean enFalla = false;       // avería de camión
        String atendiendoEnPunto = null; // ubicación donde está cargando/descargando (ocupa una pala/puesto)
        String palaAsignada = null;      // pala concreta asignada (para ubicar el camión en SU pala)
        int offX = 0, offY = 0;        // desplazamiento visual respecto al punto (ranura de pala o fila de cola)

        void setPosGeo(double lat, double lon) {
            this.latitud = lat;
            this.longitud = lon;
            this.tienePosicionGeografica = true;
        }
    }
}
