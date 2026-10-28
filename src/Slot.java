import java.io.Serializable;

/**
 * Slot del schedule. Debe ser Serializable porque viaja dentro del
 * DocumentoRescheduling (List&lt;Slot&gt;) en los ACLMessage.INFORM de
 * right-shift que el ReschedulingAgent envía a Truck/Shovel/UnloadingPoint.
 * Sin esto, JADE lanza NotSerializableException: Slot al enviar el mensaje.
 */
public class Slot implements Serializable {

	private static final long serialVersionUID = 1L;

	public static enum enum_operacion {VIAJE_VACIO, CARGA, VIAJE_CARGADO, DESCARGA }; 
	private String maquinaPropietariaSchedule;
	private long horaInicio;
	private long horaFin;
	private String maquina;
	private enum_operacion operacion;
	private String ubicacion;
	private double cantidadMaterial;
	private long tiempoInactivoDesdeActividadAnterior;

	// --- Plan ORIGINAL (negociación), para auditar el efecto del right-shift ---
	// Se capturan en la PRIMERA asignación de horaInicio/horaFin (el plan que sale
	// de la negociación / del CSV) y NO se vuelven a tocar cuando el rescheduling
	// desplaza horaInicio/horaFin. Así, aunque el right-shift pise las horas
	// vigentes, queda registro del plan original para compararlo contra el real.
	private long horaInicioOriginal;
	private long horaFinOriginal;
	private boolean origInicioSet = false;
	private boolean origFinSet = false;
	
	public long getTiempoInactivoDesdeActividadAnterior() {
		return tiempoInactivoDesdeActividadAnterior;
	}
	public void setTiempoInactivoDesdeActividadAnterior(long tiempoInactivoDesdeActividadAnterior) {
		this.tiempoInactivoDesdeActividadAnterior = tiempoInactivoDesdeActividadAnterior;
	}
	public long getHoraInicio() {
		return horaInicio;
	}
	public void setHoraInicio(long horaInicio) {
		if (!origInicioSet) { this.horaInicioOriginal = horaInicio; this.origInicioSet = true; }
		this.horaInicio = horaInicio;
	}
	public long getHoraFin() {
		return horaFin;
	}
	public void setHoraFin(long horaFin) {
		if (!origFinSet) { this.horaFinOriginal = horaFin; this.origFinSet = true; }
		this.horaFin = horaFin;
	}
	public String getMaquina() {
		return maquina;
	}
	public void setMaquina(String maquina) {
		this.maquina = maquina;
	}
	public enum_operacion getOperacion() {
		return operacion;
	}
	public void setOperacion(enum_operacion operacion) {
		this.operacion = operacion;
	}
	public String getUbicacion() {
		return ubicacion;
	}
	public void setUbicacion(String ubicacion) {
		this.ubicacion = ubicacion;
	}
	public double getCantidadMaterial() {
		return cantidadMaterial;
	}
	public void setCantidadMaterial(double cantidadMaterial) {
		this.cantidadMaterial = cantidadMaterial;
	}
	public String getMaquinaPropietariaSchedule() {
		return maquinaPropietariaSchedule;
	}
	public void setMaquinaPropietariaSchedule(String maquinaPropietariaSchedule) {
		this.maquinaPropietariaSchedule = maquinaPropietariaSchedule;
	}

	// --- Acceso al plan original y detección de desplazamiento por rescheduling ---
	public long getHoraInicioOriginal() {
		return origInicioSet ? horaInicioOriginal : horaInicio;
	}
	public long getHoraFinOriginal() {
		return origFinSet ? horaFinOriginal : horaFin;
	}
	/** true si el right-shift desplazó este slot respecto de su plan original. */
	public boolean fueReschedulado() {
		return horaInicio != getHoraInicioOriginal() || horaFin != getHoraFinOriginal();
	}

}