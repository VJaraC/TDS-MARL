import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Motor del simulador. Lee el archivo "&lt;prefijo&gt;schedules.csv" (que ya
 * escribe Schedule.agregarSlot via CsvSchedules) periódicamente, detecta
 * filas NUEVAS desde la última lectura, y vuelca los slots que corresponden
 * a CAMIONES al RegistroSimulacion.
 *
 * Por qué solo camiones: el CSV de schedules contiene slots de los tres
 * agentes (ShovelAgent, TruckAgent, UnloadingPointAgent). Para el simulador
 * solo nos interesan los del camión, que es quien se mueve por el mapa.
 * Filtramos por "maquinaPropietariaSchedule" empezando con "Truck".
 *
 * Implementación: usa RandomAccessFile con seek a la última posición leída.
 * Si una línea está parcialmente escrita (sin '\n') la deja para el próximo
 * tick. NO toca el código de los agentes.
 */
public class LectorCSVSchedules {

    private final File archivo;
    private long ultimaPosicion = 0;
    private boolean primeraLectura = true;
    private Timer timer;
    private final Set<String> firmasSlotsCargados = new HashSet<>();

    public LectorCSVSchedules() {
        this.archivo = new File(ParametrosConfiguracion.prefijoArchivos() + "schedules.csv");
    }

