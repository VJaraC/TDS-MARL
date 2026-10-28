import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.UnreadableException;

/**
 * Ejemplo de ShovelAgent (Manager) que:
 * 1) Tiene una lista de momentos (hora, minuto) en los que estará disponible.
 * 2) Inicia una negociación (CNP) manual por cada momento (una a la vez).
 * 3) Envía CFP a TruckAgents, recibe PROPOSE/REFUSE en un CyclicBehaviour.
 * 4) Usa un WakerBehaviour para controlar un deadline (4000 ms) y,
 *    si expira, decide y cierra la negociación con la info disponible.
 * 5) No usa ContractNetInitiator.
 */
public class ShovelAgent extends Agent {

    private Schedule schedule;
    private boolean isNegotiating = false;
    private boolean negociacionFinalizadaSinGanadores=false;
	private long ultima_HoraInicioCargaOfertadaPorPalaNegociada;
    private Map<String, NegotiationData> negotiations;  // Clave: conversationId -> NegotiationData    
    private String ubicacionInicial;
    private double capacidad;
    private String destinoMateriales;
    private long duracionPalada ;
	private boolean recibiDisconfirm=false;
	private Date deadline;
	private ArrayList<AID> noEnviarCFP;
	protected boolean esperandoConfirmacion=false;
	private boolean recibiRefuseDeUnRequest=false;

	// ── Re-oferta de huecos liberados (Partial Repair / Complete Regeneration) ──
	// Ventana [reofertaInicio, reofertaFin] de capacidad liberada por un LIBERAR.
	// -1 = sin hueco pendiente. Sin esto la pala ofertaba SOLO despues de su
	// ultimo slot (append-only): un hueco liberado en el medio de su agenda era
	// invisible y el camion liberado quedaba huerfano. Reglas:
	//   - solo se re-ofertan huecos DENTRO del turno (inicio < 6h);
	//   - pala saturada = sin hueco = sin re-oferta (se maneja solo);
	//   - los reintentos "sin ganadores" avanzan de a 60s ACOTADOS por
	//     reofertaFin, para no invadir los slots que siguen al hueco.
	private long reofertaInicio = -1;
	private long reofertaFin = -1;
	private boolean avisoTurnoImpreso = false;

	// ── Re-oferta TOTAL tras una reparacion (reunion con el profesor, 2-oct-2026) ──
	// Cuando hay una reparacion (parcial o completa) la pala recorre TODO su
	// tiempo libre desde el reloj actual hasta el fin del turno, igual que en la
	// negociacion inicial: si gana un camion sigue desde el fin de esa carga, si
	// nadie acepta avanza 60 s. Salta las tareas que ya tiene (en la reparacion
	// parcial, lo que se mantiene no se vuelve a ofrecer). Antes solo
	// re-ofertaba los huecos que se le liberaban, y una pala que habia quedado
	// vacia no volvia a ofrecer nada aunque hubiera camiones libres.
	private boolean barridoActivo = false;
	private long cursorBarrido = -1;
	private int generacionBarrido = -1;

	/**
	 * Contadores OBSERVACIONALES (no alteran la negociacion en nada): permiten
	 * saber desde fuera cuando la negociacion inicial termino de construir el
	 * plan del turno.
	 *
	 * Hacen falta porque el plan NO se arma de una sola vez: cada pala negocia
	 * un ciclo cada 2 segundos REALES y sigue hasta llenar el turno, asi que
	 * construir el plan completo toma cerca de dos minutos de reloj. Arrancar
	 * la simulacion antes de eso hace que el cursor le gane la carrera a la
	 * negociacion y los camiones queden sin plan futuro.
	 */
	private static final java.util.concurrent.atomic.AtomicInteger PALAS_REGISTRADAS =
			new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger PALAS_CON_TURNO_LLENO =
			new java.util.concurrent.atomic.AtomicInteger();

	/** true cuando todas las palas llenaron su turno al menos una vez. */
	public static boolean negociacionInicialCompleta() {
		int registradas = PALAS_REGISTRADAS.get();
		return registradas > 0 && PALAS_CON_TURNO_LLENO.get() >= registradas;
	}

	public static int getPalasRegistradas()     { return PALAS_REGISTRADAS.get(); }
	public static int getPalasConTurnoLleno()   { return PALAS_CON_TURNO_LLENO.get(); }

