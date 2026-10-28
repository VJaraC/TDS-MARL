import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * PLAN INICIAL UNICO por escenario (reunion con el profesor, 14-oct-2026).
 *
 * En vez de negociar el plan inicial en cada corrida, cada agente carga su
 * agenda desde un archivo guardado de una negociacion anterior. Asi todos los
 * metodos y todas las replicas parten del MISMO plan, y la diferencia entre
 * corridas se debe solo a la reprogramacion. Ademas se ahorra la negociacion
 * inicial (de 30 a 90 minutos reales en el escenario grande).
 *
 * --- El archivo ----------------------------------------------------------
 * planes_iniciales/plan_inicial_<flota>.csv, por ejemplo
 * plan_inicial_datosShovelTrucksGrande_original.csv. Lo escribe
 * GuardarPlanInicial al terminar una negociacion inicial, despues de
 * verificar que el plan esta limpio. Trae la agenda de cada camion, pala y
 * punto de descarga tal como la dejo la negociacion:
 *   agente,tipo,contraparte,operacion,ubicacion,inicio_ms,fin_ms,material
 * El nombre va ligado a la flota para que no se cargue el plan de otro
 * escenario por descuido.
 *
 * --- Como se carga -------------------------------------------------------
 * Cada agente, en su setup, agrega sus tareas con Schedule.agregarSlot, igual
 * que cuando gana una negociacion. Eso las escribe en el CSV de schedules, y
 * el simulador las recibe por el mismo camino de siempre (LectorCSVSchedules),
 * asi que las cuatro copias de cada asignacion (simulador, camion, pala y
 * destino) quedan iguales. La pala da su turno por lleno sin negociar, que es
 * el estado en que queda al terminar la negociacion inicial; despues atiende
 * las liberaciones y re-ofertas de la reprogramacion como siempre.
 *
 * La simulacion no arranca hasta que todos los agentes del archivo cargaron
 * su agenda y el simulador leyo todas las tareas (ver
 * VentanaSimulacion.evaluarArranqueAutomatico). Si falta el archivo o no
 * calza con la flota, no arranca y lo avisa por consola.
 */
public final class PlanInicialFijo {

    public static final String CARPETA = "planes_iniciales";
    public static final String CABECERA = "agente,tipo,contraparte,operacion,ubicacion,inicio_ms,fin_ms,material";

    /** Una tarea de la agenda de un agente, tal como va en el archivo. */
    static final class Fila {
        String agente, tipo, contraparte, operacion, ubicacion;
        long inicio, fin;
        double material;

        Fila(String agente, String tipo, String contraparte, String operacion, String ubicacion,
             long inicio, long fin, double material) {
            this.agente = agente;
            this.tipo = tipo;
            this.contraparte = contraparte == null ? "" : contraparte;
            this.operacion = operacion;
            this.ubicacion = ubicacion == null ? "" : ubicacion;
            this.inicio = inicio;
            this.fin = fin;
            this.material = material;
        }

        /** Para comparar dos planes (el material no cambia la asignacion). */
        String clave() {
            return agente + "|" + tipo + "|" + contraparte + "|" + operacion + "|" + ubicacion
                    + "|" + inicio + "|" + fin;
        }

        String linea() {
            return String.join(",", agente, tipo, contraparte, operacion, ubicacion,
                    Long.toString(inicio), Long.toString(fin), Double.toString(material));
        }
    }

    private static Map<String, List<Fila>> porAgente = null;   // null = sin leer
    private static String error = null;
    private static int tareasCamiones = 0;
    private static final Set<String> cargados = new HashSet<>();

    private PlanInicialFijo() {}

    public static boolean usar() {
        return ParametrosSimulador.USAR_PLAN_INICIAL_FIJO;
    }

    /** planes_iniciales/plan_inicial_<flota sin extension>.csv */
    public static String archivo() {
        return archivo(ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA);
    }

    static String archivo(String flota) {
        int punto = flota.lastIndexOf('.');
        if (punto > 0) flota = flota.substring(0, punto);
        return CARPETA + File.separator + "plan_inicial_" + flota + ".csv";
    }

