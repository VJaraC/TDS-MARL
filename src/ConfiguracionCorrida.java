import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametros de UNA corrida entregados desde fuera, sin recompilar
 * (ver COMO_CORRER.md). Se leen UNA vez, de propiedades del sistema, cuando se
 * inicializan ParametrosConfiguracion / ParametrosSimulador, es decir al
 * comienzo de MainContainer.main y antes de crear los agentes:
 *
 *   -Dtds.etiqueta=<texto>             ETIQUETA_ESCENARIO
 *   -Dtds.escenario=<pequeno|mediano|grande|archivo.owl>   NOMBRE_ARCHIVO_FLOTA
 *   -Dtds.deadlineMs=<entero > 0>      DEADLINE_MS
 *   -Dtds.topeNegociacionMin=<numero > 0 | null>   TOPE_NEGOCIACION_INICIAL_MS (null = sin tope)
 *   -Dtds.sinDialogo=true              cierre sin dialogos (lo usa correr_lote.py)
 *   -Dtds.sinVentana=true              no muestra la ventana de la simulacion (la mina)
 *
 * Si una propiedad no se entrega se usa el valor escrito en el codigo, asi que
 * sin propiedades el sistema se comporta exactamente como antes. Los valores
 * invalidos no se corrigen: se anotan y validar() impide que la corrida arranque.
 */
public final class ConfiguracionCorrida {

    public static final String P_ETIQUETA = "tds.etiqueta";
    public static final String P_ESCENARIO = "tds.escenario";
    public static final String P_DEADLINE = "tds.deadlineMs";
    public static final String P_TOPE = "tds.topeNegociacionMin";
    public static final String P_SIN_DIALOGO = "tds.sinDialogo";
    public static final String P_SIN_VENTANA = "tds.sinVentana";

    /** Nombres cortos de escenario. "grande" es el original (el que usan planes_iniciales/). */
    private static final Map<String, String> ESCENARIOS = new LinkedHashMap<>();
    static {
        ESCENARIOS.put("pequeno", "datosShovelTrucksPequeno.owl");
        ESCENARIOS.put("mediano", "datosShovelTrucksMediano.owl");
        ESCENARIOS.put("grande", "datosShovelTrucksGrande_original.owl");
    }

    /** Valor de TOPE_NEGOCIACION_INICIAL_MS que significa "sin tope". */
    public static final long SIN_TOPE = Long.MAX_VALUE;

    private static final List<String> errores = new ArrayList<>();
    /** Para configuracion.json: de donde salio cada parametro ("propiedad" o "codigo"). */
    private static final Map<String, Object> origen = new LinkedHashMap<>();

    private ConfiguracionCorrida() {}

    private static String prop(String nombre) {
        String v = System.getProperty(nombre);
        return v == null ? null : v.trim();
    }

    static synchronized String etiqueta(String porDefecto) {
        String v = prop(P_ETIQUETA);
        if (v == null) { origen.put("etiqueta", "codigo"); return porDefecto; }
        origen.put("etiqueta", "propiedad");
        if (v.isEmpty() || v.contains("/") || v.contains("\\") || v.contains(",")) {
            errores.add("-D" + P_ETIQUETA + "=\"" + v + "\": la etiqueta no puede estar vacia ni tener / \\ o comas.");
            return porDefecto;
        }
        return v;
    }

    static synchronized String escenario(String porDefecto) {
        String v = prop(P_ESCENARIO);
        if (v == null) { origen.put("escenario", "codigo"); return porDefecto; }
        origen.put("escenario", "propiedad");
        String archivo = ESCENARIOS.getOrDefault(v.toLowerCase(Locale.ROOT), v);
        if (!archivo.contains(".")) {
            errores.add("-D" + P_ESCENARIO + "=" + v + ": no es un nombre corto conocido " + ESCENARIOS.keySet()
                    + " ni un archivo .owl.");
            return porDefecto;
        }
        return archivo;
    }

