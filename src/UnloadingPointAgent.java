import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.UnreadableException;
import java.io.IOException;

/**
 * Agente que representa un punto de descarga (Crusher, Wastedump, Stockpile).
 * Recibe REQUEST de TruckAgents con su ventana de descarga propuesta.
 * Responde AGREE con el horario de inicio de descarga (ajustado si hay solapamiento),
 * pero NO agrega el slot al schedule hasta recibir INFORM del TruckAgent,
 * que confirma que el camión efectivamente va a descargar.
 */
public class UnloadingPointAgent extends Agent {

    private Schedule schedule;
    private int maximumDischarge;
    private String ubicacion;
    // Reservas provisorias mientras el camion negocia (2026-10-07): ver
    // ReservasDescarga. Evitan que dos camiones que preguntan a la vez reciban
    // la misma hora en un destino de capacidad 1 (chancador).
    private final ReservasDescarga reservas = new ReservasDescarga();

    @Override
    protected void setup() {
        schedule = new Schedule();
        RegistroAgendasAgentes.registrar(getLocalName(), RegistroAgendasAgentes.DESCARGA, schedule); // solo observacion
        Object[] args = getArguments();
        if (args != null && args.length >= 2) {
            ubicacion = (String) args[0];
            maximumDischarge = (int) args[1];
        }
        // Plan inicial unico (14-oct-2026): con USAR_PLAN_INICIAL_FIJO la agenda
        // viene de un archivo en vez de la negociacion inicial. Sin el, no hace nada.
        PlanInicialFijo.cargarEn(getLocalName(), RegistroAgendasAgentes.DESCARGA, schedule);
        System.out.println(getLocalName() + " listo. maximumDischarge=" + maximumDischarge + " ubicacion=" + ubicacion);

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    switch (msg.getPerformative()) {
                        case ACLMessage.REQUEST:
                            handleRequest(msg);
                            break;
                        case ACLMessage.INFORM:
                            // Distinguir INFORM de rescheduling del INFORM normal
                            // de confirmación de descarga (TruckAgent → registrar slot).
                            if (msg.getConversationId() != null
                                    && msg.getConversationId().startsWith("RESCHEDULE-")) {
                                handleRescheduleInform(msg);
                            } else if (msg.getConversationId() != null
                                    && msg.getConversationId().startsWith("LIBERAR-")) {
                                handleLiberarInform(msg);
                            } else {
                                handleInform(msg);
                            }
                            break;
                    }
                } else {
                    block();
                }
            }
        });
    }

    /**
     * Calcula el horario de descarga disponible y responde AGREE con el DOP actualizado.
     * No registra el slot en el schedule — eso ocurre solo al recibir INFORM.
     */
    private void handleRequest(ACLMessage request) {
        try {
            DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) request.getContentObject();

            long horaLlegada = dop.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion();
            long duracionDescarga = dop.getHoraFinDeDescargaPropuestoPorCamion() - horaLlegada;

            // Hora libre contando las descargas firmes Y las reservas provisorias
            // de otros camiones que estan negociando ahora; luego se reserva.
            long ahoraReal = System.currentTimeMillis();
            long inicioDescarga = encontrarProximoSlotDisponible(horaLlegada, duracionDescarga,
                    dop.getIdCamion(), ahoraReal);
            reservas.reservar(dop.getIdCamion(), inicioDescarga, inicioDescarga + duracionDescarga, ahoraReal);

            dop.setTiempoEsperaParaDescargar(inicioDescarga - horaLlegada);
            dop.setHoraLlegadaAlugarDeDescargaPropuestoPorCamion(inicioDescarga);
            dop.setHoraFinDeDescargaPropuestoPorCamion(inicioDescarga + duracionDescarga);
            dop.setContent("descarga aprobada en " + getLocalName()
                + " inicio=" + Utilidades.longToHHMMSS(inicioDescarga));

            ACLMessage reply = request.createReply();
            reply.setPerformative(ACLMessage.AGREE);
            reply.setContentObject(dop);
            send(reply);

            CsvLogger.logMessage(
                getLocalName(), "AGREE descarga (pendiente INFORM)",
                "sin sender", request.getSender().getLocalName(),
                "AGREE", request.getConversationId(),
                "inicio descarga propuesto=" + Utilidades.longToHHMMSS(inicioDescarga) + " para " + dop.getIdCamion()
            );

        } catch (UnreadableException | IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * El TruckAgent confirmo al ShovelAgent: registrar ahora el slot definitivo en el schedule.
     */
    private void handleInform(ACLMessage inform) {
        try {
            DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) inform.getContentObject();

            schedule.agregarSlot(
                getLocalName(),
                dop.getIdCamion(),
                dop.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion(),
                dop.getHoraFinDeDescargaPropuestoPorCamion(),
                ubicacion,
                Slot.enum_operacion.DESCARGA,
                dop.getCantidadEstimadaDeMaterialATransportar()
            );
            // La descarga ya es firme: se sueltan las reservas provisorias del
            // camion (las de las otras palas que estaba evaluando ya no sirven).
            reservas.soltar(dop.getIdCamion());

            CsvLogger.logMessage(
                getLocalName(), "INFORM recibido - slot registrado",
                "sin sender", inform.getSender().getLocalName(),
                "INFORM", inform.getConversationId(),
                "descarga registrada para " + dop.getIdCamion()
                    + " inicio=" + Utilidades.longToHHMMSS(dop.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion())
            );

        } catch (UnreadableException e) {
            e.printStackTrace();
        }
    }

    /**
     * Busca el primer instante >= horaLlegada donde caben menos de maximumDischarge
     * descargas simultáneas durante el intervalo [inicio, inicio+duracion).
     */
    private long encontrarProximoSlotDisponible(long horaLlegada, long duracion,
                                                String camion, long ahoraReal) {
        java.util.List<long[]> ocupados = reservas.ocupadasPorOtros(camion, ahoraReal);
        for (Slot slot : schedule.getSlots()) {
            if (slot.getOperacion() == Slot.enum_operacion.DESCARGA) {
                ocupados.add(new long[]{slot.getHoraInicio(), slot.getHoraFin()});
            }
        }
        return ReservasDescarga.proximoInicio(ocupados, horaLlegada, duracion, maximumDischarge);
    }

    /** Version original (sin reservas provisorias), conservada para comparar o revertir. */
    private long encontrarProximoSlotDisponible(long horaLlegada, long duracion) {
        long inicio = horaLlegada;

        while (contarDescargasSolapadas(inicio, inicio + duracion) >= maximumDischarge) {
            long proximoFin = Long.MAX_VALUE;
            for (Slot slot : schedule.getSlots()) {
                if (slot.getOperacion() == Slot.enum_operacion.DESCARGA
                        && slot.getHoraInicio() < inicio + duracion
                        && slot.getHoraFin() > inicio
                        && slot.getHoraFin() < proximoFin) {
                    proximoFin = slot.getHoraFin();
                }
            }
            if (proximoFin == Long.MAX_VALUE) break;
            inicio = proximoFin;
        }

        return inicio;
    }

    private int contarDescargasSolapadas(long horaInicio, long horaFin) {
        int count = 0;
        for (Slot slot : schedule.getSlots()) {
            if (slot.getOperacion() == Slot.enum_operacion.DESCARGA
                    && slot.getHoraInicio() < horaFin
                    && slot.getHoraFin() > horaInicio) {
                count++;
            }
        }
        return count;
    }

    /**
     * Recibe un right-shift de ReschedulingAgent y actualiza en este agente
     * el/los slot(s) de DESCARGA que corresponden al camión retrasado.
     *
     * ¿Por qué es importante?
     * encontrarProximoSlotDisponible usa schedule.getSlots() para detectar
     * solapamientos. Si el slot de TruckX sigue en T=200 cuando en realidad
     * llegará en T=250, otro camión podría reservar T=200-250 y crear un
     * conflicto real de capacidad.
     * Al shiftear el slot de TruckX a T=250, liberamos T=200-250 para otros.
     */
    private void handleRescheduleInform(ACLMessage inform) {
        try {
            DocumentoRescheduling docR =
                    (DocumentoRescheduling) inform.getContentObject();
            int n;
            synchronized (schedule) {
                // Horario exacto de cada descarga segun la cascada (ver aplicarA).
                n = docR.aplicarA(schedule.getSlots(), docR.getIdCamion(), Slot.enum_operacion.DESCARGA);
                if (n < 0) {
                    n = 0;
                    for (Slot s : schedule.getSlots()) {
                        // Solo los slots de DESCARGA registrados para este camión
                        // que sean FUTUROS respecto al corte de la simulación.
                        if (docR.getIdCamion().equals(s.getMaquina())
                                && s.getHoraInicio() > docR.getTiempoCorte()) {
                            s.setHoraInicio(s.getHoraInicio() + docR.getDeltaT());
                            s.setHoraFin   (s.getHoraFin()    + docR.getDeltaT());
                            n++;
                        }
                    }
                }
                schedule.getSlots().sort(java.util.Comparator.comparingLong(Slot::getHoraInicio));
            }
            System.out.printf("[%s] RIGHT-SHIFT: descarga de %s desplazada +%.1fmin (%d slots)%n",
                    getLocalName(), docR.getIdCamion(),
                    docR.getDeltaT() / 60_000.0, n);
            CsvLogger.logMessage(
                    getLocalName(), "RESCHEDULE_APLICADO",
                    inform.getSender().getLocalName(), getLocalName(),
                    "INFORM", inform.getConversationId(),
                    "descarga de " + docR.getIdCamion()
                            + " shiftada +" + Utilidades.longToHHMMSS(docR.getDeltaT())
                            + " (" + n + " slots)");
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
    }

    /**
     * Partial Repair: borra los slots de DESCARGA asignados al camion identificado
     * en el documento cuyo horaInicio > corte. El hueco queda vacante para que
     * otro camion lo tome (la negociacion la inicia el ShovelAgent emparejado).
     */
    private void handleLiberarInform(ACLMessage inform) {
        try {
            DocumentoLiberacion docL =
                    (DocumentoLiberacion) inform.getContentObject();
            int n = 0;
            synchronized (schedule) {
                java.util.Iterator<Slot> it = schedule.getSlots().iterator();
                while (it.hasNext()) {
                    Slot s = it.next();
                    if (docL.getIdCamion().equals(s.getMaquina())
                            && s.getHoraInicio() > docL.getCorte()) {
                        it.remove();
                        n++;
                    }
                }
            }
            // Reservas provisorias de una negociacion anterior a la liberacion: fuera.
            reservas.soltar(docL.getIdCamion());
            System.out.printf("[%s] PARTIAL REPAIR: liberados %d slots de descarga de %s (corte=%s)%n",
                    getLocalName(), n, docL.getIdCamion(),
                    Utilidades.longToHHMMSS(docL.getCorte()));
            CsvLogger.logMessage(
                    getLocalName(), "LIBERAR_APLICADO",
                    inform.getSender().getLocalName(), getLocalName(),
                    "INFORM", inform.getConversationId(),
                    "borrados " + n + " slots de descarga de " + docL.getIdCamion()
                            + " corte=" + Utilidades.longToHHMMSS(docL.getCorte()));
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
    }


    @Override
    protected void takeDown() {
        System.out.println(getLocalName() + " finalizando.");
    }
}
