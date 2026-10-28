import java.io.Serializable;

/**
 * Aviso del ReschedulingAgent a una pala: "hubo una reparacion, vuelve a
 * ofrecer todo tu tiempo libre desde 'desde' hasta el final del turno".
 *
 * Lo pidio el profesor (reunion del 2-oct-2026): al reparar el plan, todas
 * las palas ofrecen todo lo que tengan disponible, no solo los huecos que se
 * les liberaron, porque las condiciones cambiaron desde la negociacion
 * inicial y una pala que quedo vacia ahora si puede recibir camiones.
 *
 * 'generacion' identifica el barrido: el episodio de renegociacion espera a
 * que cada pala termine el barrido de SU generacion antes de descongelar el
 * reloj (ver EpisodioRescheduling).
 */
public class DocumentoReoferta implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int  generacion;
    private final long desde;

    public DocumentoReoferta(int generacion, long desde) {
        this.generacion = generacion;
        this.desde      = desde;
    }

    public int  getGeneracion() { return generacion; }
    public long getDesde()      { return desde; }
}