    @Override
    protected void setup() {
    	
        schedule = new Schedule();
        RegistroAgendasAgentes.registrar(getLocalName(), RegistroAgendasAgentes.PALA, schedule); // solo observacion
        negotiations = new HashMap<>(); //aqui se almacena la info de las negociaciones
        Object[] args = getArguments();
        noEnviarCFP = new ArrayList<AID>();
        if (args != null && args.length > 0) {
        	ubicacionInicial=(String) args[0]; 
        	capacidad=(double) args[1]; 
        	destinoMateriales=(String) args[2];
        	duracionPalada=(long) args[3]; 
        } 
        // Plan inicial unico (14-oct-2026): con USAR_PLAN_INICIAL_FIJO la agenda
        // viene de un archivo y la pala no negocia el plan inicial: queda con el
        // turno lleno, igual que al terminar la negociacion. Las liberaciones y
        // re-ofertas de la reprogramacion las atiende como siempre.
        if (PlanInicialFijo.usar()) {
            PlanInicialFijo.cargarEn(getLocalName(), RegistroAgendasAgentes.PALA, schedule);
            ultima_HoraInicioCargaOfertadaPorPalaNegociada = ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO + 1;
        }
        PALAS_REGISTRADAS.incrementAndGet();
        System.out.println(getLocalName() + " listo.");
        addBehaviour(new TickerBehaviour(this, 2000) {         // 3. TickerBehaviour: cada 2s revisa si puede iniciar una nueva negociación
            @Override
            protected void onTick() {
                if (!isNegotiating && HayTareaPorAsignar()) {
                    startNegotiation();
                }
            }
        });
        

        // 4. CyclicBehaviour para procesar mensajes (CFP, PROPOSE, REFUSE, INFORM, etc.).
        //    En este caso, como Shovel es Manager, esperamos PROPOSE/REFUSE de los trucks,
        //    y luego INFORM cuando aceptamos la propuesta.
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = myReceive();
                if (msg != null) {
                	if (msg.getReplyByDate()!=null && System.currentTimeMillis() > msg.getReplyByDate().getTime())  { 
                		System.out.println("mensaje llego despues del repplybydate");
                	} else {
                		String convId = msg.getConversationId();

                		// ── RIGHT-SHIFT RESCHEDULING ─────────────────────────────────────
                		// Los mensajes de ReschedulingAgent llevan convId = "RESCHEDULE-…".
                		// Se procesan ANTES del chequeo de negociación activa porque no
                		// pertenecen a ningún Contract-Net en curso.
                		if (convId != null && convId.startsWith("RESCHEDULE-")) {
                			try {
                				Object contenido = msg.getContentObject();
                				if (contenido instanceof DocumentoRescheduling) {
                					aplicarRightShiftEnSchedule((DocumentoRescheduling) contenido);
                				}
                			} catch (jade.lang.acl.UnreadableException e) {
                				e.printStackTrace();
                			}
                			return;  // no continuar con el flujo de negociación
                		}

                		// ── PARTIAL REPAIR (LIBERAR-...) ────────────────────────────
                		// El truck identificado en el documento "soltó" sus slots
                		// futuros. Borramos los slots de carga que teniamos asignados
                		// a ese truck con horaInicio > corte → la pala queda con
                		// huecos vacantes y su TickerBehaviour inicia nuevos CFP.
                		if (convId != null && convId.startsWith("LIBERAR-")) {
                			try {
                				Object contenido = msg.getContentObject();
                				if (contenido instanceof DocumentoLiberacion) {
                					aplicarLiberacionEnSchedule((DocumentoLiberacion) contenido);
                				}
                			} catch (jade.lang.acl.UnreadableException e) {
                				e.printStackTrace();
                			}
                			return;
                		}

                		// ── RE-OFERTA TOTAL (REOFERTA-...) ───────────────────────────
                		// Aviso de reparacion: recorrer todo el tiempo libre hasta el
                		// fin del turno. Llega DESPUES de los LIBERAR del mismo
                		// emisor, asi que los huecos ya estan borrados.
                		if (convId != null && convId.startsWith("REOFERTA-")) {
                			try {
                				Object contenido = msg.getContentObject();
                				if (contenido instanceof DocumentoReoferta) {
                					iniciarBarrido((DocumentoReoferta) contenido);
                				}
                			} catch (jade.lang.acl.UnreadableException e) {
                				e.printStackTrace();
                			}
                			return;
                		}
                		// ────────────────────────────────────────────────────────────────

                		NegotiationData data = negotiations.get(convId);
                		if (data == null) {
                			// Mensaje que no pertenece a ninguna negociación actual
	                        return;
	                    }

	                    switch (msg.getPerformative()) {
	                        case ACLMessage.PROPOSE:
	                            handlePropose(msg, data);
	                            break;
	                        case ACLMessage.REFUSE:
	                            handleRefuse(msg, data);
	                            break;
	                        case ACLMessage.CONFIRM:
	                            handleConfirm(msg, data);
	                            break;
	                        case ACLMessage.DISCONFIRM:
	                            handleDisconfirm(msg, data);
	                            break;
	                        default:
	                            System.out.println(msg.toString());
	                            break;
	                    }

	                    // Si ya se recibieron todas las respuestas y no hay decisión tomada, 
	                    // forzamos la decisión (aunque el deadline no haya llegado todavía).
	                    
	                    if (!data.isDecisionMade 
	                        && (data.numResponses == data.numReceivers) && !esperandoConfirmacion) {
	                    	CsvLogger.logMessage(
	                    			getLocalName(),
	                                "llegaron todas las respuestas. numero de trucks "+data.numReceivers+". numero de propuestas "+data.listaPropuestas.size(),
	                                "sin sender", 
	                                "sin receptores",
	                                "sin performative",
	                                "sin conversationID",
	                                "sin contenido"
	                        );
	                    	if (!data.listaPropuestas.isEmpty()) {
	                    		decideAndSendAcceptRejectMulticriterio(data);
	                    		
	                    	} else {
	                    		data.isDecisionMade=true;
	                    		isNegotiating=false;
	                    		noEnviarCFP.clear();
	                    		esperandoConfirmacion=false;
	                    		negociacionFinalizadaSinGanadores=true;
	                    		data.finNegociacion=System.currentTimeMillis();
	                    		data.motivoFinNegociacion="No hubieron propuestas";
	                    		CsvLogger.logMessage(
	                            		getLocalName(),
	                                    " - Negociacion " + convId + " finalizada. No hubieron propuestas.",
	                                    "sin sender",
	                                    "sin receptores",
	                                    "sin performative",
	                                    convId,
	                                    "sin contenido"
	                            );
	                    		CsvNegotiationsLogger.logNegotiation(
	                            		getLocalName(), data                                
	                            );
	                    		
	                    	}
	                        
	                    }

	                    // Si la negociación está decidida, revisamos si finalizó
	                    
	                    if (data.isFinished()) {
	                        negotiations.remove(convId);
	                        isNegotiating = false;
	                        esperandoConfirmacion=false;
	                        data.isDecisionMade=true;
	                		//noEnviarCFP.clear();

	                        CsvLogger.logMessage(
	                        		getLocalName(),
	                                " - Negociacion " + convId + " finalizada.",
	                                "sin sender",
	                                "sin receptores",
	                                "sin performative",
	                                convId,
	                                "sin contenido"
	                        );
	                       
	                    }
                	
		            }
                    
                } else {
                    block();
                }
            }

			
        });
    }
    
    /**
     * Método "wrapper" que invoca a super.receive() y, si obtiene un mensaje,
     * lo loggea como "RECEIVE" antes de retornarlo.
     *
     * Úsalo en lugar de 'receive()' en tus comportamientos.
     */
    public ACLMessage myReceive() {
        ACLMessage msg = super.receive();
        if (msg != null) {
            // Extraemos datos
            String sender = (msg.getSender() != null) ? msg.getSender().getLocalName() : "unknown";
            String receivers = getReceiverString(msg);
            String performative = ACLMessage.getPerformative(msg.getPerformative());
            String conversationId = (msg.getConversationId() != null) ? msg.getConversationId() : "";
            Date replyByDate = msg.getReplyByDate();
            Date currentTime = new Date();
            String content="";

          
                 
          
            DocumentoOfertaPropuesta dop=null;;
           
			try {
				// Protección: el contenido puede NO ser un DOP
				// (ej: DocumentoRescheduling enviado por ReschedulingAgent).
				// Usamos instanceof para evitar ClassCastException.
				Object contenido = msg.getContentObject();
				if (contenido instanceof DocumentoOfertaPropuesta) {
					dop = (DocumentoOfertaPropuesta) contenido;
				}
				if (dop!=null && dop.getContent()!=null) {
	            	content = dop.getContent();	
	            } else if (dop != null) {
	            	content = "dop sin contenido";
	            } else {
	            	content = "mensaje no-DOP (ej: rescheduling)";
	            }
			} catch (UnreadableException e) {
				 System.out.println("msg received "+sender+" / "+performative);
				e.printStackTrace();
			}
            
            

            switch (msg.getPerformative()) {
            case ACLMessage.PROPOSE:
            	if (currentTime.after(deadline)) {
                    System.out.println(this.getLocalName()+": Mensaje recibido después del límite de tiempo (replyBy: " + deadline + ")");
                }
            	content = "hora de llegada estimada propuesta por camion "
            				+Utilidades.longToHHMMSS(dop.getHoraLlegadaApalaPropuestoPorCamion())
            				+" hora inicio viaje vacio "+Utilidades.longToHHMMSS(dop.getHoraInicioViajeVacioPropuestoPorCamion())
            				+" hora fin descarga "+Utilidades.longToHHMMSS(dop.getHoraFinDeDescargaPropuestoPorCamion())		
            				;
                break;
            case ACLMessage.REFUSE:
                
                break;
            case ACLMessage.DISCONFIRM:
                break;
            default:
                // Podríamos manejar más casos (FAILURE, etc.)
                break;
        }
            
            // Loggear como "RECEIVE"
            CsvLogger.logMessage(
            		this.getLocalName(),
                    "RECEIVE",
                    sender,
                    "sin receivers",
                    performative,
                    conversationId,
                  content
            );
        }
        return msg;
    }
  
    public void mySend(ACLMessage msg) {
        // 1. Identificar datos del mensaje
        String sender = getLocalName();  // el localName de este agente
        String receivers = getReceiverString(msg);
        String performative = ACLMessage.getPerformative(msg.getPerformative());
        String conversationId = (msg.getConversationId() != null) ? msg.getConversationId() : "";
        String content="no se abrio el dop";
        DocumentoOfertaPropuesta dop = negotiations.get(conversationId).getDop();
		content = "hora de inicio ofertada por pala "
				+Utilidades.longToHHMMSS(dop.getHoraInicioCargaOfertadaPorPala()); // "hay que abrir DOP para ver info";//(msg.getContent() != null) ? msg.getContent() : "";
        // 2. Loggear en CSV como "SEND"
        CsvLogger.logMessage(
        		this.getLocalName(),
                "SEND",
                sender,
                receivers,
                performative,
                conversationId,
                content
        );

        // 3. Llamar al método original para enviar de verdad
        super.send(msg);
    }

    

    /**
     * Convierte la lista de receptores de un ACLMessage a un string "AgenteA|AgenteB|..."
     */
    private String getReceiverString(ACLMessage msg) {
        String receiverList="";
        jade.util.leap.Iterator it = msg.getAllReceiver();
        while (it.hasNext()) {
            AID r = (AID) it.next();
            receiverList=receiverList+" "+r.getLocalName();
        }
        return receiverList;
    }


    /**
     * Inicia la negociación enviando un CFP a los Trucks.
     * Crea también un WakerBehaviour para controlar el deadline.
     */
    private void startNegotiation() {
    	deadline = new Date(System.currentTimeMillis() + ParametrosConfiguracion.DEADLINE_MS);
        isNegotiating = true;
        String conversationId = this.getLocalName()+" CNP-" + System.currentTimeMillis();
        NegotiationData data = new NegotiationData(conversationId, ParametrosConfiguracion.NUMERODETRUCKS);  
        negotiations.put(conversationId, data);
        CsvLogger.logMessage(
        		getLocalName(),
                " Iniciando negociacion",
                "sin sender",
                "sin receptores",
                "sin performative",
                conversationId,
                " deadline: " + deadline+" ("+ParametrosConfiguracion.DEADLINE_MS + " ms)"
        );
 
        // Enviamos CFP a los trucks
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setConversationId(conversationId);
        cfp.setReplyByDate(deadline);

        //cfp.setContent("AvailableTime=" + hour + ":" + String.format("%02d", minute));
        DocumentoOfertaPropuesta dop = generaContenidoCFP();
        System.out.println(getLocalName()+" hora de inicio ofertada por pala "
				+Utilidades.longToHHMMSS(dop.getHoraInicioCargaOfertadaPorPala())); 
        data.setDop(dop);
 		try {
			cfp.setContentObject(dop);
		} catch (IOException e) {
			e.printStackTrace();
		}

        
        for (int i = 0; i < ParametrosConfiguracion.NUMERODETRUCKS; i++) {
        	String truckName = "Truck"+i;
        	
        	cfp.addReceiver(new AID(truckName, AID.ISLOCALNAME));
        }
        data.inicioDeNegociacion = System.currentTimeMillis();
        
        //remuevo a aquellos que disconfirmaron esta negociacion
        for (int i=0;i<noEnviarCFP.size();i++) {
        	cfp.removeReceiver(noEnviarCFP.get(i));
        	CsvLogger.logMessage(this.getLocalName(), "remover agente del CFP", "no sender", "no receiver", "no performative", conversationId, "removi a "+noEnviarCFP.get(i).getLocalName()+" por que antes envio disconfirm");
        }
       
        	
        mySend(cfp);

        // *** WakerBehaviour *** para manejar el deadline de 4000 ms
        addBehaviour(new WakerBehaviour(this, ParametrosConfiguracion.DEADLINE_MS) {
            @Override
            protected void onWake() {
                // Se invoca automáticamente tras 4000 ms
                NegotiationData nd = negotiations.get(conversationId);
                if (nd != null && !nd.isDecisionMade) {
                    // Si la negociación sigue sin decidir, la cerramos con la info disponible
                    /*
                	System.out.println(getLocalName() + " - Deadline alcanzado para "
                                       + conversationId 
                                       + ". Decidiendo con "
                                       + nd.numResponses + " respuestas recibidas.");
                                       */
                    CsvLogger.logMessage(
                    		getLocalName(),
                            "cumplimiento deadline",
                            "sin sender",
                            "sin receptores",
                            "sin performative",
                            conversationId,
                            "sin contenido"
                    );
                	if (!data.listaPropuestas.isEmpty()) {
                		decideAndSendAcceptRejectMulticriterio(nd);
                	} else {
                		data.isDecisionMade=true;
                		isNegotiating=false;
                		esperandoConfirmacion=false;
                		noEnviarCFP.clear();
                		negociacionFinalizadaSinGanadores=true;
                		data.finNegociacion=System.currentTimeMillis();
                		data.motivoFinNegociacion="deadline cumplido. No hubieron propuestas";
                		CsvLogger.logMessage(
                        		getLocalName(),
                                " - Negociacion " + data.conversationId + " finalizada. deadline cumplido No hubieron propuestas.",
                                "sin sender",
                                "sin receptores",
                                "sin performative",
                                data.conversationId,
                                "sin contenido"
                        );
                		CsvNegotiationsLogger.logNegotiation(
                        		getLocalName(), data                                
                        );
                		
                	}
                    // Revisamos si eso la deja finalizada
                    if (nd.isFinished()) {
                        negotiations.remove(conversationId);
                        isNegotiating = false;   
                        esperandoConfirmacion=false;
                		noEnviarCFP.clear();
                        System.out.println(getLocalName()
                            + " - Negociación " + conversationId + " finalizada (deadline).");
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------
    // Métodos de manejo de mensajes
    // ------------------------------------------------------------------------------------
    private void handlePropose(ACLMessage propose, NegotiationData data) {
    	if (data.conversationId==propose.getConversationId()) {
    		data.numResponses++;
            data.numProposes++;
           	
    	}
        // int arrival = parseArrivalTime(propose.getContent());
       // data.proposals.put(propose.getSender(), arrival);
        DocumentoOfertaPropuesta dop;
		try {
			dop = (DocumentoOfertaPropuesta) propose.getContentObject();
			if (dop.getHoraInicioCargaPropuestoPorCamion()>=dop.getHoraInicioCargaOfertadaPorPala()){			
		        data.listaPropuestas.add(propose);
		        //no agrego propuestas antes de la hora ofertada por pala. Quizas sirvan pero de
				}
		} catch (UnreadableException e) {
			e.printStackTrace();
		}
		
        /*
        System.out.println(getLocalName() + " - Recibido PROPOSE de "
                           + propose.getSender().getLocalName()
                           + ": " + propose.getContent() 
                           + ", convID=" + propose.getConversationId());
         */
    }

    private void handleRefuse(ACLMessage refuse, NegotiationData data) {
    	if (data.conversationId==refuse.getConversationId()) {
    		data.numResponses++;
            data.numRefuses++;
    	}
    	// CAMBIO 2026-10-07 (pendiente de confirmar con el profesor): solo el
    	// REFUSE del GANADOR (el camion al que se le envio el REQUEST) cierra la
    	// negociacion. Antes cualquier REFUSE que llegara mientras se esperaba la
    	// confirmacion se tomaba como del ganador: una respuesta atrasada de otro
    	// camion a la oferta (llegada despues del deadline) cerraba la negociacion
    	// como fallida, la pala volvia a ofrecer la misma hora, y luego llegaba
    	// igual el CONFIRM del ganador. En grande_V7_COMPLETE eso dejo 6 palas con
    	// dos camiones a la misma hora y 3 cargas sin reserva (PA04 1:23:35:
    	// Truck87 respondio tarde, Truck12 confirmo y despues tambien Truck84).
    	boolean delGanador = refuse.getSender() != null && data.ganador != null
    			&& data.ganador.equals(refuse.getSender().getLocalName());
    	if (esperandoConfirmacion && delGanador) {
    		this.isNegotiating=false;
    		esperandoConfirmacion=false;
    		//negociacionFinalizadaSinGanadores=true; //esto no esta bien.
    		recibiRefuseDeUnRequest=true;
    		noEnviarCFP.clear();
    		data.isDecisionMade = true;
    		data.finNegociacion=System.currentTimeMillis();
    		data.motivoFinNegociacion="recibi refuse despues de request";
    		CsvNegotiationsLogger.logNegotiation(
            		getLocalName(), data                                
            );
    	}
    	
    
    }

    private void handleConfirm(ACLMessage confirm, NegotiationData data) {
        data.numInforms++;
        DocumentoOfertaPropuesta dop;
		try {
			dop = (DocumentoOfertaPropuesta) confirm.getContentObject();
	        AgregaGanador(dop);
			this.isNegotiating=false;
    		negociacionFinalizadaSinGanadores=false;
    		noEnviarCFP.clear();
    		data.isDecisionMade = true;
    		data.finNegociacion=System.currentTimeMillis();
    		data.motivoFinNegociacion="recibi inform. ganador acepto";
    		CsvNegotiationsLogger.logNegotiation(
            		getLocalName(), data                                
            );
		} catch (UnreadableException e) {
			e.printStackTrace();
		}        
   }
    
    private void handleDisconfirm(ACLMessage msg, ShovelAgent.NegotiationData data) {
		this.isNegotiating=false;
		esperandoConfirmacion=false;
		data.isDecisionMade=true;
		data.motivoFinNegociacion="recibi Disconfirm";
		noEnviarCFP.add(msg.getSender());
		data.finNegociacion=System.currentTimeMillis();
		data.numCancels++;
		recibiDisconfirm=true;
		CsvNegotiationsLogger.logNegotiation(
        		getLocalName(), data                                
        );
		CsvLogger.logMessage(
        		getLocalName(),
                " - Negociacion " + "convId" + " finalizada. Recibi Disconfirm. Debo reiniciar negociacion",
                "sin sender",
                "sin receptores",
                "sin performative",
                "convId",
                "sin contenido"
        );
		
	}

    /**
     * Decide y envía ACCEPT al Truck con llegada más temprana,
     * REJECT a los demás.
     */
    /**
     * Selección multicriterio: minimiza un costo ponderado que considera
     * el tiempo de espera de la pala, la capacidad del camión, la duración
     * de los viajes vacío y cargado, y el tiempo de espera para descargar.
     *
     * Costo = W_ESPERA_PALA * esperaPala
     *       + W_VIAJE_VACIO * duracionViajeVacio
     *       + W_VIAJE_CARGADO * duracionViajeCargado
     *       + W_ESPERA_DESCARGA * tiempoEsperaDescarga
     *       - W_CAPACIDAD * capacidadCamion        <- negativo: mayor capacidad reduce el costo
     *
     * Gana la propuesta con menor costo.
     */
    private void decideAndSendAcceptRejectMulticriterio(NegotiationData data) {

        final double W_ESPERA_PALA     = 2.0;     // ms — penaliza tiempo ocioso de la pala
        final double W_VIAJE_VACIO     = 1.0;     // ms
        final double W_VIAJE_CARGADO   = 1.0;     // ms
        final double W_ESPERA_DESCARGA = 1.5;     // ms — penaliza cola en punto de descarga
        final double W_CAPACIDAD       = 1000.0;  // ms/ton — premia mayor capacidad

        double menorCosto = Double.POSITIVE_INFINITY;
        ACLMessage propuestaGanadora = null;

        for (ACLMessage proposal : data.listaPropuestas) {
            try {
                DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) proposal.getContentObject();

                if (dop.getHoraInicioCargaPropuestoPorCamion() < dop.getHoraInicioCargaOfertadaPorPala()) {
                    continue;
                }
                // Solo durante el barrido de re-oferta: la oferta puede caer en un
                // hueco entre tareas, y un camion que llega tarde haria que su
                // carga se superpusiera con la siguiente tarea de la pala. En la
                // negociacion inicial la oferta va siempre despues de la ultima
                // tarea, asi que este filtro no cambia nada ahi.
                if (barridoActivo && seSuperpone(schedule,
                        dop.getHoraInicioCargaPropuestoPorCamion(),
                        dop.getHoraInicioViajeCargadoPropuestoPorCamion())) {
                    continue;
                }

                double esperaPala = Math.max(0,
                    dop.getHoraLlegadaApalaPropuestoPorCamion() - dop.getHoraInicioCargaOfertadaPorPala());

                double duracionViajeVacio =
                    dop.getHoraLlegadaApalaPropuestoPorCamion() - dop.getHoraInicioViajeVacioPropuestoPorCamion();

                // El viaje cargado real excluye el tiempo de espera en el punto de descarga
                double duracionViajeCargado =
                    (dop.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion() - dop.getTiempoEsperaParaDescargar())
                    - dop.getHoraInicioViajeCargadoPropuestoPorCamion();

                double tiempoEsperaDescarga = dop.getTiempoEsperaParaDescargar();

                double capacidad = dop.getCantidadEstimadaDeMaterialATransportar();

                double costo = W_ESPERA_PALA     * esperaPala
                             + W_VIAJE_VACIO     * duracionViajeVacio
                             + W_VIAJE_CARGADO   * duracionViajeCargado
                             + W_ESPERA_DESCARGA * tiempoEsperaDescarga
                             - W_CAPACIDAD       * capacidad;

                CsvLogger.logMessage(getLocalName(),
                    "evaluacion multicriterio: costo=" + costo
                    + " esperaPala=" + Utilidades.longToHHMMSS((long)esperaPala)
                    + " viajeVacio=" + Utilidades.longToHHMMSS((long)duracionViajeVacio)
                    + " viajeCargado=" + Utilidades.longToHHMMSS((long)duracionViajeCargado)
                    + " esperaDescarga=" + Utilidades.longToHHMMSS((long)tiempoEsperaDescarga)
                    + " capacidad=" + capacidad + "t",
                    proposal.getSender().getLocalName(),
                    "sin receptores", "sin performative",
                    data.conversationId, "sin contenido");

                if (costo < menorCosto) {
                    menorCosto = costo;
                    propuestaGanadora = proposal;
                }

            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        }

        if (propuestaGanadora == null) {
            if (barridoActivo) {
                // ninguna propuesta cabe en la agenda: se cierra sin ganador y
                // el barrido avanza 60 s, como cuando nadie propone
                cerrarSinGanadorCompatible(data);
                return;
            }
            propuestaGanadora = data.listaPropuestas.get(0);   // comportamiento original
        }

        data.listaPropuestas.remove(propuestaGanadora);
        data.ganador = propuestaGanadora.getSender().getLocalName();
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.setConversationId(data.conversationId);
        request.addReceiver(propuestaGanadora.getSender());
        try {
            request.setContentObject(propuestaGanadora.getContentObject());
        } catch (IOException | UnreadableException e) {
            e.printStackTrace();
        }
        mySend(request);
        esperandoConfirmacion = true;

        ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
        reject.setConversationId(data.conversationId);
        reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
        for (ACLMessage proposal : data.listaPropuestas) {
            reject.addReceiver(proposal.getSender());
        }
        mySend(reject);
    }

    private void decideAndSendAcceptReject(NegotiationData data) {
       // data.isDecisionMade = true;

       // AID bestTruck = null;
		double menorValor = Double.POSITIVE_INFINITY;
		ACLMessage propuestaGanadora = data.listaPropuestas.get(0);

        for (ACLMessage proposal : data.listaPropuestas) {
			try {
				DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) proposal.getContentObject();
				double valorPropuesta = dop.getHoraLlegadaApalaPropuestoPorCamion();
				if (dop.getHoraInicioCargaPropuestoPorCamion()>=dop.getHoraInicioCargaOfertadaPorPala()){
					if (valorPropuesta < menorValor) {
						menorValor = valorPropuesta;
						propuestaGanadora = proposal;
					}
				} //no considera propuestas con horas inicio de carga antes de lo ofertado.             

			} catch (UnreadableException e) {
				e.printStackTrace();
			}
		}
        
     // Mandamos ACCEPT a bestTruck,
		data.listaPropuestas.remove(propuestaGanadora);
		data.ganador=propuestaGanadora.getSender().getLocalName();
		ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.setConversationId(data.conversationId);
        request.addReceiver(propuestaGanadora.getSender());
        try {
        	request.setContentObject(propuestaGanadora.getContentObject());
		} catch (IOException e) {
			e.printStackTrace();
		} catch (UnreadableException e) {
			e.printStackTrace();
		}
        mySend(request);
        esperandoConfirmacion=true;


       //  REJECT a los demás //porque si utilizo el reply anterior, le enviaba mensaje al best proposal
        ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
        reject.setConversationId(data.conversationId);
        reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
        for (ACLMessage proposal : data.listaPropuestas) {
        	reject.addReceiver(proposal.getSender());
        }
        mySend(reject);
        
    }

    

    /**
     * Aplica el right-shift en el schedule INTERNO del ShovelAgent para el
     * camión indicado en el DocumentoRescheduling.
     *
     * ¿Por qué es necesario?
     * ShovelAgent.AgregaGanador() registra en su schedule el slot de CARGA
     * de cada camión asignado (con maquina = idCamion). Si no actualizamos
     * esos tiempos, el schedule del shovel mostrará el slot de TruckX en
     * T=100-120 cuando el camión llegará en T=150. Esto distorsiona la
     * disponibilidad de la pala para futuras negociaciones: el shovel
     * seguirá ofreciendo el slot "vacío" en T=120 cuando en realidad
     * está ocupado hasta T=170 (slot shiftado = T=150-170).
     */

    private void aplicarRightShiftEnSchedule(DocumentoRescheduling docR) {
        int n;
        synchronized (schedule) {
            // Horario exacto de cada carga segun la cascada (ver aplicarA).
            n = docR.aplicarA(schedule.getSlots(), docR.getIdCamion(), Slot.enum_operacion.CARGA);
            if (n < 0) {
                n = 0;
                for (Slot s : schedule.getSlots()) {
                    // Buscamos slots de CARGA donde maquina = camión retrasado
                    // y que sean futuros (no se toca lo ya ejecutado).
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
        System.out.printf("[%s] RIGHT-SHIFT: carga de %s desplazada +%.1fmin (%d slots)%n",
                getLocalName(), docR.getIdCamion(),
                docR.getDeltaT() / 60_000.0, n);
        CsvLogger.logMessage(
                getLocalName(), "RESCHEDULE_APLICADO",
                "ReschedulingAgent", getLocalName(),
                "INFORM", "RESCHEDULE-" + docR.getIdCamion(),
                "carga de " + docR.getIdCamion()
                        + " shiftada +" + Utilidades.longToHHMMSS(docR.getDeltaT())
                        + " (" + n + " slots)");
    }

    /**
     * Partial Repair: el camion identificado fue liberado. Borramos los slots
     * de CARGA que teniamos asignados a ese camion con horaInicio > corte.
     * Los huecos quedan vacantes y el TickerBehaviour de esta pala detectara
     * disponibilidad y disparara nuevos CFP en el proximo tick (cada 2s).
     */
    private void aplicarLiberacionEnSchedule(DocumentoLiberacion docL) {
        int n = 0;
        long huecoIni = Long.MAX_VALUE, huecoFin = -1;
        synchronized (schedule) {
            java.util.Iterator<Slot> it = schedule.getSlots().iterator();
            while (it.hasNext()) {
                Slot s = it.next();
                if (docL.getIdCamion().equals(s.getMaquina())
                        && s.getHoraInicio() > docL.getCorte()) {
                    if (s.getHoraInicio() < huecoIni) huecoIni = s.getHoraInicio();
                    if (s.getHoraFin()    > huecoFin) huecoFin = s.getHoraFin();
                    it.remove();
                    n++;
                }
            }
        }
        // Si este camion estaba en la lista "noEnviarCFP" lo sacamos: queremos
        // poder volver a invitarlo en el proximo Contract-Net.
        if (noEnviarCFP != null) {
            noEnviarCFP.removeIf(aid -> aid.getLocalName().equals(docL.getIdCamion()));
        }

        // RE-OFERTA DEL HUECO: la capacidad liberada vuelve al mercado. Solo si
        // el hueco arranca DENTRO del turno; un hueco pasado el reloj se recorta
        // al presente (no se puede ofertar el pasado). Varias liberaciones se
        // fusionan en una sola ventana [min inicio, max fin].
        if (n > 0) {
            long inicioReoferta = Math.max(huecoIni,
                    VentanaSimulacion.tiempoSimulacionGlobalMs);
            if (inicioReoferta < ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO) {
                reofertaInicio = (reofertaInicio < 0)
                        ? inicioReoferta : Math.min(reofertaInicio, inicioReoferta);
                reofertaFin = Math.max(reofertaFin, huecoFin);
                System.out.printf("[%s] RE-OFERTA de hueco liberado: %s - %s%n",
                        getLocalName(), Utilidades.longToHHMMSS(reofertaInicio),
                        Utilidades.longToHHMMSS(reofertaFin));
            }
        }
        System.out.printf("[%s] PARTIAL REPAIR: liberados %d slots de carga de %s (corte=%s)%n",
                getLocalName(), n, docL.getIdCamion(),
                Utilidades.longToHHMMSS(docL.getCorte()));
        CsvLogger.logMessage(
                getLocalName(), "LIBERAR_APLICADO",
                "ReschedulingAgent", getLocalName(),
                "INFORM", "LIBERAR-" + docL.getIdCamion(),
                "borrados " + n + " slots de " + docL.getIdCamion()
                        + " corte=" + Utilidades.longToHHMMSS(docL.getCorte()));
    }

    // ── Re-oferta total ─────────────────────────────────────────────────────

    private void iniciarBarrido(DocumentoReoferta doc) {
        barridoActivo = true;
        generacionBarrido = doc.getGeneracion();
        cursorBarrido = Math.max(doc.getDesde(), VentanaSimulacion.tiempoSimulacionGlobalMs);
        negociacionFinalizadaSinGanadores = false;
        recibiDisconfirm = false;
        recibiRefuseDeUnRequest = false;
        reofertaInicio = -1;          // el barrido ya cubre los huecos liberados
        reofertaFin = -1;
        if (noEnviarCFP != null) noEnviarCFP.clear();   // cambiaron las condiciones
        System.out.printf("[%s] RE-OFERTA TOTAL: recorre su tiempo libre desde %s (barrido %d)%n",
                getLocalName(), Utilidades.longToHHMMSS(cursorBarrido), generacionBarrido);
    }

    /** Proxima hora a ofrecer en el barrido, sin modificar el estado. */
    private long siguienteOfertaBarrido() {
        long base;
        if (recibiDisconfirm || recibiRefuseDeUnRequest) {
            base = ultima_HoraInicioCargaOfertadaPorPalaNegociada;     // se re-ofrece la misma
        } else if (negociacionFinalizadaSinGanadores) {
            base = ultima_HoraInicioCargaOfertadaPorPalaNegociada + 60000;
        } else {
            base = cursorBarrido;
        }
        base = Math.max(base, VentanaSimulacion.tiempoSimulacionGlobalMs);
        return siguienteInstanteLibre(schedule, base);
    }

    private void terminarBarrido() {
        barridoActivo = false;
        // la regla normal no vuelve a ofrecer: el turno ya se recorrio entero
        ultima_HoraInicioCargaOfertadaPorPalaNegociada =
                Math.max(ultima_HoraInicioCargaOfertadaPorPalaNegociada,
                        ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO + 1);
        negociacionFinalizadaSinGanadores = false;
        reofertaInicio = -1;
        reofertaFin = -1;
        System.out.printf("[%s] RE-OFERTA TOTAL terminada (barrido %d)%n",
                getLocalName(), generacionBarrido);
        EpisodioRescheduling.barridoTerminado(getLocalName(), generacionBarrido);
    }

    /** Primer instante >= t que no cae dentro de ninguna tarea de la agenda. */
    static long siguienteInstanteLibre(Schedule sch, long t) {
        synchronized (sch) {
            java.util.List<Slot> ordenados = new ArrayList<>(sch.getSlots());
            ordenados.sort(java.util.Comparator.comparingLong(Slot::getHoraInicio));
            for (Slot s : ordenados) {
                if (s.getHoraFin() <= t) continue;
                if (s.getHoraInicio() <= t) {
                    t = s.getHoraFin();
                } else {
                    break;
                }
            }
        }
        return t;
    }

    /** true si [ini, fin) se superpone con alguna tarea de la agenda. */
    static boolean seSuperpone(Schedule sch, long ini, long fin) {
        synchronized (sch) {
            for (Slot s : sch.getSlots()) {
                if (s.getHoraInicio() < fin && s.getHoraFin() > ini) return true;
            }
        }
        return false;
    }

    /** Cierra una negociacion del barrido en que ninguna propuesta cabe. */
    private void cerrarSinGanadorCompatible(NegotiationData data) {
        if (!data.listaPropuestas.isEmpty()) {
            ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
            reject.setConversationId(data.conversationId);
            for (ACLMessage p : data.listaPropuestas) reject.addReceiver(p.getSender());
            mySend(reject);
            data.listaPropuestas.clear();
        }
        data.isDecisionMade = true;
        isNegotiating = false;
        esperandoConfirmacion = false;
        negociacionFinalizadaSinGanadores = true;
        data.finNegociacion = System.currentTimeMillis();
        data.motivoFinNegociacion = "Sin propuestas compatibles con la agenda (re-oferta)";
        CsvNegotiationsLogger.logNegotiation(getLocalName(), data);
    }

    @Override
    protected void takeDown() {
        System.out.println(getLocalName() + " finalizando.");
    }
    
    public DocumentoOfertaPropuesta generaContenidoCFP() {
		DocumentoOfertaPropuesta dop = new DocumentoOfertaPropuesta();		
		dop.setIdPala(this.getLocalName());
		dop.setUbicacionPala(this.ubicacionInicial);
		dop.setDestinoMateriales(destinoMateriales);
		dop.setCapacidadPala(capacidad);
		dop.setDuracionPalada(duracionPalada);
		
		if (recibiDisconfirm || recibiRefuseDeUnRequest) {
			dop.setHoraInicioCargaOfertadaPorPala(ultima_HoraInicioCargaOfertadaPorPalaNegociada);//
			recibiDisconfirm=false;
			recibiRefuseDeUnRequest=false;
		} else if (barridoActivo) {
			// Re-oferta total: proximo instante libre de la agenda a partir del
			// cursor (o del ultimo ofrecido + 60 s si nadie acepto).
			long t = siguienteOfertaBarrido();
			negociacionFinalizadaSinGanadores = false;
			cursorBarrido = t;
			dop.setHoraInicioCargaOfertadaPorPala(t);
		} else {
			// Ventana de hueco ya VENCIDA (el reloj la paso entera): descartarla.
			long relojAhoraMs = VentanaSimulacion.tiempoSimulacionGlobalMs;
			if (reofertaFin >= 0 && reofertaFin <= relojAhoraMs) {
				reofertaInicio = -1;
				reofertaFin = -1;
			}
			if (reofertaInicio >= 0) {
				// Hueco liberado pendiente (Partial Repair / Complete Regen):
				// re-ofertarlo ANTES que cualquier otro camino. En particular
				// tiene PRIORIDAD sobre un "sin ganadores" viejo de la cola de
				// la agenda: ese flag ofertaria ultima+60s (final del turno) y
				// cerraria la ventana sin haberla ofertado nunca (bug visto en
				// el primer test de Ronda B: 13/13 huerfanos pese a RE-OFERTA).
				negociacionFinalizadaSinGanadores = false;
				dop.setHoraInicioCargaOfertadaPorPala(reofertaInicio);
				reofertaInicio = -1;
				// Consumo unico: si no hay ganador, el camino "sin ganadores"
				// sigue barriendo la ventana de a 60s acotado por reofertaFin.
			} else if (negociacionFinalizadaSinGanadores) {
				long oferta = ultima_HoraInicioCargaOfertadaPorPalaNegociada+60000;//60 segundos mas  mas
				negociacionFinalizadaSinGanadores=false;
				// Si estabamos recorriendo un hueco liberado y el reintento ya
				// se salio de la ventana, volvemos al comportamiento normal
				// (ofertar despues del ultimo slot) y cerramos la ventana.
				if (reofertaFin >= 0 && oferta > reofertaFin) {
					oferta = schedule.getHoraFinUltimoSlot(this.getLocalName());
					reofertaInicio = -1;
					reofertaFin = -1;
				}
				dop.setHoraInicioCargaOfertadaPorPala(oferta);
			} else {
				dop.setHoraInicioCargaOfertadaPorPala(schedule.getHoraFinUltimoSlot(this.getLocalName()));

			}
		}

		// ── Fix del SIMULADOR (no de los metodos de rescheduling) ───────────
		// Una pala no puede ofertar una hora de inicio en el PASADO del reloj
		// de simulacion: en la mina real diria "estoy libre desde ahora".
		// Sin este clamp, las palas ociosas ofertaban tiempos viejos (ej. 0:50
		// con el reloj en 2:00) y los camiones liberados por Partial Repair
		// los rechazaban por infactibles → quedaban huerfanos. Durante la
		// carga inicial del plan (ventana pausada, reloj=0) el clamp es no-op.
		long relojSim = VentanaSimulacion.tiempoSimulacionGlobalMs;
		if (dop.getHoraInicioCargaOfertadaPorPala() < relojSim) {
			dop.setHoraInicioCargaOfertadaPorPala(relojSim);
		}

        Slot previo = this.schedule.buscarSlotAnterior(dop.getHoraInicioCargaOfertadaPorPala());
        if (previo==null) {
			dop.setTiempoInactivoDesdeActividadAnterior(0);
		} else {
			dop.setTiempoInactivoDesdeActividadAnterior(dop.getHoraInicioCargaOfertadaPorPala()-previo.getHoraFin());
		}
		
		
		
		
		
		
		ultima_HoraInicioCargaOfertadaPorPalaNegociada=dop.getHoraInicioCargaOfertadaPorPala();
		//System.out.println(this.getLocalName()+" ofertando "+dop.getHoraInicioCargaOfertadaPorPala()+" - "+Utilidades.longToHHMMSS(dop.getHoraInicioCargaOfertadaPorPala()));
		
		return dop;
		
		
	}
    
    public boolean HayTareaPorAsignar() {
    	// Barrido de re-oferta total en curso: hay tarea mientras el proximo
    	// instante libre caiga dentro del turno. Al pasar el fin del turno el
    	// barrido termina y se avisa al episodio de renegociacion.
    	if (barridoActivo) {
    		if (siguienteOfertaBarrido() <= ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO) {
    			return true;
    		}
    		terminarBarrido();
    		return false;
    	}
    	// Hay tarea si la proxima oferta cae dentro del turno O si hay un hueco
    	// liberado pendiente de re-ofertar (Partial Repair / Complete Regen).
    	if (reofertaInicio >= 0
    			|| this.ultima_HoraInicioCargaOfertadaPorPalaNegociada<=ParametrosConfiguracion.SEGUNDO_FINAL_HORARIO) {
    		return true;
    	}
    	if (!avisoTurnoImpreso) {
    		System.out.println(this.getLocalName()+" mi horario supera el turno");
    		avisoTurnoImpreso = true;
    		PALAS_CON_TURNO_LLENO.incrementAndGet();
    	}
    	// OJO: ya NO se hace doSuspend(). Un agente suspendido no procesa
    	// mensajes, asi que las palas "cerradas" ignoraban los LIBERAR- de
    	// Partial Repair / Complete Regeneration y los huecos liberados eran
    	// invisibles (causa directa de camiones huerfanos). La pala queda viva
    	// pero sin iniciar CFPs mientras no tenga nada que ofertar en el turno.
    	return false;
	}
    
    public void AgregaGanador(DocumentoOfertaPropuesta dop) {
		schedule.agregarSlot(this.getLocalName(), dop.getIdCamion(), dop.getHoraInicioCargaPropuestoPorCamion(), dop.getHoraInicioViajeCargadoPropuestoPorCamion(), ubicacionInicial, Slot.enum_operacion.CARGA, dop.getCantidadEstimadaDeMaterialATransportar());
		if (barridoActivo) {
			// El barrido sigue 60 s despues de la hora ofrecida; siguienteInstanteLibre
			// salta la carga recien agregada. Si el ganador carga a la hora
			// ofrecida, da lo mismo que antes (el fin de su carga). Si llega mas
			// tarde, antes se saltaba todo el tiempo libre entre la oferta y su
			// carga sin ofrecerlo a nadie. Cambio del 2026-10-07.
			cursorBarrido = Math.max(cursorBarrido, dop.getHoraInicioCargaOfertadaPorPala() + 60_000L);
		}
		//schedule.imprimirSchedule(this.getLocalName());
		//CsvSchedules.logSchedule(destinoMateriales, destinoMateriales, destinoMateriales, destinoMateriales, destinoMateriales, ubicacionInicial, destinoMateriales, ubicacionInicial, destinoMateriales);
	}

    // ------------------------------------------------------------------------------------
    // Clase interna para info de la negociación en curso
    // ------------------------------------------------------------------------------------
    public static class NegotiationData {
		protected String motivoFinNegociacion;
		protected long finNegociacion;
		public long inicioDeNegociacion;
		String conversationId;
        int numReceivers;      // cuántos TRUCKS invitamos
        int numResponses = 0;  // cuántos PROPOSE/REFUSE recibimos
        boolean isDecisionMade = false;

        int numInforms = 0; // cuántos INFORM tras un ACCEPT
      //  int hour, minute;
        private DocumentoOfertaPropuesta dop;

       // Map<AID, Integer> proposals = new HashMap<>();
        private ArrayList<ACLMessage> listaPropuestas;
		public String ganador;
		public int numProposes;
		public int numRefuses;
		public int numCancels;
        NegotiationData(String convId, int numReceivers) {
            this.conversationId = convId;
            this.numReceivers = numReceivers;
            listaPropuestas = new ArrayList<ACLMessage>();
        }

        boolean isFinished() {
            // Concluimos la negociación si se tomó la decisión y ya recibimos al menos
            // 1 INFORM (si hubo un ACCEPT). Si nadie propuso, se finaliza al decidir.
            if (!isDecisionMade) {
                return false;
            }
            if (listaPropuestas.isEmpty()) {
                // Nadie mandó propose
                return true;
            } else {
                // Esperamos 1 INFORM de la parte aceptada
                return numInforms >= 1;
            }
        }

		public DocumentoOfertaPropuesta getDop() {
			return dop;
		}

		public void setDop(DocumentoOfertaPropuesta dop) {
			this.dop = dop;
		}
    }
}
