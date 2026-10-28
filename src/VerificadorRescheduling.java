import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verificador de Partial Repair (v2, con RE-CHEQUEO).
 *
 * ─── Por que v2 ────────────────────────────────────────────────────────────
 * La v1 chequeaba UNA sola vez a +30s real y comparaba IDENTIDAD de objetos
 * Slot. Daba ~87% de "huerfanos" que eran FALSOS POSITIVOS:
 *   - La renegociacion CNP tarda con frecuencia mas de 30s (varias
 *     negociaciones encadenadas, deadline 1s c/u, palas tick cada 2s).
 *   - Si el camion renegociaba y se re-liberaba antes del chequeo, los slots
 *     nuevos desaparecian y el verificador los contaba como 0.
 * Prueba de que eran falsos: 20+ camiones se liberaron DOS veces — solo
 * posible si renegociaron (consiguieron slots) entre liberaciones.
 *
 * ─── Como funciona v2 ──────────────────────────────────────────────────────
 * Re-chequeo periodico (lo llama el TickerBehaviour del ReschedulingAgent,
 * cada INTERVALO_CHEQUEO). Para cada liberacion pendiente:
 *   - Si el camion YA tiene algun slot con horaInicio > corte → RENEGOCIO.
 *     Se registra exito con el tiempo real que tardo. Se quita de pendientes.
 *   - Si todavia no, y no se agoto el TIMEOUT → se deja pendiente (reintenta).
 *   - Si se agoto el TIMEOUT sin renegociar → HUERFANO definitivo.
 *
 * Criterio robusto: "tiene algun slot futuro al corte" (no identidad de
 * objetos). Tolera re-liberaciones: si el camion renegocio aunque sea una vez,
 * tendra slots > corte.
 *
 * ─── Salida ───────────────────────────────────────────────────────────────
 * verificacion_partial_repair.csv, una fila por liberacion RESUELTA:
 *   timestamp, escenario, camion, corte_ms, corte,
 *   slots_antes_lib, slots_post_lib, slots_futuros_al_corte,
 *   huerfano, dt_real_resolucion_ms
 *
 * - slots_futuros_al_corte: cuantos slots con horaInicio>corte tiene al
 *   resolverse (>0 = renegocio, 0 = huerfano).
 * - dt_real_resolucion_ms: ms reales desde la liberacion hasta que se confirmo
 *   renegociacion, o hasta el timeout si quedo huerfano.
 */
public class VerificadorRescheduling {

    /** Tiempo maximo (real) que esperamos a que un camion renegocie antes
     *  de declararlo huerfano definitivo. */
    public static final long TIMEOUT_RENEGOCIACION_MS = 180_000L;  // 3 min real

    private static final String ARCHIVO = "verificacion_partial_repair.csv";

    private static final String HEADER = String.join(",",
            "timestamp", "escenario", "camion",
            "corte_ms", "corte",
            "slots_antes_lib", "slots_post_lib", "slots_futuros_al_corte",
            "huerfano", "dt_real_resolucion_ms");

    private static final VerificadorRescheduling INSTANCIA = new VerificadorRescheduling();
    public static VerificadorRescheduling getInstancia() { return INSTANCIA; }
    private VerificadorRescheduling() {}

    /** Una verificacion en curso. */
    private static class Pendiente {
        final String camion;
        final long   corte;
        final long   tRealDisparo;   // wall clock de la liberacion
        final long   tRealDeadline;  // disparo + timeout
        final int    slotsAntesLib;
        final int    slotsPostLib;

        Pendiente(String camion, long corte, long tRealDisparo, int antes, int post) {
            this.camion        = camion;
            this.corte         = corte;
            this.tRealDisparo  = tRealDisparo;
            this.tRealDeadline = tRealDisparo + TIMEOUT_RENEGOCIACION_MS;
            this.slotsAntesLib = antes;
            this.slotsPostLib  = post;
        }
    }

    private final ConcurrentHashMap<Long, Pendiente> pendientes = new ConcurrentHashMap<>();
    private long seq = 0;

    /**
     * Renegociaciones todavia sin resolver. Lo consulta EpisodioRescheduling
     * para decidir si el cursor de simulacion sigue congelado: mientras haya
     * pendientes la negociacion no convergio, y mientras el numero BAJE hay
     * progreso. Si se estanca, los que queden son huerfanos.
     */
    public int cantidadPendientes() { return pendientes.size(); }

    /**
     * Llamado desde ReschedulingAgent justo despues de liberar.
     * @param tRealAhora wall clock (System.currentTimeMillis()).
     * @param slotsAntesLib count de slots del camion antes de liberar.
     */
    public synchronized void agendarVerificacion(String camion, long corte,
                                                  long tRealAhora,
                                                  int slotsAntesLib) {
        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        int slotsPost = 0;
        if (sch != null) {
            synchronized (sch) { slotsPost = sch.getSlots().size(); }
        }
        pendientes.put(++seq, new Pendiente(camion, corte, tRealAhora,
                slotsAntesLib, slotsPost));
    }

