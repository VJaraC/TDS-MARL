import java.io.File;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Orquestador del módulo simulador. Se invoca desde MainContainer en dos
 * momentos:
 *
 *   1) ANTES de crear los agentes JADE: {@link #limpiarArchivosPrevios()}.
 *      Borra los CSV de la corrida anterior para no contaminar.
 *
 *   2) DESPUÉS de crear los agentes JADE: {@link #iniciar(Graph)}.
 *      Lee el OWL para poblar RegistroSimulacion con palas, destinos,
 *      camiones y velocidades. Inicia el polling del CSV de schedules.
 *      Abre la ventana del simulador.
 *
 * Es la ÚNICA puerta de entrada al módulo simulador: el resto de los archivos
 * (RegistroSimulacion, LectorCSVSchedules, VentanaSimulacion, etc.) se
 * orquestan desde aquí.
 */
public class InicializadorSimulador {

    /**
     * Borra los CSVs que produce el simulador para que la corrida actual
     * empiece limpia. Se llama al inicio de MainContainer.main() antes de que
     * los agentes carguen las clases CsvSchedules/CsvLogger.
     *
     * NO borra "kpis_corridas.csv" (es acumulativo a propósito).
     */
    public static void limpiarArchivosPrevios() {
        String[] archivos = {
            ParametrosConfiguracion.prefijoArchivos() + "schedules.csv",
            ParametrosConfiguracion.prefijoArchivos() + "messages.csv",
            ParametrosConfiguracion.prefijoArchivos() + "negotiations.csv",
            "auditoria_simulacion_planificada.csv",
            "auditoria_simulacion_comparativa.csv"
            // OJO: "kpis_corridas.csv" NO se borra (acumulativo entre corridas).
        };
        for (String f : archivos) {
            try {
                File file = new File(f);
                if (file.exists() && file.delete()) {
                    System.out.println("[sim] borrado: " + f);
                }
            } catch (Exception e) {
                System.err.println("[sim] no se pudo borrar " + f + ": " + e.getMessage());
            }
        }
    }

    /**
     * Lee el OWL de flota y los registra al RegistroSimulacion:
     *   - Cada camión: su ubicación inicial y sus velocidades nominales.
     *   - Cada pala: su ubicación física.
     *   - Cada destino de descarga (Wastedump/Crusher/Stockpile): su capacidad.
     * Luego inicia el polling del CSV de schedules y abre la ventana.
     */
    public static void iniciar(Graph<Vertice, DefaultWeightedEdge> grafoMina) {
        try {
            File inputFile = new File(ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(inputFile);
            doc.getDocumentElement().normalize();

            registrarCamiones(doc);
            registrarPalas(doc);
            registrarDestinos(doc);

            int trucks  = RegistroSimulacion.getInstancia().getUbicacionesIniciales().size();
            int palas   = RegistroSimulacion.getInstancia().getUbicacionPalas().size();
            int destinos= RegistroSimulacion.getInstancia().getCapacidadDestinos().size();
            System.out.println("[sim] datos cargados del OWL: "
                + trucks + " camiones, " + palas + " palas, " + destinos + " destinos de descarga");

            // Polling del CSV de schedules: detecta slots a medida que los
            // agentes los van escribiendo.
            LectorCSVSchedules lector = new LectorCSVSchedules();
            lector.iniciar();
            System.out.println("[sim] lector de schedules en marcha (cada "
                + ParametrosSimulador.POLLING_CSV_MS + "ms)");

            // Avería predeterminada del camión, si hay incertidumbre. Las de pala
            // y punto de descarga se cargan al conocerse el plan inicial.
            cargarAveriasPredeterminadas();

            // Ventana en el EDT.
            javax.swing.SwingUtilities.invokeLater(() -> {
                VentanaSimulacion v = new VentanaSimulacion(grafoMina);
                v.setVisible(true);
                System.out.println("[sim] ventana abierta. escenario=\""
                    + ParametrosSimulador.ETIQUETA_ESCENARIO + "\"");
                // Capturador de snapshots: periodico + por evento. Si la ventana
                // se cierra antes de arrancar, capturarPorEvento queda como no-op.
                CapturadorSnapshots.getInstancia().iniciar(v);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── Averias predeterminadas ─────────────────────────────────────────────
    // Una de cada tipo, en franjas separadas de los 3 climas para verlas por
    // separado: camion 1:30 (20 min), pala 2:00 (25 min), punto de descarga
    // 3:20 (30 min).
    private static final long MIN = 60_000L;
    static final long INICIO_FALLA_PALA_MS     = 120 * MIN;
    static final long DURACION_FALLA_PALA_MS   =  25 * MIN;
    static final long INICIO_FALLA_DESTINO_MS  = 200 * MIN;
    static final long DURACION_FALLA_DESTINO_MS =  30 * MIN;
    private static boolean averiasSegunPlanCargadas = false;

    /**
     * Falla de CAMION: Truck5 si existe, si no el primero registrado. Se carga
     * al iniciar. La de pala y la de punto de descarga se cargan al conocerse
     * el plan inicial (ver cargarAveriasSegunPlan).
     */
    private static void cargarAveriasPredeterminadas() {
        if (!ParametrosSimulador.INCERTIDUMBRES_ACTIVAS) return;
        RegistroSimulacion reg = RegistroSimulacion.getInstancia();
        java.util.List<String> camiones =
                new java.util.ArrayList<>(reg.getUbicacionesIniciales().keySet());
        java.util.Collections.sort(camiones);
        if (!camiones.isEmpty()) {
            String camion = camiones.contains("Truck5") ? "Truck5" : camiones.get(0);
            GestorEventos.getInstancia().agregarEvento(EventoSimulacion.crearFallaCamion(camion, 90 * MIN, 20 * MIN));
            System.out.println("[sim] avería predeterminada: camión " + camion + " @ 1:30 (20 min)");
        }
    }

    /**
     * Fallas de PALA y de PUNTO DE DESCARGA sobre equipos que el plan inicial
     * usa en ese momento. Lo llama PlanBase.capturar, con el reloj todavia en
     * 0: el plan ya esta completo y los agentes no conocen las fallas futuras,
     * asi que elegir el objetivo con el plan no le da informacion a ningun
     * metodo. Con el plan inicial unico, los cuatro metodos reciben las mismas
     * fallas.
     *
     * Cambio del 2026-10-15. Antes la pala era la primera de la primera
     * ubicacion (PA01) y el destino el de menor capacidad (el chancador CS03).
     * En los tres escenarios ninguna de las dos alcanzaba a un camion: PA01
     * solo carga en los primeros 20 minutos y el chancador solo recibe
     * descargas en las primeras horas, porque las palas de RF10 que lo
     * alimentan se quedan sin camiones. En la practica solo actuaban los
     * climas y la falla del camion.
     *
     * Regla: falla la pala con mas cargas planificadas durante su falla, y el
     * punto de descarga con mas descargas planificadas durante la suya. Empate:
     * mas tareas en todo el turno; despues, orden alfabetico.
     */
    static synchronized void cargarAveriasSegunPlan(java.util.Map<String, java.util.List<PlanBase.SlotBase>> plan) {
        if (!ParametrosSimulador.INCERTIDUMBRES_ACTIVAS || averiasSegunPlanCargadas) return;
        averiasSegunPlanCargadas = true;
        GestorEventos gestor = GestorEventos.getInstancia();

        String[] pala = elegirObjetivo(plan, Slot.enum_operacion.CARGA,
                INICIO_FALLA_PALA_MS, INICIO_FALLA_PALA_MS + DURACION_FALLA_PALA_MS);
        if (pala != null) {
            gestor.agregarEvento(EventoSimulacion.crearFallaPala(pala[0],
                    INICIO_FALLA_PALA_MS, DURACION_FALLA_PALA_MS));
            System.out.println("[sim] avería predeterminada: pala " + pala[0] + " @ 2:00 (25 min), "
                    + pala[1] + " cargas planificadas en ese lapso");
        }
        String[] destino = elegirObjetivo(plan, Slot.enum_operacion.DESCARGA,
                INICIO_FALLA_DESTINO_MS, INICIO_FALLA_DESTINO_MS + DURACION_FALLA_DESTINO_MS);
        if (destino != null) {
            gestor.agregarEvento(EventoSimulacion.crearFallaChancador(destino[0],
                    INICIO_FALLA_DESTINO_MS, DURACION_FALLA_DESTINO_MS));
            System.out.println("[sim] avería predeterminada: punto de descarga " + destino[0]
                    + " @ 3:20 (30 min), " + destino[1] + " descargas planificadas en ese lapso");
        }
    }

    /**
     * Equipo con mas tareas de esa operacion que se cruzan con [desde, hasta).
     * Para CARGA el equipo es la pala (maquina del slot); para DESCARGA, el
     * punto de descarga (ubicacion). Devuelve {equipo, tareas en el lapso}, o
     * null si el plan no tiene esa operacion.
     */
    static String[] elegirObjetivo(java.util.Map<String, java.util.List<PlanBase.SlotBase>> plan,
                                   Slot.enum_operacion op, long desde, long hasta) {
        java.util.Map<String, int[]> cuenta = new java.util.TreeMap<>();   // {en el lapso, en el turno}
        for (java.util.List<PlanBase.SlotBase> slots : plan.values()) {
            for (PlanBase.SlotBase s : slots) {
                if (s.operacion != op) continue;
                String equipo = (op == Slot.enum_operacion.CARGA) ? s.maquina : s.ubicacion;
                if (equipo == null || equipo.isEmpty()) continue;
                int[] c = cuenta.computeIfAbsent(equipo, k -> new int[2]);
                c[1]++;
                if (s.horaInicio < hasta && s.horaFin > desde) c[0]++;
            }
        }
        String mejor = null;
        int[] m = null;
        for (java.util.Map.Entry<String, int[]> e : cuenta.entrySet()) {   // orden alfabetico
            int[] c = e.getValue();
            if (m == null || c[0] > m[0] || (c[0] == m[0] && c[1] > m[1])) {
                mejor = e.getKey();
                m = c;
            }
        }
        return mejor == null ? null : new String[]{mejor, Integer.toString(m[0])};
    }

    /**
     * Registra ubicaciones iniciales y velocidades nominales de cada camión.
     * Misma lógica de parseo que MainContainer.crearAgentesCamiones, pero
     * sobre RegistroSimulacion (no crea agentes JADE).
     */
    private static void registrarCamiones(Document doc) {
        NodeList lista = doc.getElementsByTagName("trans:Truck");
        for (int i = 0; i < lista.getLength(); i++) {
            Node n = lista.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String nombre = "Truck" + i;
            NodeList childNodes = e.getChildNodes();
            try {
                String ubicacionInicial = childNodes.item(3).getAttributes().item(0).getTextContent();
                double velocidadMaxima  = Double.parseDouble(childNodes.item(1).getTextContent());
                double velocidadCargado = Double.parseDouble(childNodes.item(11).getTextContent());
                RegistroSimulacion.getInstancia().registrarUbicacionInicialCamion(nombre, ubicacionInicial);
                RegistroSimulacion.getInstancia().registrarVelocidadesNominales(nombre, velocidadMaxima, velocidadCargado);
            } catch (Exception ex) {
                System.err.println("[sim] error parseando " + nombre + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Registra ubicación física de cada pala. Misma lógica de parseo que
     * MainContainer.crearAgentesPalas.
     */
    private static void registrarPalas(Document doc) {
        NodeList lista = doc.getElementsByTagName("mine:Shovel");
        for (int i = 0; i < lista.getLength(); i++) {
            Node n = lista.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String nombre = e.getAttribute("rdf:ID");
            try {
                NodeList childNodes = e.getChildNodes();
                String ubicacion = childNodes.item(1).getAttributes().item(0).getTextContent();
                RegistroSimulacion.getInstancia().registrarPala(nombre, ubicacion);
            } catch (Exception ex) {
                System.err.println("[sim] error parseando pala " + nombre + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Registra capacidad de descarga (maximumDischarge) de cada Wastedump,
     * Crusher y Stockpile del OWL.
     */
    private static void registrarDestinos(Document doc) {
        String[] tags = {"mine:Wastedump", "mine:Crusher", "mine:Stockpile"};
        for (String tag : tags) {
            NodeList lista = doc.getElementsByTagName(tag);
            for (int i = 0; i < lista.getLength(); i++) {
                Node n = lista.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                try {
                    NodeList childNodes = e.getChildNodes();
                    String ubicacion = childNodes.item(1).getAttributes().item(0).getTextContent();
                    int maxDischarge = Integer.parseInt(childNodes.item(3).getTextContent().trim());
                    RegistroSimulacion.getInstancia().registrarDestino(ubicacion, maxDischarge);
                } catch (Exception ex) {
                    System.err.println("[sim] error parseando destino: " + ex.getMessage());
                }
            }
        }
    }
}