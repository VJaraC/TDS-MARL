import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

/**
 * Clase principal que inicializa el contenedor JADE y lanza varios agentes Shovel y Truck.
 */
public class MainContainer {
    /** Contenedor principal de JADE; lo usa CierrePlanInicial para apagar la plataforma. */
    public static volatile AgentContainer contenedorPrincipal;

    public static void main(String[] args) {

    	// Parametros entregados al lanzar (-Dtds.*): si alguno es invalido, no arranca.
    	ConfiguracionCorrida.validar();

    	// Si la corrida termina tras el plan inicial, la etiqueta no puede estar usada.
    	CierrePlanInicial.verificarEtiquetaLibre();

    	// === simulador (plus) === borra CSVs de la corrida anterior para empezar limpio
    	InicializadorSimulador.limpiarArchivosPrevios();

    	//crear grafo mina
    	SimpleWeightedGraph<Vertice, DefaultWeightedEdge> grafoMina = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
    	crearGrafoMina(grafoMina);
    	
        // Inicializa el runtime de JADE
        Runtime rt = Runtime.instance();
        
        // Perfil por defecto (localhost, puerto 1099, GUI de JADE)
        Profile profile = new ProfileImpl();
        AgentContainer mainContainer = rt.createMainContainer(profile);
        contenedorPrincipal = mainContainer;
        crearAgentesUnloadingPoints(mainContainer);
        crearAgentesPalas(mainContainer, grafoMina);
        crearAgentesCamiones(mainContainer, grafoMina);
        crearAgenteRescheduling(mainContainer);   // módulo right-shift rescheduling

        // === simulador (plus) === lee el OWL, arranca el lector de schedules y abre la ventana
        InicializadorSimulador.iniciar(grafoMina);
    }
    