    /**
     * Comienza el polling. Hilo daemon para no bloquear la salida del programa.
     */
    public void iniciar() {
        timer = new Timer("LectorCSVSchedules", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                try { procesarLineasNuevas(); }
                catch (Exception e) {
                    System.err.println("[LectorCSVSchedules] error: " + e.getMessage());
                }
            }
        }, ParametrosSimulador.POLLING_CSV_MS, ParametrosSimulador.POLLING_CSV_MS);
    }

    public void detener() {
        if (timer != null) timer.cancel();
    }

    /**
     * Lee del CSV todo lo escrito desde la última posición leída. Por cada
     * línea completa (terminada en '\n'), la procesa. Si la última línea está
     * incompleta, NO actualiza ultimaPosicion para reintentarla en el próximo tick.
     */
    private synchronized void procesarLineasNuevas() throws IOException {
        if (!archivo.exists()) return;

        try (RandomAccessFile raf = new RandomAccessFile(archivo, "r")) {
            long tamanoActual = raf.length();
            if (tamanoActual < ultimaPosicion) {
                // El archivo se truncó (nueva corrida) → reiniciamos
                ultimaPosicion = 0;
                primeraLectura = true;
                firmasSlotsCargados.clear();
            }
            if (tamanoActual == ultimaPosicion) return;

            raf.seek(ultimaPosicion);
            ArrayList<String> lineas = new ArrayList<>();
            ArrayList<Long> inicios = new ArrayList<>();   // byte donde empieza cada linea
            String linea;
            long posicionTrasUltimaLineaCompleta = ultimaPosicion;
            long inicioLinea = raf.getFilePointer();
            while ((linea = raf.readLine()) != null) {
                long posicionAhora = raf.getFilePointer();
                // Si la línea es la última y el archivo NO termina en '\n',
                // está incompleta. La detectamos comparando posición.
                if (posicionAhora == tamanoActual && !lineaTerminaEnNuevaLinea(raf, tamanoActual)) {
                    break;
                }
                lineas.add(linea);
                inicios.add(inicioLinea);
                posicionTrasUltimaLineaCompleta = posicionAhora;
                inicioLinea = posicionAhora;
            }
            ultimaPosicion = posicionTrasUltimaLineaCompleta;

            // Saltar la cabecera si es la primera lectura del archivo.
            int desde = 0;
            if (primeraLectura && !lineas.isEmpty()) {
                if (lineas.get(0).startsWith("maquinaPropietariaSchedule")) desde = 1;
                primeraLectura = false;
            }
            for (int i = desde; i < lineas.size(); i++) {
                procesarLinea(lineas.get(i), inicios.get(i));
            }
        }
    }

    private boolean lineaTerminaEnNuevaLinea(RandomAccessFile raf, long tamano) throws IOException {
        if (tamano == 0) return false;
        long pos = raf.getFilePointer();
        raf.seek(tamano - 1);
        int b = raf.read();
        raf.seek(pos);
        return b == '\n' || b == '\r';
    }

    /**
     * Cada línea del CSV de schedules tiene este formato (de CsvSchedules):
     *   maquinaPropietariaSchedule, maquina, operacion, horaInicio, horaFin,
     *   ubicacion, cantidadMaterial, horaInicioHHMMSS, horaFinHHMMSS,
     *   duracionActividad, tiempoInactivoDesdeActividadAnterior
     */
    private void procesarLinea(String linea, long posicionEnArchivo) {
        String[] campos = linea.split(",", -1);
        if (campos.length < 11) return;

        String propietario = campos[0].trim();
        // Solo nos interesan los slots cuyo propietario es un CAMIÓN.
        if (!propietario.startsWith("Truck")) return;

        try {
            String maquina    = campos[1].trim();
            String operacion  = campos[2].trim();
            long horaInicio   = Long.parseLong(campos[3].trim());
            long horaFin      = Long.parseLong(campos[4].trim());
            String ubicacion  = campos[5].trim();
            double cantidad   = Double.parseDouble(campos[6].trim());

            // Firma unica para no cargar dos veces LA MISMA FILA si el polling la
            // relee. Es la posicion de la fila en el archivo y no su contenido:
            // con el contenido (camion|op|inicio|fin|ubicacion), un slot que se
            // libero y despues se volvio a negociar con exactamente los mismos
            // horarios se descartaba, y el simulador perdia esa asignacion
            // aunque el camion, la pala y el destino si la tenian.
            String firma = propietario + "@" + posicionEnArchivo;
            if (!firmasSlotsCargados.add(firma)) return;

            Slot.enum_operacion op;
            try { op = Slot.enum_operacion.valueOf(operacion); }
            catch (IllegalArgumentException e) { return; }

            Slot slot = new Slot();
            slot.setMaquinaPropietariaSchedule(propietario);
            slot.setMaquina(maquina);
            slot.setOperacion(op);
            slot.setHoraInicio(horaInicio);
            slot.setHoraFin(horaFin);
            slot.setUbicacion(ubicacion);
            slot.setCantidadMaterial(cantidad);

            // Factor con el que el camion planifico este viaje: el mismo que usa
            // TruckAgent.generaPropuesta (factor propio x clima del reloj actual,
            // que esta congelado mientras se negocia). CalculadorScheduleReal lo
            // descuenta para no aplicar dos veces el clima y el factor propio.
            // Desde el 2026-10-05 el camion aplica el clima solo mientras dura
            // (GestorEventos.duracionConClimaConocido), asi que el factor del
            // plan es el promedio del viaje: trabajo sin clima / duracion.
            // Sin clima activo da el factor propio, igual que antes.
            if (op == Slot.enum_operacion.VIAJE_VACIO || op == Slot.enum_operacion.VIAJE_CARGADO) {
                double factorPlan = RegistroSimulacion.getInstancia().getFactorVelocidadCamion(propietario);
                long dur = horaFin - horaInicio;
                if (dur > 0) {
                    factorPlan *= GestorEventos.getInstancia().trabajoConClimaConocido(
                            horaInicio, horaFin, VentanaSimulacion.tiempoSimulacionGlobalMs) / dur;
                }
                RegistroSimulacion.getInstancia().registrarFactorPlan(slot, factorPlan);
            }

            // Agregar directamente a la lista del schedule, SIN llamar a
            // schedule.agregarSlot() que volvería a loguear al CSV.
            Schedule sch = RegistroSimulacion.getInstancia().obtenerOCrearSchedule(propietario);
            synchronized (sch) {
                sch.getSlots().add(slot);
                // Reordenar por hora de inicio (la ventana asume ordenado).
                sch.getSlots().sort((a, b) -> Long.compare(a.getHoraInicio(), b.getHoraInicio()));
            }
        } catch (NumberFormatException e) {
            // Línea corrupta, la ignoramos.
        }
    }
}