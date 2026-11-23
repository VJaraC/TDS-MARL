
public class ParametrosConfiguracion {

	public static int NUMERODESHOVELS;//en main container se inicializa.
	public static int NUMERODETRUCKS; //en main container se inicializa.
	// DEADLINE_MS, NOMBRE_ARCHIVO_FLOTA: el valor escrito es el POR DEFECTO; se pueden
	// entregar al lanzar con -Dtds.deadlineMs y -Dtds.escenario (ver ConfiguracionCorrida y COMO_CORRER.md).
	public static long DEADLINE_MS=ConfiguracionCorrida.deadlineMs(5000L);
	public static final long SEGUNDO_FINAL_HORARIO = 6*60*60*1000; // turno de 6 horas en milisegundos;
	public static final String LEARNING_ENABLED = "false";
	//public static final String NOMBRE_ARCHIVO_FLOTA = "datosShovelTrucksPequeno.owl";
	//public static final String NOMBRE_ARCHIVO_FLOTA = "datosShovelTrucksMediano.owl";
	public static final String NOMBRE_ARCHIVO_FLOTA = ConfiguracionCorrida.escenario("datosShovelTrucksGrande_original.owl");
	
	
	public static final String prefijoArchivos() {
		String prefijo;
		int index = NOMBRE_ARCHIVO_FLOTA.indexOf('.');
        
		if (LEARNING_ENABLED.equals("false")) {
			prefijo = NOMBRE_ARCHIVO_FLOTA.substring(0, index)+"SinRL";
		} else {
			prefijo = NOMBRE_ARCHIVO_FLOTA.substring(0, index)+"ConRL";

		}
		 
		return prefijo;
		
	}
}
