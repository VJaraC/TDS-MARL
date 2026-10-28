import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro OBSERVACIONAL de la agenda interna de cada agente (camion, pala y
 * punto de descarga). No altera la negociacion en nada: cada agente solo anota
 * aqui la referencia a su Schedule al arrancar, y el exportador por corrida la
 * lee al final para volcarla a disco.
 *
 * Hace falta porque cada asignacion vive en CUATRO copias que se borran por
 * separado al renegociar: la del simulador (RegistroSimulacion), la del
 * TruckAgent, la de su ShovelAgent y la de su UnloadingPointAgent. Si una de
 * esas copias no se actualiza bien en un LIBERAR, las demas no lo notan. La
 * unica forma de verificarlo es comparar las cuatro al final de la corrida.
 */
public final class RegistroAgendasAgentes {

    public static final String CAMION   = "CAMION";
    public static final String PALA     = "PALA";
    public static final String DESCARGA = "DESCARGA";

    public static final class Entrada {
        public final String tipo;
        public final Schedule schedule;
        Entrada(String tipo, Schedule schedule) {
            this.tipo = tipo;
            this.schedule = schedule;
        }
    }

    private static final Map<String, Entrada> AGENDAS = new ConcurrentHashMap<>();

    private RegistroAgendasAgentes() {}

    public static void registrar(String agente, String tipo, Schedule schedule) {
        AGENDAS.put(agente, new Entrada(tipo, schedule));
    }

    public static Map<String, Entrada> getAgendas() {
        return AGENDAS;
    }
}
