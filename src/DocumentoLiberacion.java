import java.io.Serializable;

/**
 * DTO serializable para el mensaje LIBERAR del metodo Partial Repair.
 *
 * Los receptores identifican este mensaje porque su conversationId empieza
 * con el prefijo "LIBERAR-", lo que les permite distinguirlo del flujo de
 * Contract-Net normal y del RESCHEDULE- de la cascada.
 *
 * Semantica:
 *   - El camion identificado por idCamion debe BORRAR sus slots con
 *     horaInicio > corte (los que vienen despues de la tarea en curso).
 *   - Si es un ShovelAgent o UnloadingPointAgent: borrar los slots que
 *     tenia ASIGNADOS a este camion con horaInicio > corte.
 *   - El TruckAgent ademas debe RESETEAR su lista noEnviarCFP y otros
 *     estados de negociacion para volver a aceptar CFPs de cualquier pala.
 *
 * Despues de procesar el mensaje, los huecos generados en las palas hacen
 * que su TickerBehaviour detecte vacantes y dispare nuevas negociaciones
 * Contract-Net. El truck liberado responde y se re-asigna.
 */
public class DocumentoLiberacion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String idCamion;
    private final long   corte;
    private final String motivo;
    /**
     * Hora en que el camion queda REALMENTE libre (fin esperado del ciclo que
     * esta haciendo de verdad, segun el simulador). Sus tareas nuevas no
     * pueden salir antes. -1 = no aplica (se mantiene lo que tenia).
     * Agregado el 2026-10-07: antes las tareas nuevas se planificaban desde
     * el fin PLANIFICADO del ciclo en curso y heredaban todo el atraso.
     */
    private final long   disponibleDesde;

    public DocumentoLiberacion(String idCamion, long corte, String motivo) {
        this(idCamion, corte, motivo, -1L);
    }

    public DocumentoLiberacion(String idCamion, long corte, String motivo, long disponibleDesde) {
        this.idCamion = idCamion;
        this.corte    = corte;
        this.motivo   = motivo;
        this.disponibleDesde = disponibleDesde;
    }

    public String getIdCamion() { return idCamion; }
    public long   getCorte()    { return corte; }
    public String getMotivo()   { return motivo; }
    public long   getDisponibleDesde() { return disponibleDesde; }

    @Override
    public String toString() {
        return "DocumentoLiberacion{camion=" + idCamion
             + ", corte=" + Utilidades.longToHHMMSS(corte)
             + ", motivo=" + motivo + "}";
    }
}
