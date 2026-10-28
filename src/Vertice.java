import java.io.Serializable;

public class Vertice implements Serializable {
    private static final long serialVersionUID = 1L;
	
	private String id;
	private double latitud;
	private double longitud;
	public Vertice(String id, double lat, double lon) {
		setId(id);
		setLatitud(lat);
		setLongitud(lon);
	}
	public double getLongitud() {
		return longitud;
	}
	public void setLongitud(double longitud) {
		this.longitud = longitud;
	}
	public double getLatitud() {
		return latitud;
	}
	public void setLatitud(double latitud) {
		this.latitud = latitud;
	}
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}

	public String toString() {
		return (this.id+" latitud:"+this.latitud+" longitud:"+this.longitud);
		
	}
}