    static synchronized long deadlineMs(long porDefecto) {
        String v = prop(P_DEADLINE);
        if (v == null) { origen.put("deadline_ms", "codigo"); return porDefecto; }
        origen.put("deadline_ms", "propiedad");
        try {
            long x = Long.parseLong(v);
            if (x > 0) return x;
        } catch (NumberFormatException e) {
            // se informa abajo
        }
        errores.add("-D" + P_DEADLINE + "=" + v + ": DEADLINE_MS debe ser un numero entero positivo (en ms).");
        return porDefecto;
    }

    /** Minutos -> ms. "null" (o "sin_tope") = sin tope. */
    static synchronized long topeNegociacionMs(long porDefecto) {
        String v = prop(P_TOPE);
        if (v == null) { origen.put("tope_negociacion_min", "codigo"); return porDefecto; }
        origen.put("tope_negociacion_min", "propiedad");
        String x = v.toLowerCase(Locale.ROOT);
        if (x.equals("null") || x.equals("sin_tope") || x.equals("ninguno")) return SIN_TOPE;
        try {
            double min = Double.parseDouble(x);
            if (min > 0 && !Double.isInfinite(min) && !Double.isNaN(min)) return Math.round(min * 60_000.0);
        } catch (NumberFormatException e) {
            // se informa abajo
        }
        errores.add("-D" + P_TOPE + "=" + v + ": el tope debe ser un numero de minutos mayor que 0, o null (sin tope).");
        return porDefecto;
    }

    public static boolean sinDialogo() {
        return "true".equalsIgnoreCase(prop(P_SIN_DIALOGO));
    }

    /**
     * true: la ventana de la simulacion se crea pero NO se muestra. Su reloj
     * (Swing Timer) sigue corriendo y es el que detecta el fin de la
     * negociacion, asi que el comportamiento es el mismo; solo no se dibuja.
     */
    public static boolean sinVentana() {
        return "true".equalsIgnoreCase(prop(P_SIN_VENTANA));
    }

    public static synchronized Map<String, Object> origen() {
        return new LinkedHashMap<>(origen);
    }

    /**
     * Lo primero que hace MainContainer.main: fuerza la lectura de los
     * parametros y comprueba lo indispensable (escenario existente y
     * DEADLINE_MS entero positivo). Si algo esta mal, la corrida no arranca:
     * no se borra nada ni se crean agentes. Codigo de salida 2.
     */
    public static void validar() {
        // Inicializa las clases de parametros (lee las propiedades).
        String escenario = ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA;
        long deadline = ParametrosConfiguracion.DEADLINE_MS;
        String etiqueta = ParametrosSimulador.ETIQUETA_ESCENARIO;
        long tope = ParametrosSimulador.TOPE_NEGOCIACION_INICIAL_MS;

        List<String> e;
        synchronized (ConfiguracionCorrida.class) { e = new ArrayList<>(errores); }
        if (!new File(escenario).isFile()) {
            e.add("el escenario " + escenario + " no existe en " + new File(".").getAbsoluteFile().getParent() + ".");
        }
        if (e.isEmpty()) {
            System.out.println("[ConfiguracionCorrida] etiqueta=" + etiqueta + " escenario=" + escenario
                    + " deadline_ms=" + deadline + " tope_negociacion="
                    + (tope == SIN_TOPE ? "sin tope" : (tope / 60_000.0) + " min")
                    + (sinDialogo() ? " (sin dialogo)" : "") + (sinVentana() ? " (sin ventana)" : "")
                    + " origen=" + origen());
            return;
        }
        StringBuilder s = new StringBuilder("La corrida NO arranca: configuracion invalida.");
        for (String x : e) s.append("\n - ").append(x);
        System.err.println("[ConfiguracionCorrida] ERROR: " + s);
        if (!sinDialogo()) {
            try {
                javax.swing.JOptionPane.showMessageDialog(null, s.toString(), "Configuracion invalida",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                // sin entorno grafico: basta la consola
            }
        }
        System.exit(2);
    }
}