    /** Lee un archivo de plan. Lanza IOException con un mensaje claro si no calza. */
    static List<Fila> leer(File f) throws IOException {
        List<Fila> filas = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String cabecera = r.readLine();
            if (cabecera == null || !cabecera.trim().startsWith("agente,tipo,contraparte,operacion")) {
                throw new IOException(f.getPath() + " no tiene el formato esperado (cabecera: " + cabecera + ")");
            }
            String linea;
            int numero = 1;
            while ((linea = r.readLine()) != null) {
                numero++;
                if (linea.trim().isEmpty()) continue;
                String[] c = linea.split(",", -1);
                if (c.length < 8) throw new IOException(f.getPath() + ", linea " + numero + ": faltan columnas");
                try {
                    Fila x = new Fila(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim(),
                            Long.parseLong(c[5].trim()), Long.parseLong(c[6].trim()),
                            Double.parseDouble(c[7].trim()));
                    Slot.enum_operacion.valueOf(x.operacion);   // falla si la operacion no existe
                    filas.add(x);
                } catch (IllegalArgumentException e) {
                    throw new IOException(f.getPath() + ", linea " + numero + ": " + e.getMessage());
                }
            }
        }
        return filas;
    }

    private static void leerSiFalta() {
        if (porAgente != null || error != null) return;
        File f = new File(archivo());
        if (!f.exists()) {
            error = "no existe " + f.getPath() + ". Se crea solo al terminar una corrida con"
                    + " USAR_PLAN_INICIAL_FIJO = false (negociacion inicial completa y plan limpio),"
                    + " o con: java -cp bin GuardarPlanInicial <etiqueta de una corrida anterior>.";
            return;
        }
        Map<String, List<Fila>> mapa = new HashMap<>();
        int n = 0;
        try {
            for (Fila x : leer(f)) {
                mapa.computeIfAbsent(x.agente, k -> new ArrayList<>()).add(x);
                if (RegistroAgendasAgentes.CAMION.equals(x.tipo)) n++;
            }
        } catch (IOException e) {
            error = "no se pudo leer " + f.getPath() + ": " + e.getMessage();
            return;
        }
        porAgente = mapa;
        tareasCamiones = n;
        System.out.printf("[PlanInicialFijo] %s: %d agentes, %d tareas de camiones%n",
                f.getPath(), mapa.size(), n);
    }

    /**
     * Agrega a la agenda del agente las tareas que el archivo le asigna. Lo
     * llama cada agente en su setup, antes de empezar a atender mensajes.
     * Devuelve cuantas tareas agrego (0 si no se usa el plan fijo).
     */
    public static synchronized int cargarEn(String agente, String tipo, Schedule schedule) {
        if (!usar()) return 0;
        leerSiFalta();
        if (error != null) return 0;
        List<Fila> filas = porAgente.get(agente);
        cargados.add(agente);
        if (filas == null) return 0;   // agente sin tareas en el plan
        int n = 0;
        synchronized (schedule) {
            for (Fila x : filas) {
                if (!tipo.equals(x.tipo)) {
                    error = "el archivo dice que " + agente + " es " + x.tipo + ", pero es " + tipo
                            + ". El plan es de otra flota.";
                    return n;
                }
                schedule.agregarSlot(agente, x.contraparte, x.inicio, x.fin, x.ubicacion,
                        Slot.enum_operacion.valueOf(x.operacion), x.material);
                n++;
            }
        }
        return n;
    }

    /** Mensaje de error, o null si el plan se leyo bien (o todavia no se usa). */
    public static synchronized String getError() {
        if (!usar()) return null;
        leerSiFalta();
        return error;
    }

    /**
     * true cuando todos los agentes del archivo cargaron su agenda y el
     * simulador ya leyo todas las tareas de los camiones.
     */
    public static synchronized boolean completo() {
        if (porAgente == null || error != null) return false;
        if (!cargados.containsAll(porAgente.keySet())) return false;
        return tareasEnSimulador() == tareasCamiones;
    }

    /** Avance de la carga, para la consola. */
    public static synchronized String estado() {
        if (porAgente == null) return "archivo sin leer";
        Set<String> faltan = new TreeSet<>(porAgente.keySet());
        faltan.removeAll(cargados);
        String s = String.format("agentes %d/%d, tareas de camiones en el simulador %d/%d",
                porAgente.size() - faltan.size(), porAgente.size(), tareasEnSimulador(), tareasCamiones);
        if (!faltan.isEmpty()) {
            List<String> muestra = new ArrayList<>(faltan);
            s += "; faltan " + (muestra.size() > 8 ? muestra.subList(0, 8) + "..." : muestra);
        }
        return s;
    }

    /**
     * Al terminar una negociacion inicial: true cuando las cuatro copias ya
     * tienen la misma cantidad de tareas. Los ultimos mensajes (el INFORM al
     * punto de descarga) y el lector del CSV del simulador pueden ir unos
     * instantes atrasados respecto de la agenda del camion; sin esperarlos, la
     * fotografia del plan inicial quedaba incompleta (grande_V7_PARTIAL: el
     * ultimo ciclo de Truck31 no estaba en la copia del simulador).
     */
    public static boolean copiasAlDia() {
        int camion = 0, cargasCamion = 0, descargasCamion = 0, pala = 0, destino = 0;
        for (RegistroAgendasAgentes.Entrada e : RegistroAgendasAgentes.getAgendas().values()) {
            synchronized (e.schedule) {
                for (Slot s : e.schedule.getSlots()) {
                    if (RegistroAgendasAgentes.CAMION.equals(e.tipo)) {
                        camion++;
                        if (s.getOperacion() == Slot.enum_operacion.CARGA) cargasCamion++;
                        if (s.getOperacion() == Slot.enum_operacion.DESCARGA) descargasCamion++;
                    } else if (RegistroAgendasAgentes.PALA.equals(e.tipo)) {
                        pala++;
                    } else {
                        destino++;
                    }
                }
            }
        }
        return camion == tareasEnSimulador() && cargasCamion == pala && descargasCamion == destino;
    }

    private static int tareasEnSimulador() {
        int n = 0;
        for (Schedule s : RegistroSimulacion.getInstancia().getAgendasCamiones().values()) {
            synchronized (s) { n += s.getSlots().size(); }
        }
        return n;
    }
}
