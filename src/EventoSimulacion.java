import java.awt.Color;

/**
 * Representa un evento que afecta a la simulación. Hay dos familias:
 *
 *   FAMILIA "CLIMA" (afecta a todos los camiones por igual mientras dura):
 *     LLUVIA_LIGERA, LLUVIA_FUERTE, NEBLINA, TORMENTA, VIENTO_FUERTE, POLVO_EN_PISTA
 *
 *   FAMILIA "FALLA" (afecta a un agente específico, identificado por targetId):
 *     FALLA_CAMION, FALLA_PALA, FALLA_CHANCADOR
 *
 * Para esta capa solo se usan los eventos de CLIMA (ver GestorEventos). Los
 * tipos de falla quedan definidos para la próxima capa, pero no se cargan.
 *
 * Para eventos de clima, factorVelocidad en (0,1] multiplica la velocidad
 * efectiva de los camiones mientras dura el evento.
 */
public class EventoSimulacion {

    public enum TipoEvento {
        LLUVIA_LIGERA   ("Lluvia ligera",        new Color(100, 149, 237), false),
        LLUVIA_FUERTE   ("Lluvia fuerte",        new Color( 70, 130, 180), false),
        NEBLINA         ("Neblina densa",        new Color(176, 196, 222), false),
        TORMENTA        ("Tormenta eléctrica",   new Color( 75,   0, 130), false),
        VIENTO_FUERTE   ("Viento fuerte",        new Color(244, 164,  96), false),
        POLVO_EN_PISTA  ("Polvareda en pista",   new Color(210, 180, 140), false),
        FALLA_CAMION    ("Avería de camión",     new Color(220,  20,  60), true),
        FALLA_PALA      ("Avería de pala",       new Color(255,  69,   0), true),
        // Falla de un punto de descarga completo (chancador o botadero). El
        // nombre se conserva por compatibilidad con los CSV de corridas anteriores.
        FALLA_CHANCADOR ("Avería de punto de descarga", new Color(178,  34,  34), true);

        private final String descripcion;
        private final Color color;
        private final boolean esFalla;

        TipoEvento(String d, Color c, boolean falla) {
            this.descripcion = d; this.color = c; this.esFalla = falla;
        }
        public String getDescripcion() { return descripcion; }
        public Color  getColor()       { return color; }
        public boolean esFalla()       { return esFalla; }
    }

    private final TipoEvento tipo;
    private final long horaInicioMs;
    private final long duracionMs;
    private final double factorVelocidad;
    private final String targetId; // null para clima; id de camión/pala/destino para fallas

    public EventoSimulacion(TipoEvento tipo, long horaInicioMs, long duracionMs, double factorVelocidad) {
        this(tipo, horaInicioMs, duracionMs, factorVelocidad, null);
    }

    public EventoSimulacion(TipoEvento tipo, long horaInicioMs, long duracionMs, double factorVelocidad, String targetId) {
        this.tipo = tipo;
        this.horaInicioMs = horaInicioMs;
        this.duracionMs = duracionMs;
        this.factorVelocidad = tipo.esFalla() ? 0.0 : factorVelocidad;
        this.targetId = targetId;
    }

    public static EventoSimulacion crearFallaCamion(String idCamion, long horaInicioMs, long duracionMs) {
        return new EventoSimulacion(TipoEvento.FALLA_CAMION, horaInicioMs, duracionMs, 0.0, idCamion);
    }

    public static EventoSimulacion crearFallaPala(String ubicacionPala, long horaInicioMs, long duracionMs) {
        return new EventoSimulacion(TipoEvento.FALLA_PALA, horaInicioMs, duracionMs, 0.0, ubicacionPala);
    }

    public static EventoSimulacion crearFallaChancador(String destino, long horaInicioMs, long duracionMs) {
        return new EventoSimulacion(TipoEvento.FALLA_CHANCADOR, horaInicioMs, duracionMs, 0.0, destino);
    }

    public TipoEvento getTipo()        { return tipo; }
    public long getHoraInicioMs()      { return horaInicioMs; }
    public long getDuracionMs()        { return duracionMs; }
    public long getHoraFinMs()         { return horaInicioMs + duracionMs; }
    public double getFactorVelocidad() { return factorVelocidad; }
    public String getTargetId()        { return targetId; }

    public boolean estaActivoEn(long t) {
        return t >= horaInicioMs && t < (horaInicioMs + duracionMs);
    }

    public double progresoEn(long t) {
        if (!estaActivoEn(t)) return -1;
        return (double) (t - horaInicioMs) / (double) duracionMs;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(tipo.getDescripcion());
        if (targetId != null) sb.append(" [").append(abreviar(targetId)).append("]");
        sb.append(" @ ").append(Utilidades.longToHHMMSS(horaInicioMs));
        sb.append(" (").append(duracionMs / 60000).append(" min");
        if (!tipo.esFalla()) sb.append(", x").append(String.format(java.util.Locale.US, "%.2f", factorVelocidad));
        sb.append(")");
        return sb.toString();
    }

    private static String abreviar(String s) {
        if (s == null) return "";
        return s.length() > 18 ? s.substring(0, 15) + "..." : s;
    }
}