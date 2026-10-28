import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Capturador automatico de snapshots de la ventana del simulador.
 *
 * Dos modos combinables:
 *   - Periodico: dispara cada INTERVALO_PERIODICO_MS de reloj real (que en este
 *     simulador equivale a tiempo de simulacion porque corre en tiempo real).
 *   - Por evento: lo invoca quien quiera dejar evidencia visual de un instante
 *     (ej. ReschedulingAgent antes/despues de un right-shift).
 *
 * Salida: snapshots/corrida_<timestamp>/snap_<tiempoSim>_<tipo>_<seq>.png
 *         + manifest.csv con la lista de capturas y a que evento corresponden.
 *
 * Singleton. Se inicializa una vez desde InicializadorSimulador despues de
 * crear la VentanaSimulacion. Si nunca se llama a iniciar(), capturarPorEvento
 * queda como no-op (seguro para invocar desde ReschedulingAgent aunque la
 * ventana no este abierta).
 */
public class CapturadorSnapshots {

    // ── Parametros ───────────────────────────────────────────────────────────
    /**
     * Intervalo entre snapshots periodicos en ms de TIEMPO DE SIMULACION
     * (no tiempo real). Como la simulacion suele correr acelerada (multiplicador
     * x25-x100), medir contra reloj real haria que nunca dispare. Asi 1h sim
     * = 1 snapshot, independiente de la velocidad que pongas en el slider.
     */
    public static final long INTERVALO_PERIODICO_SIM_MS = 60 * 60 * 1000L;  // 1 hora SIM

    /** Cada cuanto el watcher chequea el estado pausa/play (ms de reloj real). */
    public static final long INTERVALO_CHECK_MS         = 30 * 1000L;       // 30 s real

    // ── Singleton ────────────────────────────────────────────────────────────
    private static final CapturadorSnapshots INSTANCIA = new CapturadorSnapshots();
    public static CapturadorSnapshots getInstancia() { return INSTANCIA; }
    private CapturadorSnapshots() {}

    // ── Estado ───────────────────────────────────────────────────────────────
    private VentanaSimulacion ventana;
    private File carpetaCorrida;
    private File manifest;
    private Timer timerWatcher;
    private final AtomicInteger secuencia = new AtomicInteger(0);
    private boolean activo = false;

    /** Marca de la ultima captura periodica (tiempo de SIMULACION en ms). */
    private long ultimaCapturaPeriodicaSimMs = 0;
    /** True una vez que ya hicimos la captura "inicio" tras el primer Play. */
    private boolean capturaInicialHecha = false;
    /** IDs de eventos cuyo punto medio ya capturamos (evita duplicar). */
    private final Set<String> eventosMedioCapturados = new HashSet<>();

    // ── API publica ──────────────────────────────────────────────────────────

    /**
     * Arranca el capturador. Crea la carpeta de la corrida y dispara el timer
     * periodico. Idempotente: llamadas repetidas se ignoran.
     */
    public synchronized void iniciar(VentanaSimulacion ventana) {
        if (activo) return;
        if (ventana == null) {
            System.err.println("[snapshots] ventana null, capturador no se inicia.");
            return;
        }
        this.ventana = ventana;

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        carpetaCorrida = new File("snapshots", "corrida_" + stamp);
        if (!carpetaCorrida.mkdirs() && !carpetaCorrida.exists()) {
            System.err.println("[snapshots] no se pudo crear " + carpetaCorrida);
            return;
        }
        manifest = new File(carpetaCorrida, "manifest.csv");
        escribirManifestHeader();

        // Un solo watcher liviano que tick cada 30s. Decide adentro si:
        //   - hay que hacer la captura "inicio" (primer Play del usuario), o
        //   - ya paso 1h desde la ultima captura periodica.
        // Asi el primer snapshot cae apenas el usuario le da Play, no a los 60min.
        timerWatcher = new Timer((int) INTERVALO_CHECK_MS, e -> watcherTick());
        timerWatcher.setRepeats(true);
        timerWatcher.start();

        activo = true;
        System.out.println("[snapshots] capturador activo. carpeta=" + carpetaCorrida
                + " periodico=" + (INTERVALO_PERIODICO_SIM_MS / 60_000) + "min SIM"
                + " check=" + (INTERVALO_CHECK_MS / 1_000) + "s real");
    }

