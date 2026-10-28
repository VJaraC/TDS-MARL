import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.UnreadableException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.FileWriter;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;

/**
 * Ejemplo de Agente Truck que actúa como 'Contractor' en un Contract Net Protocol
 * sin usar ContractNetResponder de JADE.
 * En su lugar, implementa un comportamiento propio que procesa:
 *  - CFP (Call for Proposal)
 *  - ACCEPT_PROPOSAL
 *  - REJECT_PROPOSAL
 */
public class TruckAgent extends Agent {
	
	private CopyOnWriteArrayList<ACLMessage> listaCFPs;
	// CFPs de Shovel pendientes de PROPOSE, esperando el AGREE del UnloadingPointAgent
	private Map<String, ACLMessage> pendingCFPs; // key: unloadingConvId -> CFP de Shovel
	// Contador de consultas al UnloadingPointAgent: hace unico el unloadingConvId.
	// Sin el, dos CFP respondidos en el mismo milisegundo compartian id, la
	// segunda entrada de pendingCFPs pisaba a la primera y el camion terminaba
	// proponiendo a una pala los horarios calculados para la oferta de otra
	// (reservas fantasma y palas con dos camiones en su agenda). Corregido el
	// 2026-10-04, pendiente de confirmar con el profesor.
	private long contadorConsultas = 0;
	// Hora real en que el camion queda libre tras una liberacion (Partial
	// Repair / Complete Regeneration). 0 = sin restriccion. Ver calcularTiempos.
	private long noAntesDe = 0;
	//horario
    private Schedule schedule;

    private Random random;
    private Map<String, Integer> lastRejections;


    // ------------------------------------------------------------------
    // (Opcional) Bandera para habilitar o deshabilitar aprendizaje
    // ------------------------------------------------------------------
    private boolean learningEnabled = false;

    // ------------------------------------------------------------------
    // Q-LEARNING (opcional): Para cada Shovel (estado), Q-values para 2 acciones:
    // action=0 -> REFUSE, action=1 -> PROPOSE
    // Usamos un Map: shovelName -> double[2]
    // ------------------------------------------------------------------
    private Map<String, double[]> qTable;  

    // Parámetros de Q-learning
    private double alpha = 0.01;      // Tasa de aprendizaje
    private double gamma = 0.98;      // Factor de descuento
    private double epsilon = 0.05;    // Probabilidad de exploración

    // Guardamos la última acción elegida por conversación ID
    private Map<String, Integer> lastActionChosen;

	private double capacidadCamion;

	private String ubicacionInicial;

	private double velocidadVacio;

	private long duracionDescarga;

	private double velocidadCargado; 
	
	private Graph<Vertice, DefaultWeightedEdge> grafoMina;
	
	private List<Double> episodeRewards; // Lista para almacenar la recompensa acumulada por episodio
    private int episodeCount = 0; // Contador de episodios
    private static final String LOG_FILE = ParametrosConfiguracion.prefijoArchivos()+"rewards_log.csv"; // Archivo para guardar los datos


    @Override
    protected void setup() {
        lastRejections = new HashMap<>();
        pendingCFPs = new HashMap<>();

    	schedule = new Schedule();
        RegistroAgendasAgentes.registrar(getLocalName(), RegistroAgendasAgentes.CAMION, schedule); // solo observacion
        // Semilla derivada del nombre del camion: cada truck tiene su propia
        // secuencia reproducible (semilla maestra + hash del nombre). Asi dos
        // corridas con la misma SEMILLA_ALEATORIA toman las mismas decisiones
        // de desempate, manteniendo la comparabilidad entre metodos.
        random = new Random(ParametrosSimulador.SEMILLA_ALEATORIA
                + getLocalName().hashCode());
        episodeRewards = new ArrayList<>();
        listaCFPs = new CopyOnWriteArrayList<ACLMessage>();

        // Lectura de argumentos (opcional) para habilitar/deshabilitar aprendizaje
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            try {
                learningEnabled = Boolean.parseBoolean(ParametrosConfiguracion.LEARNING_ENABLED);
                ubicacionInicial = args[0].toString();
                velocidadVacio = Double.parseDouble(args[1].toString());
                capacidadCamion = Double.parseDouble(args[2].toString());
                velocidadCargado = Double.parseDouble(args[3].toString());
                duracionDescarga = Long.parseLong(args[4].toString());
                grafoMina = (Graph<Vertice, DefaultWeightedEdge>) args[5]; 
            } catch (Exception e) {
                // Mantener valor por defecto
            }
        }

        // Plan inicial unico (14-oct-2026): con USAR_PLAN_INICIAL_FIJO la agenda
        // viene de un archivo en vez de la negociacion inicial. Sin el, no hace nada.
        PlanInicialFijo.cargarEn(getLocalName(), RegistroAgendasAgentes.CAMION, schedule);

        System.out.println(getLocalName() + " listo. Aprendizaje habilitado = " + learningEnabled);

        // Inicializamos estructuras para RL
        qTable = new HashMap<>();
        lastActionChosen = new HashMap<>();
        
        addBehaviour(new TickerBehaviour(this, 1000) { //considerar este valor junto con el tiempo antes que se debe contestar a un deadline
            @Override
            protected void onTick() {
                checkAndRespondToCFPs();
                //checkAndRemoveCFPS();
            }

			
        });