    /**
     * Re-chequeo periodico. Resuelve los pendientes que ya renegociaron o que
     * agotaron el timeout. Los demas siguen pendientes para el proximo tick.
     * @param tRealAhora wall clock.
     */
    public synchronized void chequearPendientes(long tRealAhora) {
        List<Long> aRemover = new ArrayList<>();
        for (java.util.Map.Entry<Long, Pendiente> e : pendientes.entrySet()) {
            Pendiente p = e.getValue();
            int futuros = contarSlotsFuturosAlCorte(p.camion, p.corte);

            if (futuros > 0) {
                // Renegocio con exito.
                registrar(p, futuros, false, tRealAhora - p.tRealDisparo);
                aRemover.add(e.getKey());
            } else if (tRealAhora >= p.tRealDeadline && !EpisodioRescheduling.estaAbierto()) {
                // Se agoto el timeout sin renegociar → huerfano definitivo.
                // Mientras el episodio sigue abierto NO se declara huerfano a
                // nadie: las palas todavia estan recorriendo su tiempo libre y
                // la renegociacion dura mas que este timeout (en grande_V4_*
                // unos 7 min reales). Antes 33 de 48 "huerfanos" de la
                // regeneracion completa si consiguieron tarea despues. Al
                // cerrar el episodio, resolverPendientesComoHuerfanos decide.
                registrar(p, 0, true, tRealAhora - p.tRealDisparo);
                aRemover.add(e.getKey());
            }
            // else: sigue pendiente, se reintenta en el proximo tick.
        }
        for (Long k : aRemover) pendientes.remove(k);
    }

    /**
     * Cierra como HUERFANOS todos los pendientes que quedan, y devuelve
     * cuantos fueron. Lo llama EpisodioRescheduling cuando la negociacion
     * dejo de progresar.
     *
     * DEFINICION ESTRUCTURAL DEL HUERFANO. Antes un camion era huerfano
     * porque se agotaba un timeout de 180 s de RELOJ DE PARED, asi que la
     * metrica principal de la tesis dependia de la velocidad de reproduccion
     * y de la maquina. Ahora es huerfano porque el CNP DEJO DE PODER
     * COLOCARLO: el episodio se cierra cuando la cantidad de pendientes no
     * baja, y los que sobran no encontraron pala. Eso es una propiedad del
     * sistema, no del cronometro.
     */
    public synchronized int resolverPendientesComoHuerfanos() {
        long ahora = System.currentTimeMillis();
        int n = 0;
        for (java.util.Map.Entry<Long, Pendiente> e : pendientes.entrySet()) {
            Pendiente p = e.getValue();
            int futuros = contarSlotsFuturosAlCorte(p.camion, p.corte);
            // Ultimo chequeo: si alcanzo a renegociar entre el ultimo tick y
            // el cierre, no es huerfano.
            boolean huerfano = futuros == 0;
            registrar(p, futuros, huerfano, ahora - p.tRealDisparo);
            if (huerfano) n++;
        }
        pendientes.clear();
        return n;
    }

    /** Cuenta slots del camion con horaInicio > corte (= renegociados). */
    private int contarSlotsFuturosAlCorte(String camion, long corte) {
        Schedule sch = RegistroSimulacion.getInstancia().getAgendasCamiones().get(camion);
        if (sch == null) return 0;
        int n = 0;
        synchronized (sch) {
            for (Slot s : sch.getSlots()) {
                if (s.getHoraInicio() > corte) n++;
            }
        }
        return n;
    }

    private void registrar(Pendiente p, int futuros, boolean huerfano, long dtReal) {
        boolean existe = new File(ARCHIVO).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(ARCHIVO, true))) {
            if (!existe) w.println(HEADER);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
            w.println(String.join(",",
                    timestamp,
                    escape(ParametrosSimulador.ETIQUETA_ESCENARIO),
                    escape(p.camion),
                    Long.toString(p.corte),
                    Utilidades.longToHHMMSS(p.corte),
                    Integer.toString(p.slotsAntesLib),
                    Integer.toString(p.slotsPostLib),
                    Integer.toString(futuros),
                    Boolean.toString(huerfano),
                    Long.toString(dtReal)));
        } catch (IOException e) {
            System.err.println("[Verificador] error escribiendo " + ARCHIVO + ": " + e.getMessage());
        }

        if (huerfano) {
            System.err.printf("[Verificador] HUERFANO def.: %s no renegocio en %.0fs real (timeout).%n",
                    p.camion, dtReal / 1000.0);
        } else {
            System.out.printf("[Verificador] OK: %s renegocio %d slots futuros en %.0fs real.%n",
                    p.camion, futuros, dtReal / 1000.0);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
