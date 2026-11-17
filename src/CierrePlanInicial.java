import java.awt.Desktop;
import java.io.File;
import java.util.Locale;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Cierre de la corrida apenas se genera el schedule inicial
 * (ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL).
 *
 * El trabajo de titulo estudia solo la NEGOCIACION INICIAL y el plan que
 * produce, asi que no se ejecuta el turno: al capturarse el plan
 * (VentanaSimulacion.evaluarArranqueAutomatico -> PlanBase.capturar) se
 * generan los archivos de resumen (EstadisticasPlanInicial), se muestra un
 * dialogo con el resultado y se apaga JADE (agentes y plataforma, libera el
 * puerto 1099) antes de terminar la JVM.
 *
 * No cambia nada de la negociacion: solo lee los CSV que ya escriben los
 * loggers y el estado en memoria del simulador. Ver DOCUMENTACION_REGISTROS.md.
 */
public final class CierrePlanInicial {

    private static boolean iniciado = false;

    private CierrePlanInicial() {}

    /**
     * Se llama al inicio de MainContainer.main, antes de borrar los CSV de la
     * corrida anterior y de crear los agentes. Si la carpeta de la etiqueta ya
     * existe, la corrida NO arranca: asi no se sobrescriben resultados.
     */
    public static void verificarEtiquetaLibre() {
        if (!ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL) return;
        File dir = new File("corridas", ParametrosSimulador.ETIQUETA_ESCENARIO);
        if (!dir.exists()) return;
        String texto = "La carpeta " + dir.getPath() + " ya existe.\n"
                + "Cambia ParametrosSimulador.ETIQUETA_ESCENARIO (o mueve esa carpeta)\n"
                + "y vuelve a lanzar. La corrida NO arranca para no sobrescribir resultados.";
        System.err.println("[CierrePlanInicial] ERROR: " + texto.replace('\n', ' '));
        try {
            JOptionPane.showMessageDialog(null, texto, "Etiqueta repetida", JOptionPane.ERROR_MESSAGE);
        } catch (Exception e) {
            // sin entorno grafico: basta con el aviso por consola
        }
        System.exit(1);
    }

    /**
     * Genera los archivos, muestra el resumen y apaga. Se invoca una sola vez,
     * justo despues de PlanBase.capturar.
     *
     * @param motivo           motivo con que termino la negociacion inicial
     * @param tCapturaMs       reloj de pared en el instante de la captura
     * @param tFinDetectadoMs  reloj de pared en que se detecto el fin de la
     *                         negociacion (antes de esperar las copias)
     */
    public static synchronized void cerrar(String motivo, long tCapturaMs, long tFinDetectadoMs) {
        if (iniciado) return;
        iniciado = true;
        // Fuera del hilo de Swing: leer messages.csv puede tardar en el escenario grande.
        Thread t = new Thread(() -> {
            File dir = new File("corridas", ParametrosSimulador.ETIQUETA_ESCENARIO);
            EstadisticasPlanInicial.Resultado r = null;
            String error = null;
            try {
                r = EstadisticasPlanInicial.generar(dir, motivo, tCapturaMs, tFinDetectadoMs);
            } catch (Exception e) {
                error = e.toString();
                System.err.println("[CierrePlanInicial] no se pudieron generar los archivos de resumen: " + e);
                e.printStackTrace();
            }
            final EstadisticasPlanInicial.Resultado res = r;
            final String err = error;
            SwingUtilities.invokeLater(() -> {
                mostrarDialogo(dir, motivo, res, err);
                apagar();
            });
        }, "cierre-plan-inicial");
        t.start();
    }

    private static void mostrarDialogo(File dir, String motivo, EstadisticasPlanInicial.Resultado r, String error) {
        boolean valida = "negociacion_completa".equals(motivo);
        StringBuilder s = new StringBuilder();
        if (!valida) {
            s.append("CORRIDA NO VÁLIDA: la negociación inicial terminó por \"").append(motivo).append("\".\n\n");
        }
        s.append("Escenario: ").append(ParametrosSimulador.ETIQUETA_ESCENARIO).append('\n');
        if (r != null) {
            s.append(String.format(Locale.ROOT, "Negociación inicial: %.1f s (%d negociaciones, %d con ganador)%n",
                    r.duracionNegociacionMs / 1000.0, r.negociacionesTotal, r.negociacionesConGanador));
            s.append(String.format(Locale.ROOT, "Plan: %d ciclos, %.0f t planificadas (%.0f t dentro del turno)%n",
                    r.ciclosTotal, r.toneladasTotal, r.toneladasEnTurno));
            s.append(String.format(Locale.ROOT, "Camiones con trabajo: %d de %d%n", r.camionesConTrabajo, r.flotaTotal));
            s.append(String.format(Locale.ROOT, "Uso promedio de palas: %.1f %%  |  solapes: %d (%.1f min)%n",
                    r.usoPromedioPct, r.solapesTotal, r.minutosSolapadosTotal));
            s.append(String.format(Locale.ROOT, "Mensajes: %d entregas (%d envíos)%n", r.mensajesTotal, r.enviosTotal));
            if (!r.advertencias.isEmpty()) s.append("Advertencias: ").append(r.advertencias.size())
                    .append(" (ver resumen_plan.txt)\n");
        } else {
            s.append("ERROR al generar los archivos de resumen: ").append(error).append('\n');
        }
        s.append("\nResultados en: ").append(dir.getAbsolutePath());
        System.out.println("[CierrePlanInicial] " + s.toString().replace('\n', ' '));

        String[] opciones = {"Abrir carpeta de resultados", "Cerrar"};
        int op;
        try {
            op = JOptionPane.showOptionDialog(null, s.toString(),
                    valida ? "Plan inicial generado" : "CORRIDA NO VÁLIDA - plan inicial generado",
                    JOptionPane.DEFAULT_OPTION,
                    valida ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                    null, opciones, opciones[0]);
        } catch (Exception e) {
            return;  // sin entorno grafico
        }
        if (op == 0) abrirCarpeta(dir);
    }

    private static void abrirCarpeta(File dir) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
                return;
            }
        } catch (Exception e) {
            System.err.println("[CierrePlanInicial] Desktop.open fallo: " + e);
        }
        // Respaldo (algunos escritorios Linux no soportan Desktop.open).
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
            new ProcessBuilder(cmd, dir.getAbsolutePath()).start();
        } catch (Exception e) {
            System.err.println("[CierrePlanInicial] no se pudo abrir la carpeta: " + e);
        }
    }

    /**
     * Apaga JADE de forma limpia: matar el contenedor principal termina la
     * plataforma completa (agentes, AMS/DF y el puerto 1099). Si algo se
     * cuelga, a los 10 s se termina la JVM igual.
     */
    private static void apagar() {
        Thread guardia = new Thread(() -> {
            try { Thread.sleep(10_000L); } catch (InterruptedException e) { return; }
            System.err.println("[CierrePlanInicial] AVISO: el apagado de JADE no termino en 10 s; se fuerza la salida.");
            Runtime.getRuntime().halt(0);
        }, "guardia-apagado");
        guardia.setDaemon(true);
        guardia.start();

        new Thread(() -> {
            try {
                if (MainContainer.contenedorPrincipal != null) {
                    MainContainer.contenedorPrincipal.kill();
                }
                jade.core.Runtime.instance().shutDown();
                System.out.println("[CierrePlanInicial] JADE apagado.");
            } catch (Exception e) {
                System.err.println("[CierrePlanInicial] error al apagar JADE: " + e);
            }
            System.exit(0);
        }, "apagado-jade").start();
    }
}