        // Añadimos un comportamiento cíclico que atenderá TODOS los mensajes
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = myReceive();
                if (msg != null) {
                    // Procesar el mensaje
                    switch (msg.getPerformative()) {
                        case ACLMessage.CFP:
                            handleCfp(msg);
                            break;
                        case ACLMessage.REQUEST:
                            handleRequest(msg);
                            break;
                        case ACLMessage.REJECT_PROPOSAL:
                            handleRejectProposal(msg);
                            break;
                        case ACLMessage.AGREE:
                            handleAgreeFromUnloadingPoint(msg);
                            break;
                        case ACLMessage.INFORM:
                            // INFORM de rescheduling (cascada o partial repair).
                            // Distinguimos por prefijo del conversationId.
                            if (msg.getConversationId() != null
                                    && msg.getConversationId().startsWith("RESCHEDULE-")) {
                                handleRescheduleInform(msg);
                            } else if (msg.getConversationId() != null
                                    && msg.getConversationId().startsWith("LIBERAR-")) {
                                handleLiberarInform(msg);
                            }
                            break;
                        default:
                            // Ignoramos otros performatives o los procesamos si es necesario
                            break;
                    }
                } else {
                    block(); // No hay mensajes, bloqueo para liberar CPU
                }
            }
        });
        
        addBehaviour(new TickerBehaviour(this, 5000) { // Se ejecuta cada 5 segundos
            @Override
            protected void onTick() {
                resetRejectionsPeriodically();
            }
        });
        
        
    }
    
    private void resetRejectionsPeriodically() {
        for (String shovel : lastRejections.keySet()) {
            int currentRejections = lastRejections.get(shovel);
            if (currentRejections > 0) {
                lastRejections.put(shovel, currentRejections - 1); // Reduce el número de rechazos con el tiempo
            }
        }
    }
    private void checkAndRemoveCFPS() {
		int cfpsDeadlineVencidoYSinPropuesta=0;
		DocumentoOfertaPropuesta dop;
		for (ACLMessage cfp : listaCFPs) {
			System.out.println(this.getLocalName()+" "+cfp.getSender().getLocalName()+" id:"+cfp.getConversationId());
			/*
			try {
				dop = (DocumentoOfertaPropuesta) cfp.getContentObject();
				if (System.currentTimeMillis() > cfp.getReplyByDate().getTime() && dop.getHoraInicioCargaPropuestoPorCamion()<=0) {
					cfpsDeadlineVencidoYSinPropuesta++;
				}
			} catch (UnreadableException e) {
				e.printStackTrace();
			}
			*/

			
		}
		System.out.println(this.getLocalName()+" cantidad de CFPS fuera de su deadline y sin propuesta "+cfpsDeadlineVencidoYSinPropuesta+ "total de CFPs "+listaCFPs.size());
	}
    private void checkAndRespondToCFPs() {
    	//System.out.println( this.getLocalName()+"CFPS guardados "+listaCFPs.size());
        Iterator<ACLMessage> iterator = listaCFPs.iterator();
        while (iterator.hasNext()) {
        	ACLMessage cfp = iterator.next();
        	try {
				DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) cfp.getContentObject();
				if (dop.getHoraLlegadaApalaPropuestoPorCamion()==0){// solo para aquellos CFPs que aun no han enviado propuesta
					//System.out.println( this.getLocalName()+" CFP sin propuesta:"+  dop.toString());
					if (System.currentTimeMillis() >= cfp.getReplyByDate().getTime()  - 2000) { //1000 es en milisegundos y quiere decir que respondera 1000 ms antes del deadline
		                respondToCfp(cfp);
		                //iterator.remove();
		            }
				}
			} catch (UnreadableException e) {
				e.printStackTrace();
			}
        	
            
        }
    }
    
    private void respondToCfp(ACLMessage cfp) {
        try {
            DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) cfp.getContentObject();
            generaPropuesta(dop);
            cfp.setContentObject(dop); // marca el CFP como "en proceso" (horaLlegadaApala != 0)

            if (dop.getHoraLlegadaApalaPropuestoPorCamion() == -1) {
                // No es factible: REFUSE directo al Shovel
                ACLMessage refuse = cfp.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                dop.setContent("REFUSE. No puedo cumplir con lo solicitado: " + dop.getMotivoRefuse());
                refuse.setContentObject(dop);
                removerCFP(cfp.getConversationId());
                mySend(refuse);
            } else {
                // Es factible: consultar al UnloadingPointAgent para obtener horario de descarga
                String unloadingAgentName = dop.getDestinoMateriales().replace("#", "");
                String unloadingConvId = getLocalName() + "-UNL-" + System.currentTimeMillis()
                        + "-" + (contadorConsultas++);

                ACLMessage unloadingRequest = new ACLMessage(ACLMessage.REQUEST);
                unloadingRequest.setConversationId(unloadingConvId);
                unloadingRequest.addReceiver(new jade.core.AID(unloadingAgentName, jade.core.AID.ISLOCALNAME));
                unloadingRequest.setContentObject(dop);

                pendingCFPs.put(unloadingConvId, cfp);
                mySend(unloadingRequest);
            }
        } catch (UnreadableException | IOException e) {
            e.printStackTrace();
        }
    }

    public ACLMessage myReceive() {
        ACLMessage msg = super.receive();
        if (msg != null) {
            // Extraemos datos básicos
            String sender = (msg.getSender() != null) ? msg.getSender().getLocalName() : "unknown";
            String receivers = getReceiverString(msg);
            String performative = ACLMessage.getPerformative(msg.getPerformative());
            String conversationId = (msg.getConversationId() != null) ? msg.getConversationId() : "";
            
            // CORRECCIÓN: Abrir dinámicamente el DOP para leer el contenido real en el log
            String content = "";
            try {
                Object contenido = msg.getContentObject();
                if (contenido instanceof DocumentoOfertaPropuesta) {
                    DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) contenido;
                    if (dop.getContent() != null && !dop.getContent().isEmpty()) {
                        content = dop.getContent();
                    } else if (dop.getMotivoRefuse() != null) {
                        content = "Motivo: " + dop.getMotivoRefuse();
                    } else {
                        content = "DOP - Horario Carga: " + Utilidades.longToHHMMSS(dop.getHoraInicioCargaOfertadaPorPala());
                    }
                } else if (msg.getConversationId() != null && msg.getConversationId().startsWith("RESCHEDULE-")) {
                    content = "Mensaje de Rescheduling Activo";
                } else if (msg.getConversationId() != null && msg.getConversationId().startsWith("LIBERAR-")) {
                    content = "Mensaje de Liberación de Slots";
                } else {
                    content = (msg.getContent() != null) ? msg.getContent() : "Mensaje sin texto plano";
                }
            } catch (UnreadableException e) {
                content = (msg.getContent() != null) ? msg.getContent() : "Error decodificando objeto serializado";
            }

            // Loggear como "RECEIVE" con el contenido real expuesto
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
        String sender = getLocalName();  
        String receivers = getReceiverString(msg);
        String performative = ACLMessage.getPerformative(msg.getPerformative());
        String conversationId = (msg.getConversationId() != null) ? msg.getConversationId() : "";
        
        // CORRECCIÓN: Extraer el contenido del DOP antes de escribir la auditoría de salida
        String content = "";
        try {
            Object contenido = msg.getContentObject();
            if (contenido instanceof DocumentoOfertaPropuesta) {
                DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) contenido;
                if (dop.getContent() != null && !dop.getContent().isEmpty()) {
                    content = dop.getContent();
                } else if (dop.getMotivoRefuse() != null) {
                    content = "Motivo: " + dop.getMotivoRefuse();
                } else {
                    content = "DOP enviado de forma exitosa";
                }
            } else {
                content = (msg.getContent() != null) ? msg.getContent() : "Objeto de control/Mensaje plano";
            }
        } catch (UnreadableException e) {
            content = (msg.getContent() != null) ? msg.getContent() : "Error al procesar el objeto saliente";
        }

        // Loggear en CSV como "SEND" con los datos reales expuestos
        CsvLogger.logMessage(
        		this.getLocalName(),
                "SEND",
                sender,
                receivers,
                performative,
                conversationId,
                content
        );

        // Llamar al método original para enviar de verdad
        super.send(msg);
    }

    

    /**
     * Convierte la lista de receptores de un ACLMessage a un string "AgenteA|AgenteB|..."
     */
    private String getReceiverString(ACLMessage msg) {
        String receiverList = new String();
        jade.util.leap.Iterator it = msg.getAllReceiver();
        while (it.hasNext()) {
            AID r = (AID) it.next();
            receiverList.concat(r.getLocalName()+"/");
        }
        return receiverList;
    }

    // --------------------------------------------------------------------------------------
    // 1. Lógica para procesar CFP (Call for Proposal)
    // --------------------------------------------------------------------------------------
    private void handleCfp(ACLMessage cfp) {
     	listaCFPs.add(cfp); //
        

        
    }

    private boolean hayUnCFPmasConveniente(ACLMessage cfp) {
    	
    
    	
        DocumentoOfertaPropuesta dopLocal=null;
        DocumentoOfertaPropuesta dopNuevo=null;
        boolean hayMejorCFP=false;
		int numeroDeCFPsMejores=0;

		try {
			dopNuevo = (DocumentoOfertaPropuesta) cfp.getContentObject();
			//System.out.println(this.getLocalName()+"hay "+listaCFPs.size()+" CFPs");
			for (ACLMessage localCFP : this.listaCFPs) {
	    		dopLocal = (DocumentoOfertaPropuesta) localCFP.getContentObject();
	    		if (dopLocal.getHoraInicioCargaPropuestoPorCamion()>=0) { //solo considera CFP's que se les ha enviado propuesta
	    			if (overlaps(dopLocal, dopNuevo)) {
		            	//duracion (costo actividad) = duracionViajeVacio + duracionViajeCargado
		            	long duracionActividadesDopLocal = dopLocal.getHoraLlegadaApalaPropuestoPorCamion()-dopLocal.getHoraInicioViajeVacioPropuestoPorCamion()
		            			+dopLocal.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion()-dopLocal.getHoraInicioViajeCargadoPropuestoPorCamion();
		            	long duracionActividadesDopNuevo = dopNuevo.getHoraLlegadaApalaPropuestoPorCamion()-dopNuevo.getHoraInicioViajeVacioPropuestoPorCamion()
		            			+dopNuevo.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion()-dopNuevo.getHoraInicioViajeCargadoPropuestoPorCamion();
		            	//System.out.println("overlaps encontrado. costo local:"+duracionActividadesDopLocal+" costo nuevo"+duracionActividadesDopNuevo);

		            	if (duracionActividadesDopLocal<duracionActividadesDopNuevo) {
		            		//System.out.println(this.getLocalName()+" el DOP nuevo posee un costo de  "+duracionActividadesDopNuevo+" y el DOP almacenado un costo de "+duracionActividadesDopLocal);
		            		hayMejorCFP = true;
		            		numeroDeCFPsMejores++;
		            		// Usa el random sembrado del agente (no uno nuevo sin
		            		// semilla) para que el desempate sea reproducible.
		                    return this.random.nextBoolean();
		            	}
		                
		            }
	    		}
	            
	        }
		} catch (UnreadableException e) {
			e.printStackTrace();
		}
		System.out.println(this.getLocalName()+" hay "+numeroDeCFPsMejores+" cpfs mejores");
		return hayMejorCFP;
		
	}

	private boolean overlaps(DocumentoOfertaPropuesta dopLocal, DocumentoOfertaPropuesta dopNuevo) {
		if (dopLocal.getHoraInicioViajeVacioPropuestoPorCamion()<=dopNuevo.getHoraInicioViajeVacioPropuestoPorCamion()&&dopNuevo.getHoraInicioViajeVacioPropuestoPorCamion()<=dopLocal.getHoraFinDeDescargaPropuestoPorCamion()) {
			return true;
		}
		
		if (dopLocal.getHoraInicioViajeVacioPropuestoPorCamion()<=dopNuevo.getHoraFinDeDescargaPropuestoPorCamion()&&dopNuevo.getHoraFinDeDescargaPropuestoPorCamion()<=dopLocal.getHoraFinDeDescargaPropuestoPorCamion()) {
			return true;
		}
		
		
		
		return false;
	}

	// --------------------------------------------------------------------------------------
    // 2. Lógica para procesar REQUEST del ShovelAgent (el Shovel acepta la propuesta del camión)
    // --------------------------------------------------------------------------------------
	private void handleRequest(ACLMessage request) {
        try {
            DocumentoOfertaPropuesta dop = (DocumentoOfertaPropuesta) request.getContentObject();

            if (schedule.estaCamionOcupado(dop)) {
                ACLMessage refuse = request.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                
                // CORRECCIÓN: Cambiar mensaje genérico por el motivo real
                dop.setContent("REFUSE: El camion ya se encuentra ocupado en otra asignacion coordinada");
                refuse.setContentObject(dop);
                removerCFP(request.getConversationId());
                mySend(refuse);
                return;
            }
            
            // Lógica normal de confirmación si está libre...
            if (dop.getTiempoInactivoDesdeActividadAnterior() <= 60000 && hayUnCFPmasConveniente(request)) {
                ACLMessage disconfirm = request.createReply();
                disconfirm.setPerformative(ACLMessage.DISCONFIRM);
                dop.setContent("DISCONFIRM: Se detecto un CFP con mejor optimizacion multicriterio");
                disconfirm.setContentObject(dop);
                removerCFP(request.getConversationId());
                mySend(disconfirm);
                return;
            }

            if (learningEnabled) {
                String shovelName = request.getSender().getLocalName();
                int action = lastActionChosen.getOrDefault(request.getConversationId(), 1);
                updateQValue(shovelName, action, +5.0);
            }

            ACLMessage confirm = request.createReply();
            confirm.setPerformative(ACLMessage.CONFIRM);
            dop.setContent("CONFIRM: Trato cerrado, procediendo a enrutamiento");
            confirm.setContentObject(dop);
            agregaGanador(dop);
            removerCFPsOverlaps(dop);
            removerCFP(request.getConversationId());
            mySend(confirm);

            String unloadingAgentName = dop.getDestinoMateriales().replace("#", "");
            ACLMessage inform = new ACLMessage(ACLMessage.INFORM);
            inform.setConversationId(getLocalName() + "-INFORM-" + System.currentTimeMillis());
            inform.addReceiver(new jade.core.AID(unloadingAgentName, jade.core.AID.ISLOCALNAME));
            inform.setContentObject(dop);
            mySend(inform);

        } catch (UnreadableException | IOException e) {
            e.printStackTrace();
        }
    }

    private void handleAgreeFromUnloadingPoint(ACLMessage agree) {
        String unloadingConvId = agree.getConversationId();
        ACLMessage cfp = pendingCFPs.remove(unloadingConvId);

        if (cfp == null) return;

        try {
            // DOP con el horario de descarga confirmado por el UnloadingPointAgent
            DocumentoOfertaPropuesta dopActualizado = (DocumentoOfertaPropuesta) agree.getContentObject();
            String shovelName = cfp.getSender().getLocalName();

            ACLMessage propose = cfp.createReply();

            if (learningEnabled) {
                double[] qValues = qTable.getOrDefault(shovelName, new double[]{0.0, 0.0});
                qTable.putIfAbsent(shovelName, qValues);
                int action = chooseActionEGreedy(qValues);
                lastActionChosen.put(cfp.getConversationId(), action);

                if (action == 0) {
                    propose.setPerformative(ACLMessage.REFUSE);
                    dopActualizado.setContent("REFUSE por RL");
                    propose.setContentObject(dopActualizado);
                    removerCFP(cfp.getConversationId());
                } else {
                    propose.setPerformative(ACLMessage.PROPOSE);
                    propose.setContentObject(dopActualizado);
                }
            } else {
                propose.setPerformative(ACLMessage.PROPOSE);
                propose.setContentObject(dopActualizado);
            }

            mySend(propose);

        } catch (UnreadableException | IOException e) {
            e.printStackTrace();
        }
    }

    private void removerCFPsOverlaps(DocumentoOfertaPropuesta dopNuevo) {
    	DocumentoOfertaPropuesta dopLocal;
    	for (ACLMessage cfp : listaCFPs) {
    		try {
				dopLocal = (DocumentoOfertaPropuesta) cfp.getContentObject();
				if (overlaps(dopNuevo, dopLocal)) {
        	        removerCFP(cfp.getConversationId());
				}
			} catch (UnreadableException e) {
				e.printStackTrace();
			}
    	}
		
	}

	// --------------------------------------------------------------------------------------
    // 3. Lógica para procesar REJECT_PROPOSAL
    // --------------------------------------------------------------------------------------
    private void handleRejectProposal(ACLMessage rejectMsg) {
       /* System.out.println(getLocalName() + " - Mi propuesta fue RECHAZADA. "
                           + rejectMsg.getContent());
        */
        if (learningEnabled) {
            String shovelName = rejectMsg.getSender().getLocalName();

            int action = lastActionChosen.getOrDefault(rejectMsg.getConversationId(), 1);
            
            // Obtener el número de rechazos previos de esta ShovelAgent
            int numRechazos = lastRejections.getOrDefault(shovelName, 0);

            lastRejections.put(shovelName, numRechazos + 1);

            double reward = -0.1 * (numRechazos + 1); // Penalización aumenta con cada rechazo
            updateQValue(shovelName, action, reward);
        }
        
        
        
	        removerCFP(rejectMsg.getConversationId());

		
		
    }

    

    private void removerCFP(String idconversacion) {
    		for (ACLMessage cfp : listaCFPs) {
    			if (cfp.getConversationId().equals(idconversacion)) {
    				listaCFPs.remove(cfp); // Funciona correctamente
                }
            }
    	

    		
    	
		
	}

	// --------------------------------------------------------------------------------------
    // 5. Métodos Auxiliares de RL (si learningEnabled=true)
    // --------------------------------------------------------------------------------------

    /**
     * Selecciona acción (0=REFUSE, 1=PROPOSE) usando política e-greedy.
     */
    private int chooseActionEGreedy(double[] qValues) {
        // Exploración
        if (random.nextDouble() < epsilon) {
            return random.nextInt(2); // 0 o 1
        }
        // Explotación
        if (qValues[0] > qValues[1]) {
            return 0; // REFUSE
        } else {
            return 1; // PROPOSE
        }
    }

    /**
     * Actualiza Q(s,a) usando la ecuación:
     * Q(s,a) <- Q(s,a) + alpha * [ r + gamma * max_a' Q(s,a') - Q(s,a) ]
     *
     * Consideramos el "estado" = shovelName, que no cambia.
     * max Q(s,a') => mayor valor entre Q(estado,0) y Q(estado,1).
     */
    private void updateQValue(String shovelName, int action, double reward) {
        double[] qValues = qTable.getOrDefault(shovelName, new double[]{0.0, 0.0});
        double currentQ = qValues[action];

        double maxNextQ = Math.max(qValues[0], qValues[1]);

        double newQ = currentQ + alpha * (reward + gamma * maxNextQ - currentQ);
        if (reward >= 0) {
            qValues[action] = newQ; // Solo actualiza si hay nueva información
        }
        // Almacenar de vuelta
        qTable.put(shovelName, qValues);
        
     // Registrar recompensa acumulada para el episodio actual
        if (episodeRewards.size() <= episodeCount) {
            episodeRewards.add(0.0); // Inicializa episodio si no existe
        }
        episodeRewards.set(episodeCount, episodeRewards.get(episodeCount) + reward);
        endEpisode();
    }
    
    private void endEpisode() {
        episodeCount++;
        logRewards();
    }
    
 // Guardar datos en CSV
    private void logRewards() {
        try (FileWriter writer = new FileWriter(LOG_FILE, true)) {
            writer.write(episodeCount + "," + episodeRewards.get(episodeCount - 1) + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Aplica el right-shift en el schedule INTERNO del TruckAgent.
     *
     * ¿Por qué es el cambio más crítico?
     * TruckAgent.calcularTiempos() usa schedule.buscarSlotAnterior() para
     * saber cuándo termina su última actividad y así proponer la siguiente.
     * Si el schedule sigue mostrando que el camión acaba en T=100 cuando
     * en realidad acaba en T=150, el camión propondrá iniciar el siguiente
     * ciclo desde T=100 → conflicto en la pala o en el punto de descarga.
     * Al shiftear los slots futuros a T+delta, la siguiente propuesta del
     * camión partirá del tiempo correcto.
     */
    private void handleRescheduleInform(ACLMessage inform) {
        try {
            DocumentoRescheduling docR =
                    (DocumentoRescheduling) inform.getContentObject();
            // Doble check: el mensaje puede llegar a otros camiones por error
            if (!docR.getIdCamion().equals(getLocalName())) return;

            // Horario exacto de cada slot segun la cascada (ver aplicarA).
            int n = docR.aplicarA(schedule.getSlots(), null, null);
            if (n < 0) {
                n = 0;
                for (Slot s : schedule.getSlots()) {
                    if (s.getHoraInicio() > docR.getTiempoCorte()) {
                        s.setHoraInicio(s.getHoraInicio() + docR.getDeltaT());
                        s.setHoraFin   (s.getHoraFin()    + docR.getDeltaT());
                        n++;
                    }
                }
            }
            // la agenda se mantiene ordenada por hora de inicio
            schedule.getSlots().sort(java.util.Comparator.comparingLong(Slot::getHoraInicio));
            System.out.printf("[%s] RIGHT-SHIFT recibido: +%.1fmin sobre %d slots futuros%n",
                    getLocalName(), docR.getDeltaT() / 60_000.0, n);
            CsvLogger.logMessage(
                    getLocalName(), "RESCHEDULE_APLICADO",
                    inform.getSender().getLocalName(), getLocalName(),
                    "INFORM", inform.getConversationId(),
                    "right-shift +" + Utilidades.longToHHMMSS(docR.getDeltaT())
                            + " sobre " + n + " slots");
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
    }

    /**
     * Partial Repair: liberaron mis slots futuros. Los borro de mi schedule
     * interno y reseteo mi estado de negociacion para volver a aceptar CFPs
     * de cualquier pala. Asi el CNP me reasigna naturalmente.
     */
    private void handleLiberarInform(ACLMessage inform) {
        try {
            DocumentoLiberacion docL =
                    (DocumentoLiberacion) inform.getContentObject();
            // Doble check: el mensaje puede llegar a otros camiones por error.
            if (!docL.getIdCamion().equals(getLocalName())) return;

            // Hora en que quedo libre DE VERDAD (fin real esperado del ciclo
            // que esta haciendo). Sus tareas nuevas no salen antes: ver
            // calcularTiempos. Cambio del 2026-10-07.
            if (docL.getDisponibleDesde() >= 0) noAntesDe = docL.getDisponibleDesde();

            // 1. Borrar mis slots con horaInicio > corte.
            int n = 0;
            java.util.Iterator<Slot> it = schedule.getSlots().iterator();
            while (it.hasNext()) {
                Slot s = it.next();
                if (s.getHoraInicio() > docL.getCorte()) {
                    it.remove();
                    n++;
                }
            }

            // 2. Resetear estado de negociacion para volver a aceptar CFPs.
            //    Borrar noEnviarCFP entero (max libertad para redirigir a
            //    palas menos saturadas, es la idea del Partial Repair).
            //    pendingCFPs tambien — eran ofertas viejas de cuando estabamos
            //    busy, ahora estamos libres post-corte.
            if (listaCFPs != null) listaCFPs.clear();
            if (pendingCFPs != null) pendingCFPs.clear();

            System.out.printf("[%s] PARTIAL REPAIR recibido: liberados %d slots futuros (corte=%s), estado de negociacion reseteado%n",
                    getLocalName(), n, Utilidades.longToHHMMSS(docL.getCorte()));
            CsvLogger.logMessage(
                    getLocalName(), "LIBERAR_APLICADO",
                    inform.getSender().getLocalName(), getLocalName(),
                    "INFORM", inform.getConversationId(),
                    "borrados " + n + " slots futuros"
                            + " corte=" + Utilidades.longToHHMMSS(docL.getCorte())
                            + " motivo=" + docL.getMotivo());
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
    }


    @Override
    protected void takeDown() {
        System.out.println(getLocalName() + " finalizando. ¡Adiós!");
    }

    public void generaPropuesta(DocumentoOfertaPropuesta dop) {
		dop.setIdCamion(this.getLocalName());
		dop.setCantidadEstimadaDeMaterialATransportar(capacidadCamion);
		
		calcularTiempos(dop);
		
		// CORRECCIÓN 1: Validar si falló el enrutamiento en el grafo de la mina
		if (dop.getHoraLlegadaApalaPropuestoPorCamion() == -1 && "Ruta inexistente".equals(dop.getMotivoRefuse())) {
		    dop.setContent("REFUSE: No se encontro ruta en el grafo de la mina");
		    return;
		}

		// CORRECCIÓN 2: Mensajes explícitos según el estado interno del camión
		if (schedule.estaCamionOcupado(dop)) {
			dop.setHoraLlegadaApalaPropuestoPorCamion(-1);
			dop.setMotivoRefuse("Camion ocupado o ya asignado");
			dop.setContent("REFUSE: Ya estoy asignado a otra pala en ese horario");
				
		} else {
			if (!schedule.camionPuedeRealizarCicloPropuesto(dop)) {
				dop.setHoraLlegadaApalaPropuestoPorCamion(-1);
				dop.setMotivoRefuse("Camion no puede realizar ciclo propuesto");
				dop.setContent("REFUSE: Ciclo invalido o duracion excede el maximo disponible");
			} else {
				if (dop.getHoraInicioCargaPropuestoPorCamion() < dop.getHoraInicioCargaOfertadaPorPala()) {
					dop.setHoraLlegadaApalaPropuestoPorCamion(-1);
					dop.setMotivoRefuse("Camion intenta llegar antes de lo ofertado");
					dop.setContent("REFUSE: Inconsistencia temporal, llegada propuesta antes de la oferta de la pala");
				} else {
				    dop.setContent("PROPOSE: Propuesta factible calculada exitosamente");
				}
			}
		}
		
		
		/*
		if (schedule.estaCamionOcupado(dop)) {
			//System.out.println("camion ocupado, retorno -1");
			dop.setHoraLlegadaApalaPropuestoPorCamion(-1);
			dop.setMotivoRefuse("camion ocupado");
				
		} else {
			calcularTiempos(dop);
			if (!schedule.camionPuedeRealizarCicloPropuesto(dop)) {
				//System.out.println("camion no puede realizar ciclo propuesto, retorno -1");
				dop.setHoraLlegadaApalaPropuestoPorCamion(-1);
				dop.setMotivoRefuse("camion no puede realizar ciclo propuesto");
			} else {
				if (dop.getHoraInicioCargaPropuestoPorCamion()<dop.getHoraInicioCargaOfertadaPorPala()) {
					dop.setHoraLlegadaApalaPropuestoPorCamion(-1);
					dop.setMotivoRefuse("camion esta ofreciendo llegar antes que lo ofertado por pala");
				} else {
					//System.out.println("camion si puede realizar ciclo propuesto"); 
					//System.out.println(dop.toString());
				}
				 
			}
		

		}
		*/

	}
    
    private void calcularTiempos(DocumentoOfertaPropuesta dop) {
		//System.out.println(dop.toString());
		/*
		 * hay que calcular duracionCiclo = viajeVacio + carga + viajeCargado
		 * viajeVacio = duracionViaje(sAnterior.getUbicacion(), dop.getUbicacionPala(), velocidadVacio);
		 * carga = (capacidadCamion / capacidadPala)*duracionPalada
		 * viajeCargado = duracionViaje(dop.ubicacionPala, dop.destinoMateriales, velocidadCargado)
		 *
		 */
		// VELOCIDAD DEL MOMENTO (pedido del profesor, reunion 2026-07-03):
		// "cuando dispare el rescheduling, que use la velocidad que estan
		// usando EN ESTE MOMENTO". Las propuestas de tiempo se calculan con la
		// velocidad EFECTIVA actual = nominal * factorPropio * factorClima(ahora):
		//   - factorPropio: desviacion fija del camion (±3%, sembrada).
		//   - factorClima:  multiplicador vigente AHORA en el reloj de sim
		//     (lluvia x0.80, neblina x0.60, tormenta x0.40). Es la "informacion
		//     del momento" de un despachador real: sabe que esta lloviendo y
		//     estima con las condiciones actuales (no conoce el clima futuro,
		//     eso seria ver el futuro -> anti-causal).
		// Sin esto, un camion renegociando tras Partial Repair / Complete
		// Regeneration en plena tormenta propone tiempos de cielo despejado,
		// llega tarde de inmediato y dispara otro rescheduling -> bucle.
		// Durante la negociacion inicial (reloj=0, sin clima activo) el factor
		// clima es 1.0, y con incertidumbre desactivada todo es 1.0 (no-op).
		//
		// AJUSTE 2026-10-05: el clima del momento se aplica SOLO mientras dura.
		// Antes todo el viaje se calculaba con el factor vigente: renegociando
		// a las 4:22 en tormenta (x0,40 hasta las 4:45), un viaje que salia a
		// las 5:30 quedaba 2,5 veces mas largo. En grande_V4_COMPLETE eso dejo
		// 15 camiones sin trabajo (Truck14: 22,8 + 28,5 min pasaban a 57 + 71).
		// Ahora se conoce cuando termina el clima que ya empezo (no el que
		// viene) y el tramo posterior va a velocidad normal: ver
		// GestorEventos.duracionConClimaConocido. Pendiente de confirmar con
		// el profesor.
		double factorPropio = RegistroSimulacion.getInstancia().getFactorVelocidadCamion(getLocalName());
		GestorEventos clima = GestorEventos.getInstancia();
		long reloj = VentanaSimulacion.tiempoSimulacionGlobalMs;
		// Ninguna tarea nueva sale antes de ahora ni antes de que el camion quede
		// libre de verdad tras una liberacion (2026-10-07).
		long noAntes = Math.max(reloj, noAntesDe);
		// Las duraciones de abajo son SIN clima; el clima se aplica por tramo.
		double velocidadVacioEfectiva = velocidadVacio * factorPropio;
		double velocidadCargadoEfectiva = velocidadCargado * factorPropio;

    	Slot sAnterior = schedule.buscarSlotAnterior(dop.getHoraInicioCargaOfertadaPorPala());
		long duracionViajeVacio = 0;
		if (schedule.getSlots().isEmpty() || sAnterior == null) {
			duracionViajeVacio = duracionViaje(ubicacionInicial, dop.getUbicacionPala(), velocidadVacioEfectiva);
		} else {
			duracionViajeVacio = duracionViaje(sAnterior.getUbicacion(), dop.getUbicacionPala(), velocidadVacioEfectiva);
		}

		long duracionCarga = (int)(capacidadCamion / dop.getCapacidadPala()) * dop.getDuracionPalada();
		long duracionViajeCargado = duracionViaje(dop.getUbicacionPala(), dop.getDestinoMateriales(), velocidadCargadoEfectiva);
		
		// CORRECCIÓN: Si Dijkstra no encuentra un camino (-1), marcar de inmediato el error de ruta
		if (duracionViajeVacio == -1 || duracionViajeCargado == -1) {
		    dop.setHoraLlegadaApalaPropuestoPorCamion(-1);
		    dop.setMotivoRefuse("Ruta inexistente");
		    return;
		}

		if (schedule.getSlots().isEmpty() || sAnterior == null) {
			// Sin tarea previa el camion sale para llegar JUSTO a la hora ofrecida,
			// igual que cuando si tiene tarea previa (rama de abajo), y nunca en el
			// pasado del reloj. Antes salia siempre a las 0:00: con una oferta
			// posterior a su tiempo de viaje "llegaba antes de lo ofertado" y la
			// rechazaba, asi que un camion que no conseguia tarea en los primeros
			// minutos quedaba sin trabajo todo el turno (en el grande, 23 a 43 de
			// 105 camiones). Para la oferta de las 0:00 el resultado es el mismo
			// de antes. Cambio del 2026-10-04, pendiente de confirmar con el profesor.
			long salida = Math.max(noAntes, clima.salidaParaLlegarConClimaConocido(
					dop.getHoraInicioCargaOfertadaPorPala(), duracionViajeVacio, reloj));
			salida = Math.max(0, salida);
			dop.setHoraInicioViajeVacioPropuestoPorCamion(salida);
			dop.setHoraLlegadaApalaPropuestoPorCamion(salida
					+ clima.duracionConClimaConocido(salida, duracionViajeVacio, reloj));
		} else {
			long salidaJusta = clima.salidaParaLlegarConClimaConocido(
					dop.getHoraInicioCargaOfertadaPorPala(), duracionViajeVacio, reloj);
			// Para llegar justo, el camion tiene que salir despues de su tarea
			// anterior Y despues de ahora (no se sale en el pasado) Y despues de
			// la hora en que queda libre de verdad (noAntesDe). Si no alcanza,
			// sigue la regla original de abajo, que termina rechazando la
			// oferta igual que antes. Sin renegociacion (reloj 0, noAntesDe 0)
			// es exactamente la condicion original. Cambio del 2026-10-07.
			if (salidaJusta >= Math.max(sAnterior.getHoraFin(), noAntes)) {
				dop.setHoraInicioViajeVacioPropuestoPorCamion(salidaJusta);
				dop.setHoraLlegadaApalaPropuestoPorCamion(dop.getHoraInicioCargaOfertadaPorPala());
			} else {
				dop.setHoraInicioViajeVacioPropuestoPorCamion(sAnterior.getHoraFin());
				dop.setHoraLlegadaApalaPropuestoPorCamion(sAnterior.getHoraFin()
						+ clima.duracionConClimaConocido(sAnterior.getHoraFin(), duracionViajeVacio, reloj));
			}
		}
		dop.setHoraInicioCargaPropuestoPorCamion(dop.getHoraLlegadaApalaPropuestoPorCamion());
		dop.setHoraInicioViajeCargadoPropuestoPorCamion(dop.getHoraInicioCargaPropuestoPorCamion() + duracionCarga);
		dop.setHoraLlegadaAlugarDeDescargaPropuestoPorCamion(dop.getHoraInicioViajeCargadoPropuestoPorCamion()
				+ clima.duracionConClimaConocido(dop.getHoraInicioViajeCargadoPropuestoPorCamion(), duracionViajeCargado, reloj));
		dop.setHoraFinDeDescargaPropuestoPorCamion(dop.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion() + duracionDescarga);
	}
    
    private long duracionViaje(String inicio, String destino, double velocidad) {
		Vertice vInicio=Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, inicio);
		Vertice vDestino=Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, destino);
		/*System.out.println(vInicio.toString());
		System.out.println(vDestino.toString());
		System.out.println(grafoMina.toString());
		*/		
		GraphPath<Vertice, DefaultWeightedEdge> camino = DijkstraShortestPath.findPathBetween(grafoMina, vInicio, vDestino);		
		if (camino==null) {
			//System.out.println("no existe camino entre "+inicio+" y "+destino);
			return -1;
		} else {
			double distancia = camino.getWeight();
			//System.out.println("Distancia "+distancia+" velocidad "+velocidad);
			return (long) ((distancia/velocidad)*60*60*1000);			
		}	
	}
    public void agregaGanador(DocumentoOfertaPropuesta dop) {
		schedule.agregarSlot(this.getLocalName(), "", dop.getHoraInicioViajeVacioPropuestoPorCamion(), dop.getHoraLlegadaApalaPropuestoPorCamion(), dop.getUbicacionPala(), Slot.enum_operacion.VIAJE_VACIO, 0);
		schedule.agregarSlot(this.getLocalName(), dop.getIdPala(), dop.getHoraInicioCargaPropuestoPorCamion(), dop.getHoraInicioViajeCargadoPropuestoPorCamion(), dop.getUbicacionPala(), Slot.enum_operacion.CARGA, dop.getCantidadEstimadaDeMaterialATransportar());
		schedule.agregarSlot(this.getLocalName(), "", dop.getHoraInicioViajeCargadoPropuestoPorCamion(), dop.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion(), dop.getDestinoMateriales(), Slot.enum_operacion.VIAJE_CARGADO, dop.getCantidadEstimadaDeMaterialATransportar());
		schedule.agregarSlot(this.getLocalName(), "", dop.getHoraLlegadaAlugarDeDescargaPropuestoPorCamion(), dop.getHoraFinDeDescargaPropuestoPorCamion(), dop.getDestinoMateriales(), Slot.enum_operacion.DESCARGA, dop.getCantidadEstimadaDeMaterialATransportar());
		//schedule.imprimirSchedule(this.getLocalName());
		
	}
}
