import java.io.Serializable;
import java.util.List;

/**
 * DTO serializable que viaja dentro de un ACLMessage.INFORM de rescheduling.
 *
 * Los receptores identifican este mensaje porque su conversationId empieza
 * con el prefijo "RESCHEDULE-", lo que les permite distinguirlo del flujo
 * normal de negociación Contract-Net.
 *
 * Contiene:
 *   - idCamion     : el camión cuyo schedule se está corrigiendo.
 *   - deltaT       : cuántos ms se desplazan hacia adelante los slots futuros.
 *   - tiempoCorte  : solo se shiftean slots con horaInicio > tiempoCorte.
 *   - nivel        : severidad del disparador (2 = Nivel 2, >15 min de retraso).
 *   - slotsActualizados : lista ya calculada con los nuevos tiempos (copia).
 */
public class DocumentoRescheduling implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String    idCamion;
    private final long      deltaT;
    private final long      tiempoCorte;
    private final int       nivel;
    private final List<Slot> slotsActualizados;
    /** {inicioAntes, finAntes, inicioDespues, finDespues, op.ordinal()} de cada
     *  slot que movio la cascada. null en documentos sin este detalle. */
    private final List<long[]> cambios;

    public DocumentoRescheduling(String idCamion, long deltaT, long tiempoCorte,
                                  int nivel, List<Slot> slotsActualizados) {
        this.idCamion          = idCamion;
        this.deltaT            = deltaT;
        this.tiempoCorte       = tiempoCorte;
        this.nivel             = nivel;
        this.slotsActualizados = slotsActualizados;
        this.cambios           = null;
    }

    public DocumentoRescheduling(String idCamion, long deltaT, long tiempoCorte,
                                  int nivel, List<Slot> slotsActualizados,
                                  List<long[]> cambios) {
        this.idCamion          = idCamion;
        this.deltaT            = deltaT;
        this.tiempoCorte       = tiempoCorte;
        this.nivel             = nivel;
        this.slotsActualizados = slotsActualizados;
        this.cambios           = cambios;
    }

    public String    getIdCamion()              { return idCamion; }
    public long      getDeltaT()                { return deltaT; }
    public long      getTiempoCorte()           { return tiempoCorte; }
    public int       getNivel()                 { return nivel; }
    public List<Slot> getSlotsActualizados()    { return slotsActualizados; }
    public List<long[]> getCambios()            { return cambios; }

    /**
     * Aplica el right-shift a una agenda de agente dejando cada slot EXACTAMENTE
     * en el horario que le dio la cascada en el simulador.
     *
     * Se usa el par (antes, despues) de cada slot y no un delta unico porque la
     * cascada mueve los slots en varias iteraciones, desde cortes distintos y
     * por montos distintos: aplicar la suma de los deltas a todo lo posterior al
     * corte mas temprano desfasaba la agenda del agente respecto del simulador
     * (se midio hasta ~3 h en grande_ENTREGA_CASCADA).
     *
     * @param slots     agenda del agente
     * @param maquina   si no es null, solo se tocan slots con getMaquina() igual
     *                  (palas y destinos guardan slots de muchos camiones)
     * @param op        si no es null, solo se tocan slots de esa operacion
     * @return cantidad de slots movidos, o -1 si el documento no trae el detalle
     *         (el llamador aplica entonces el delta unico de siempre)
     */
    public int aplicarA(List<Slot> slots, String maquina, Slot.enum_operacion op) {
        if (cambios == null) return -1;
        java.util.Map<String, java.util.Deque<long[]>> porHorario = new java.util.HashMap<>();
        for (long[] c : cambios) {
            porHorario.computeIfAbsent(c[4] + "|" + c[0] + "|" + c[1],
                    k -> new java.util.ArrayDeque<>()).add(c);
        }
        int n = 0;
        for (Slot s : slots) {
            if (maquina != null && !maquina.equals(s.getMaquina())) continue;
            if (op != null && s.getOperacion() != op) continue;
            java.util.Deque<long[]> d = porHorario.get(
                    s.getOperacion().ordinal() + "|" + s.getHoraInicio() + "|" + s.getHoraFin());
            if (d == null || d.isEmpty()) continue;
            long[] c = d.poll();
            s.setHoraInicio(c[2]);
            s.setHoraFin(c[3]);
            n++;
        }
        return n;
    }

    @Override
    public String toString() {
        return "DocumentoRescheduling{camion=" + idCamion
             + ", deltaT=" + Utilidades.longToHHMMSS(deltaT)
             + ", corte="  + Utilidades.longToHHMMSS(tiempoCorte)
             + ", nivel="  + nivel
             + ", slots="  + (slotsActualizados == null ? 0 : slotsActualizados.size())
             + "}";
    }
}