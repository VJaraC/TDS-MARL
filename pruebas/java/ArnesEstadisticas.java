import java.io.File;

/**
 * Arnes de prueba de EstadisticasPlanInicial (lo usa pruebas/test_estadisticas.py).
 * Se ejecuta con el directorio de trabajo en una carpeta temporal que contiene
 * los CSV de entrada sinteticos.
 *
 * Uso: java ArnesEstadisticas <dir corrida> <motivo> <tCapturaMs> <tFinDetectadoMs> <numCamiones> <palas,separadas,por,coma>
 */
public class ArnesEstadisticas {
    public static void main(String[] a) throws Exception {
        ParametrosConfiguracion.NUMERODETRUCKS = Integer.parseInt(a[4]);
        for (String p : a[5].split(",")) RegistroSimulacion.getInstancia().registrarPala(p, "#X");
        RegistroSimulacion.getInstancia().registrarDestino("#D1", 2);
        EstadisticasPlanInicial.Resultado r = EstadisticasPlanInicial.generar(
                new File(a[0]), a[1], Long.parseLong(a[2]), Long.parseLong(a[3]));
        System.out.println("OK advertencias=" + r.advertencias.size());
    }
}
