import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reservas provisorias de un punto de descarga (2026-10-07).
 *
 * Problema: antes de proponerle a una pala, el camion le pregunta al punto de
 * descarga a que hora puede descargar. El UnloadingPointAgent respondia, pero
 * no anotaba nada hasta que el camion confirmaba (INFORM). Al inicio del turno
 * muchos camiones preguntan a la vez y todos recibian la misma hora: en
 * grande_V5_PARTIAL el chancador CS03 (capacidad 1) le dijo "0:14:03" a seis
 * camiones en 0,2 s, tres ganaron y quedaron descargando juntos en el plan.
 *
 * Arreglo: al responder se guarda esa hora como reserva PROVISORIA del camion
 * durante unos segundos (lo que dura una negociacion). Mientras tanto, a otro
 * camion se le responde con la siguiente hora libre. Si el camion confirma, su
 * descarga queda firme y sus reservas se sueltan; si no confirma (perdio, o
 * eligio otra pala), la reserva vence sola. Las reservas del mismo camion no
 * lo bloquean a el (puede estar evaluando varias palas a la vez y solo va a
 * confirmar una).
 *
 * Sin preguntas simultaneas el resultado es el mismo que antes.
 */
public class ReservasDescarga {

    /** Cuanto dura una reserva provisoria, en tiempo real: el plazo de una
     *  negociacion (5 s) mas un margen para la confirmacion. */
    public static final long DURACION_RESERVA_REAL_MS = 8_000L;

    private static class Reserva {
        final String camion; final long ini, fin, vence;
        Reserva(String camion, long ini, long fin, long vence) {
            this.camion = camion; this.ini = ini; this.fin = fin; this.vence = vence;
        }
    }

    private final List<Reserva> reservas = new ArrayList<>();

    public synchronized void reservar(String camion, long ini, long fin, long ahoraReal) {
        reservas.add(new Reserva(camion, ini, fin, ahoraReal + DURACION_RESERVA_REAL_MS));
    }

    /** Suelta todas las reservas del camion (confirmo su descarga, o fue liberado). */
    public synchronized void soltar(String camion) {
        Iterator<Reserva> it = reservas.iterator();
        while (it.hasNext()) if (it.next().camion.equals(camion)) it.remove();
    }

    /** Intervalos {ini, fin} de las reservas vigentes de OTROS camiones. */
    public synchronized List<long[]> ocupadasPorOtros(String camion, long ahoraReal) {
        List<long[]> out = new ArrayList<>();
        Iterator<Reserva> it = reservas.iterator();
        while (it.hasNext()) {
            Reserva r = it.next();
            if (r.vence <= ahoraReal) { it.remove(); continue; }
            if (!r.camion.equals(camion)) out.add(new long[]{r.ini, r.fin});
        }
        return out;
    }

    public synchronized int cantidad() { return reservas.size(); }

    /**
     * Primer inicio >= llegada en que el intervalo [inicio, inicio+duracion)
     * se cruza con menos de 'capacidad' descargas. Es el mismo criterio de
     * UnloadingPointAgent.encontrarProximoSlotDisponible, aplicado a una lista
     * de intervalos (descargas firmes + reservas de otros camiones).
     */
    public static long proximoInicio(List<long[]> ocupados, long llegada, long duracion, int capacidad) {
        int cap = Math.max(1, capacidad);
        long inicio = llegada;
        while (true) {
            int n = 0;
            long proximoFin = Long.MAX_VALUE;
            for (long[] o : ocupados) {
                if (o[0] < inicio + duracion && o[1] > inicio) {
                    n++;
                    if (o[1] < proximoFin) proximoFin = o[1];
                }
            }
            if (n < cap || proximoFin == Long.MAX_VALUE) return inicio;
            inicio = proximoFin;
        }
    }
}