    private static void crearAgentesUnloadingPoints(AgentContainer container) {
		String[] tags = {"mine:Wastedump", "mine:Crusher", "mine:Stockpile"};
		try {
			File inputFile = new File(ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(inputFile);
			doc.getDocumentElement().normalize();
			for (String tag : tags) {
				NodeList lista = doc.getElementsByTagName(tag);
				for (int temp = 0; temp < lista.getLength(); temp++) {
					Node nNode = lista.item(temp);
					if (nNode.getNodeType() == Node.ELEMENT_NODE) {
						Element eElement = (Element) nNode;
						String nombre = eElement.getAttribute("rdf:ID");
						NodeList childNodes = eElement.getChildNodes();
						String ubicacion = childNodes.item(1).getAttributes().item(0).getTextContent();
						int maximumDischarge = Integer.parseInt(childNodes.item(3).getTextContent().trim());
						Object[] argumentos = {ubicacion, maximumDischarge};
						AgentController agent = container.createNewAgent(nombre, UnloadingPointAgent.class.getName(), argumentos);
						agent.start();
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

    private static void crearAgentesPalas(AgentContainer container, Graph<Vertice, DefaultWeightedEdge> grafoMina) {
		try {
			File inputFile = new File(ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(inputFile);
			doc.getDocumentElement().normalize();
			NodeList listaPalas = doc.getElementsByTagName("mine:Shovel");
			ParametrosConfiguracion.NUMERODESHOVELS=listaPalas.getLength();
			for (int temp = 0; temp < listaPalas.getLength(); temp++) {
				Node nNode = listaPalas.item(temp);
				if (nNode.getNodeType() == Node.ELEMENT_NODE) {
					Element eElement = (Element) nNode;
					String nombre = eElement.getAttribute("rdf:ID");
					NodeList childNodes = eElement.getChildNodes();
					String ubicacionInicial = childNodes.item(1).getAttributes().item(0).getTextContent();
					double capacidad = Double.parseDouble(childNodes.item(3).getTextContent());
					long duracionPalada = Long.parseLong(childNodes.item(5).getTextContent());
					String destinoMateriales = childNodes.item(15).getAttributes().item(0).getTextContent();
					Object[] argumentos = {ubicacionInicial, capacidad, destinoMateriales, duracionPalada, grafoMina};
					AgentController agent = container.createNewAgent(nombre, ShovelAgent.class.getName(), argumentos);
					agent.start();					
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void crearAgentesCamiones(AgentContainer container, Graph<Vertice, DefaultWeightedEdge> grafoMina) {
		try {
			File inputFile = new File(ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(inputFile);
			doc.getDocumentElement().normalize();
			NodeList listaCamiones = doc.getElementsByTagName("trans:Truck");
			ParametrosConfiguracion.NUMERODETRUCKS=listaCamiones.getLength();
			for (int temp = 0; temp < listaCamiones.getLength(); temp++) {
				Node nNode = listaCamiones.item(temp);
				if (nNode.getNodeType() == Node.ELEMENT_NODE) {
					Element eElement = (Element) nNode;
					//String nombre = eElement.getAttribute("rdf:ID");	
					String nombre = "Truck"+temp;
					NodeList childNodes = eElement.getChildNodes();
					String ubicacionInicial = childNodes.item(3).getAttributes().item(0).getTextContent();
					double velocidadMaxima = Double.parseDouble(childNodes.item(1).getTextContent());
					double capacidad = Double.parseDouble(childNodes.item(5).getTextContent());	
					double velocidadCargado = Double.parseDouble(childNodes.item(11).getTextContent());
					long duracionDescarga = Long.parseLong(childNodes.item(9).getTextContent());
					Object[] argumentos = { ubicacionInicial, velocidadMaxima, capacidad, velocidadCargado, duracionDescarga, grafoMina};					
					AgentController responderAgent = container.createNewAgent(
							nombre, TruckAgent.class.getName(), argumentos);
					responderAgent.start();					
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}  

    /**
     * Crea el agente de rescheduling right-shift.
     * Necesita estar en el contenedor JADE para enviar mensajes a los demás
     * agentes directamente. Se suspende solo si las incertidumbres están
     * desactivadas (no hay retrasos reales que detectar en baseline puro).
     */
    private static void crearAgenteRescheduling(AgentContainer container) {
        try {
            AgentController agent = container.createNewAgent(
                    "ReschedulingAgent",
                    ReschedulingAgent.class.getName(),
                    new Object[]{});
            agent.start();
            System.out.println("[main] ReschedulingAgent creado."
                    + " Umbral Nivel2=" + ReschedulingAgent.UMBRAL_NIVEL2 / 60_000 + "min");
        } catch (Exception e) {
            System.err.println("[main] Error creando ReschedulingAgent: " + e.getMessage());
            e.printStackTrace();
        }
    }

	private static void crearGrafoMina(SimpleWeightedGraph<Vertice, DefaultWeightedEdge> grafoMina) {
		try {
			File inputFile = new File("points_20032019.owl");
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(inputFile);
			doc.getDocumentElement().normalize();
			//System.out.println("Root element :" + doc.getDocumentElement().getNodeName());
			NodeList listaVertex = doc.getElementsByTagName("tlo:Vertex");
			NodeList listaCaminos = doc.getElementsByTagName("trans:Road");

			// System.out.println("----------------------------nro elementos: "+listaVertex.getLength());
			System.out.println("levantando mapa virtual mina");
			System.out.println("nodos en archivo "+listaVertex.getLength());			
			for (int temp = 0; temp < listaVertex.getLength(); temp++) {
				Node nNode = listaVertex.item(temp);
				//System.out.println("\nCurrent Element :" + nNode.getNodeName());

				if (nNode.getNodeType() == Node.ELEMENT_NODE) {
					Element eElement = (Element) nNode;
					/*System.out.println("idVertex : " 
							+ eElement.getAttribute("rdf:about"));
					System.out.println("latitude : " 
							+ eElement
							.getElementsByTagName("tlo:latitude")
							.item(0)
							.getTextContent());
					System.out.println("longitude : " 
							+ eElement
							.getElementsByTagName("tlo:longitude")
							.item(0)
							.getTextContent());
							*/

					Vertice v= new Vertice(eElement.getAttribute("rdf:about"),
							Double.parseDouble(eElement
									.getElementsByTagName("tlo:latitude")
									.item(0)
									.getTextContent()),
							Double.parseDouble(eElement
									.getElementsByTagName("tlo:longitude")
									.item(0)
									.getTextContent()));
					grafoMina.addVertex(v);
				}
			}
			System.out.println("nodos levantados por Jgrapht "+grafoMina.vertexSet().size());
			System.out.println("caminos en archivo "+listaCaminos.getLength());
			int fallosCaminos=0;
			for (int temp = 0; temp < listaCaminos.getLength(); temp++) {
				Node nNode = listaCaminos.item(temp);
				if (nNode.getNodeType() == Node.ELEMENT_NODE) {
					Element eElement = (Element) nNode;
					/*System.out.println("idCamino : " 
							+ eElement.getAttribute("rdf:ID"));
							*/
					NodeList childNodes = eElement.getChildNodes();
					Vertice v1 = null, v2 = null;
					v1=Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, childNodes.item(1).getAttributes().item(0).getNodeValue());
					v2=Utilidades.retornaVerticeDesdeGrafoMina(grafoMina, childNodes.item(3).getAttributes().item(0).getNodeValue());

					
					String distancia = eElement
							.getElementsByTagName("tlo:edgeLength")
							.item(0)
							.getTextContent();
					/*
					System.out.println(v1.toString());
					System.out.println(v2.toString());
					System.out.println("distancia "+Double.parseDouble(distancia));
					*/

					if (v1!=null && v2!=null) {
						//System.out.println("grafo mina nro vertex "+grafoMina.vertexSet().size());
						DefaultWeightedEdge e1 = (DefaultWeightedEdge) grafoMina.addEdge(v1, v2);
						if (e1==null) {
							fallosCaminos++;
							//System.out.println("fallo la creacion del edge");
							
							/*
							 * #NR242 latitud:-20.96430814 longitud:-68.71595737
#NR238 latitud:-20.9620084 longitud:-68.71799749
							 */
							
						} else {
							Double d = Double.parseDouble(distancia);
							//System.out.println(e1.toString());
							//System.out.println(d);
							grafoMina.setEdgeWeight(e1,d );
						}
						
						
					} else {
						System.out.println("v1 o v2 es null");
					}

				}
			}
			System.out.println("caminos levantados por Jgrapht "+grafoMina.edgeSet().size());
			System.out.println("caminos NO levantados por Jgrapht "+fallosCaminos);




		} catch (Exception e) {
			e.printStackTrace();
		}

		
	}
}