    /** Logica que corre cada INTERVALO_CHECK_MS: decide si toca capturar. */
    private void watcherTick() {
        if (ventana == null || !ventana.isShowing() || ventana.isPausado()) return;

        long tSimAhora = ventana.getTiempoSimulacionMs();

        // 1. Captura inicial: la primera vez que detectamos Play, dejamos foto
        //    del estado de arranque para tener referencia.
        if (!capturaInicialHecha) {
            capturaInicialHecha = true;
            ultimaCapturaPeriodicaSimMs = tSimAhora;
            capturar("inicio");
            return;
        }

        // 2. Captura periodica: cada INTERVALO_PERIODICO_SIM_MS de TIEMPO SIM.
        //    Si la sim corre a x25, 1h sim = ~2.4 min reales — la captura
        //    igualmente dispara cuando el reloj de la ventana avanza 1h. Si la
        //    sim esta pausada, tSimAhora no avanza y nada dispara.
        if ((tSimAhora - ultimaCapturaPeriodicaSimMs) >= INTERVALO_PERIODICO_SIM_MS) {
            ultimaCapturaPeriodicaSimMs = tSimAhora;
            capturar("periodico");
        }

        // 3. Captura al MEDIO de cada evento activo. Recorre todos los eventos
        //    cargados en GestorEventos (clima predefinido + averias + eventos
        //    que el usuario agrego en la UI). Para cada uno cuyo punto medio
        //    ya paso pero el evento sigue activo, captura una vez. La etiqueta
        //    incluye el tipo y target para que en el manifest se vea claro.
        try {
            GestorEventos gestor = GestorEventos.getInstancia();
            if (gestor != null) {
                for (EventoSimulacion ev : gestor.getEventos()) {
                    if (ev == null) continue;
                    long medio = ev.getHoraInicioMs() + ev.getDuracionMs() / 2;
                    if (tSimAhora < medio || !ev.estaActivoEn(tSimAhora)) continue;

                    String idEv = ev.getTipo().name() + "@" + ev.getHoraInicioMs()
                            + (ev.getTargetId() != null ? "_" + ev.getTargetId() : "");
                    if (eventosMedioCapturados.contains(idEv)) continue;
                    eventosMedioCapturados.add(idEv);

                    String etiqueta = "medio_" + ev.getTipo().name()
                            + (ev.getTargetId() != null
                                ? "_" + sanear(ev.getTargetId()) : "");
                    capturar(etiqueta);
                }
            }
        } catch (Exception e) {
            // Si GestorEventos todavia no esta listo, ignoramos sin matar el watcher.
        }
    }

    /**
     * Captura por evento. Seguro de llamar desde cualquier hilo (incluido el
     * de JADE) — el render real se hace en el EDT. No-op si el capturador
     * todavia no fue iniciado.
     *
     * @param tipoEvento etiqueta corta que va en el nombre del PNG y en el
     *                   manifest (ej. "antes_rightshift", "despues_rightshift").
     */
    public void capturarPorEvento(String tipoEvento) {
        if (!activo) return;
        capturar(tipoEvento);
    }

    public synchronized void detener() {
        if (timerWatcher != null) timerWatcher.stop();
        activo = false;
    }

    // ── Captura ──────────────────────────────────────────────────────────────

    private void capturar(String tipoEvento) {
        // El render del JFrame debe correr en el EDT para que pinte el estado
        // consistente. Lo hacemos asincrono (invokeLater) para no bloquear al
        // ReschedulingAgent ni al timer.
        SwingUtilities.invokeLater(() -> {
            try {
                if (ventana == null || !ventana.isShowing()) return;

                // Si la simulacion esta pausada (al inicio mientras carga, o si el
                // usuario pausa a mitad), no tiene sentido acumular snapshots
                // identicos. Saltamos silenciosamente. Cuando se reanude, el
                // proximo tick del timer caera y captura normalmente.
                if (ventana.isPausado()) return;

                long tSim = ventana.getTiempoSimulacionMs();
                int seq = secuencia.incrementAndGet();

                int w = ventana.getWidth();
                int h = ventana.getHeight();
                if (w <= 0 || h <= 0) return;

                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                // printAll captura tambien bordes, decoracion y subcomponentes.
                ventana.printAll(g);
                g.dispose();

                String nombre = String.format("snap_%s_%s_%03d.png",
                        formatearTiempoSim(tSim), sanear(tipoEvento), seq);
                File destino = new File(carpetaCorrida, nombre);
                ImageIO.write(img, "PNG", destino);

                anotarManifest(seq, tSim, tipoEvento, nombre);
            } catch (IOException e) {
                System.err.println("[snapshots] error guardando PNG: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("[snapshots] error inesperado: " + e.getMessage());
            }
        });
    }

    // ── Manifest ─────────────────────────────────────────────────────────────

    private void escribirManifestHeader() {
        try (FileWriter w = new FileWriter(manifest, false)) {
            w.write("seq,tiempoSimMs,tiempoSimHHMMSS,tipoEvento,archivo,wallClock\n");
        } catch (IOException e) {
            System.err.println("[snapshots] no pude crear manifest: " + e.getMessage());
        }
    }

    private void anotarManifest(int seq, long tSim, String tipoEvento, String archivo) {
        try (FileWriter w = new FileWriter(manifest, true)) {
            String wallClock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            w.write(seq + "," + tSim + "," + Utilidades.longToHHMMSS(tSim) + ","
                    + sanear(tipoEvento) + "," + archivo + "," + wallClock + "\n");
        } catch (IOException e) {
            System.err.println("[snapshots] no pude escribir manifest: " + e.getMessage());
        }
    }

    // ── Utiles ───────────────────────────────────────────────────────────────

    private String formatearTiempoSim(long ms) {
        long totalSeg = ms / 1000;
        long h = totalSeg / 3600;
        long m = (totalSeg % 3600) / 60;
        return String.format("t%02dh%02dm", h, m);
    }

    private String sanear(String s) {
        if (s == null) return "evt";
        return s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
