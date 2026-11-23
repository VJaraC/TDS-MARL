# Historial completo del trabajo: rama `cierre-schedule-inicial`

Este documento registra **todo** el trabajo hecho entre el 17 y el 23 de
noviembre de 2026 sobre el simulador TDS-MARL:

- los pedidos, y el análisis previo a cada uno;
- cada decisión y su fundamento;
- cada cambio de código, con las líneas exactas en el Anexo A;
- cada hallazgo, con sus datos;
- las pruebas y sus resultados;
- los incidentes y los comandos útiles.

El objetivo es que cualquier integrante del equipo, el profesor guía o un
evaluador pueda entender qué se hizo, por qué y cómo comprobarlo, sin haber
participado en el trabajo.

El desarrollo se hizo con apoyo de un asistente de programación (Claude Code).
Todas las decisiones de diseño las tomó el equipo de tesis; aquí se indican como
"decisión".

Documentos complementarios:

| Documento | Contenido |
|---|---|
| [DOCUMENTACION_REGISTROS.md](DOCUMENTACION_REGISTROS.md) | Qué archivo deja cada corrida y qué significa cada campo |
| [COMO_CORRER.md](COMO_CORRER.md) | Cómo lanzar corridas y lotes, opciones, pruebas |
| [TRABAJO-PENDIENTE.md](TRABAJO-PENDIENTE.md) | Errores conocidos y limitaciones anteriores a este trabajo |

---

## Índice

1. Resumen ejecutivo
2. Punto de partida: el proyecto antes de este trabajo
3. Reglas de trabajo
4. Etapa 1: la corrida termina al generar el schedule inicial
5. Etapa 2: lotes de experimentos sin recompilar
6. Etapa 3: ventana oculta, verificación del código original y pruebas automáticas
7. Hallazgos
8. Datos de todas las corridas realizadas
9. Comandos: operación y análisis
10. Inventario de archivos
11. Cronología y commits
12. Pendientes, riesgos y recomendaciones
13. Glosario
- Anexo A: líneas exactas cambiadas en los archivos originales

---

## 1. Resumen ejecutivo

**Qué se logró:**

- **Cada corrida termina sola al generarse el schedule inicial,** que es lo
  único que estudia la tesis:
  - no simula el turno;
  - deja registros y estadísticas del plan en `corridas/<etiqueta>/`;
  - apaga JADE y libera el puerto 1099.
- **Los parámetros se entregan al lanzar,** sin recompilar: etiqueta,
  escenario, `DEADLINE_MS` y tope de la negociación.
- **Un script (`correr_lote.py`) y una interfaz (`interfaz_lotes.py`)**
  ejecutan lotes de corridas definidos en un JSON:
  - retoman si se cortan;
  - tienen seguro contra cuelgues;
  - muestran el progreso y estiman el tiempo restante.
- **La ventana de la mina no se muestra en los lotes.**
- **Una corrida en que ninguna negociación tiene ganador ya no queda colgada
  para siempre.**
- **49 pruebas automáticas** y una **verificación automática** de que el código
  original de la simulación y del scheduling no cambió.

**Qué NO cambió:** la lógica de los agentes, de la negociación ni del
scheduling. Los 39 archivos `.java` que la contienen son idénticos al commit
original `cc87b39` (ver 6.2).

**Hallazgo más importante para la tesis:** en el escenario pequeño, una sola
pala recibe entre el 64 % y el 89 % de todas las cargas del plan. Se ve en las
11 planificaciones revisadas, incluido el plan único de octubre, anterior a
este trabajo. Ver H1 en la sección 7.

---

## 2. Punto de partida: el proyecto antes de este trabajo

### 2.1 El sistema

TDS-MARL es un sistema multiagente en **JADE 4.6.0** (Java, compilado con
`--release 8`) que planifica el despacho camión–pala de una mina mediante
negociación **Contract Net** (CNP). Tiene tres tipos de agentes:

- **Pala (`ShovelAgent`):**
  - cada 2 s reales revisa si puede negociar
    (`ShovelAgent.java:115`, `TickerBehaviour(this, 2000)`);
  - ofrece una hora de carga a todos los camiones con un CFP que tiene una hora
    límite de respuesta (`DEADLINE_MS`, 5000 ms por defecto);
  - elige al ganador con un costo ponderado de cinco pesos;
  - si nadie propone, **avanza su oferta 60 s** y reintenta
    (`ShovelAgent.java:1035`);
  - cuando su oferta pasa de las 6 h de turno, declara su **turno lleno**.
- **Camión (`TruckAgent`):**
  - guarda los CFP y los revisa cada 1 s (`TruckAgent.java:133`);
  - responde cuando faltan **2000 ms o menos** para la hora límite
    (`TruckAgent.java:231`);
  - antes de proponer, consulta al punto de descarga.
- **Punto de descarga (`UnloadingPointAgent`):** reserva el horario de
  descarga.

La **negociación inicial** construye el plan del turno completo. Termina cuando
todas las palas declaran su turno lleno. El sistema también simulaba la
ejecución del turno con perturbaciones y reprogramación; esa parte quedó fuera
del alcance actual.

### 2.2 Estado del repositorio al comenzar

- **Ubicación:** `~/Documentos/TDS-MARL`, en la rama `preprocesador-registros`,
  sin cambios pendientes.
- **Último commit:** `cc87b39` ("Agrega preprocesador de registros y documento
  de flujo de episodios"). Este es el **commit original de referencia** de todo
  el documento.
- **Ya existían:**
  - `PlanBase`, que fotografía el plan inicial;
  - `GuardarPlanInicial`, que escribe `plan_inicial.csv` y
    `plan_inicial_verificacion.txt`;
  - `PlanInicialFijo`, que carga un plan único guardado;
  - `planes_iniciales/` con un plan único por escenario.
- **Ya documentado en `TRABAJO-PENDIENTE.md`:**
  - errores "de solapamiento" en `RB2_PUMA2_EST` (1.1);
  - camiones sin trabajo en el escenario mediano (1.2);
  - sobre-agendamiento de palas (1.3);
  - el plan varía entre corridas (3.1);
  - la negociación toma ~26 min y el 90 % es improductiva (3.2);
  - no había forma de automatizar lotes (3.3).

  Este trabajo resolvió el punto 3.3 y aportó datos nuevos sobre 1.1, 3.1 y 3.2.
- **Documentos de la etapa anterior, enfocada en reprogramación:**
  `preprocesar_registros.py`, `FLUJO_EPISODIOS_LLM.md` y `DUDAS_PROFESOR.md`.
  Quedaron **obsoletos** con el cambio de foco.

### 2.3 Cambio de foco que motivó el trabajo

La tesis pasó a estudiar **solo la negociación inicial y el schedule que
genera**. No interesan la ejecución del turno, los atrasos ni la
reprogramación. El componente LLM recomendará parámetros de negociación; por
ahora el único parámetro evaluado es `DEADLINE_MS`.

---

## 3. Reglas de trabajo

1. **Primero un plan, después la implementación.** Cada pedido grande tuvo una
   fase 1 (solo análisis y plan, sin modificar archivos) y una fase 2, que
   empezaba recién con la aprobación del equipo. Las decisiones de diseño las
   tomó el equipo.
2. **No cambiar la lógica de los agentes ni de la negociación o el
   scheduling.** El sistema debe generar el schedule igual que antes.
   - **Fundamento:** las corridas deben ser comparables con los resultados
     previos.
   - Esta regla se **reforzó explícitamente** durante la etapa 3 ("que lo que
     es la simulación del scheduling no cambie nada del código original"). Por
     eso se creó la verificación automática de la sección 6.2.
3. **No tocar `schedules.csv` ni la forma en que se escribe `messages.csv`.**
   `schedules.csv` lo lee `LectorCSVSchedules`. Los archivos nuevos se generan
   al final, leyendo los CSV y el estado en memoria.
4. **No borrar archivos ni clases existentes.**
5. **Si una definición no se puede calcular tal cual o es ambigua, se pregunta;
   no se inventa otra.**
6. **Commits.** El primer commit de la rama (`5d09ee1`) se hizo durante la
   etapa 1. Desde la etapa 2, los commits y el push los hace solo el equipo.

---

## 4. Etapa 1: la corrida termina al generar el schedule inicial

### 4.1 Pedido

Que cada corrida termine apenas se genera el schedule inicial y que:

- deje registros entendibles de cómo se generó y las estadísticas del plan;
- muestre un diálogo con un resumen y dos botones: "Abrir carpeta de
  resultados" y "Cerrar";
- apague JADE limpiamente y termine la JVM;
- si la negociación terminó por el tope de tiempo, genere los archivos igual
  pero marque la corrida como **NO VÁLIDA**.

### 4.2 Análisis previo (lectura del código)

- **Punto de enganche:** `VentanaSimulacion.evaluarArranqueAutomatico`, en el
  bloque `if (porPalas || porRespaldo)`. Antes de este trabajo, ahí se llamaba
  a `PlanBase.capturar(...)`, que a su vez llama a
  `GuardarPlanInicial.alCapturar` (`PlanBase.java:119`), y luego se despausaba
  la simulación.
- **Espera de las copias:** ya existía. Antes de capturar, el código espera
  hasta 30 s a que coincidan las cuatro copias de la agenda
  (`PlanInicialFijo.copiasAlDia()`). No hizo falta agregar otra espera.
  - Esa espera **solo** se hace cuando la negociación terminó con todas las
    palas llenas, no cuando terminó por el tope.
- **Apagado de JADE:** el contenedor principal era una variable local de
  `MainContainer.main`. Se decidió guardarlo en un campo estático para poder
  hacer `kill()` desde el cierre.
- **Quién lee `negotiations.csv`:**
  - solo `preprocesar_registros.py`, y lo hace **por posición, ignorando el
    encabezado**;
  - `CsvSchedulesPorCorrida` e `InicializadorSimulador` solo lo copian o
    borran.

  Por eso corregir el encabezado invertido no rompe nada.
- **Cómo registra mensajes cada agente:**
  - **Palas:** una fila `SEND` por envío, aunque vaya a varios receptores. Un
    CFP a 15 camiones es una sola fila, con 15 nombres en `receivers`.
  - **Camiones:** una fila `SEND` por envío.
  - **Palas y camiones:** una fila `RECEIVE` por cada mensaje recibido.
  - **Puntos de descarga:** reciben con `receive()` y responden con `send()`
    sin registrar `SEND` ni `RECEIVE`. Solo dejan filas descriptivas ("AGREE
    descarga (pendiente INFORM)", "INFORM recibido - slot registrado").
  - **Filas que no son mensajes:** "Iniciando negociacion", "evaluacion
    multicriterio", "cumplimiento deadline", etc.

### 4.3 Decisiones y fundamentos

| # | Tema | Decisión | Fundamento |
|---|---|---|---|
| 1 | Mensaje a varios destinatarios | Cuenta cada **entrega** (un CFP a 15 camiones = 15). Se informa además `envios` (1 por fila SEND) | La entrega mide el tráfico real; el envío, las acciones de los agentes |
| 2 | Cargas que terminan después de las 6 h | Se informan el **total** y lo de **dentro del turno**. El tiempo ocupado se recorta a [0, 6 h]. Se agrega `toneladas_descargadas_en_turno` | La pala oferta hasta las 6:00, pero el camión puede llegar después: el plan único del escenario pequeño tiene 2 de 87 cargas que empiezan después de las 6 h. Sin recorte, el tiempo ocioso podría salir negativo |
| 3 | `minutos_solapados` | Suma de las intersecciones de cada par de cargas | Coherente con contar "pares" que se solapan |
| 4 | `negociaciones_sin_propuestas` | Por **motivo** de cierre; se informa aparte "con 0 propuestas recibidas" | Una negociación puede recibir propuestas y descartarlas todas (por proponer una hora anterior a la ofrecida) |
| 5 | Ganador | La columna `ganador` queda tal cual (el camión **seleccionado**) y se agrega `acepto`. `negociaciones_con_ganador` = las que aceptaron | El seleccionado puede rechazar después (DISCONFIRM o REFUSE); solo las aceptadas agregan carga |
| 6 | `conversationId` repetidos | Una fila por cada fila del CSV, sin deduplicar; los repetidos se informan como advertencia | Una negociación puede registrarse dos veces (DISCONFIRM y luego un REFUSE tardío). Deduplicar escondería el fenómeno |
| 7 | Formato de fechas | `yyyy-MM-dd HH:mm:ss.SSS`, más `duracion_ms` | El mismo formato de `messages.csv` |
| 8 | `resumen_corridas.csv` | En `corridas/`, ignorado por git | Son resultados, no código |
| 9 | Fin por el tope de tiempo | Se generan los archivos, pero la corrida queda NO VÁLIDA | Su plan está incompleto y no es comparable |
| 10 | Interruptor | `ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL = true` | Permite volver al comportamiento anterior |
| 11 | Etiqueta repetida | Si `corridas/<etiqueta>/` existe, la corrida no arranca (aviso por consola y en un diálogo) | No sobrescribir resultados |
| 12 | Estado de git | Se registra `git_sucio` en `configuracion.json` | Saber si la corrida usó código con cambios sin commit |
| — | Duración | `duracion_negociacion_inicial_ms` = último fin − primer inicio. Aparte: `espera_copias_ms` y `duracion_hasta_captura_ms` | Separa la negociación pura de la espera técnica |
| — | Versión de Java | Se registra en `configuracion.json` | Reproducibilidad |
| — | Configuración de las corridas | `USAR_PLAN_INICIAL_FIJO = false` y `METODO_RESCHEDULING = NINGUNO` | Se estudia la negociación, así que hay que negociar y no reprogramar |

### 4.4 Implementación

**`VentanaSimulacion.java`**

- Con `TERMINAR_TRAS_PLAN_INICIAL`, al cumplirse el criterio de fin se llama a
  `PlanBase.capturar` como antes. Pero **no** se despausa: el reloj queda en 0,
  así que todas las filas siguen con `t_sim_ms = -1`.
- Luego se llama a `CierrePlanInicial.cerrar(motivo, tCaptura, tFinDetectado)`.
- Se agregó el campo `tRealFinDetectado`, solo para medir `espera_copias_ms`.

**`CierrePlanInicial.java` (nuevo)**

- En un hilo aparte:
  1. genera los archivos con `EstadisticasPlanInicial`;
  2. copia los registros crudos (ver 4.9).
- En el hilo de Swing:
  1. muestra el diálogo (`JOptionPane.showOptionDialog`). Si la corrida no es
     válida, el título y el texto dicen "CORRIDA NO VÁLIDA";
  2. "Abrir carpeta" usa `Desktop.open`, con `xdg-open`, `explorer` u `open`
     como respaldo según el sistema;
  3. apaga con `contenedorPrincipal.kill()`, luego
     `jade.core.Runtime.instance().shutDown()` y `System.exit(0)`. Un hilo de
     guardia fuerza la salida (`halt`) si el apagado tarda más de 10 s.
- `verificarEtiquetaLibre()` se llama al inicio de `main`: si la carpeta de la
  etiqueta existe, sale con código 1.

**`EstadisticasPlanInicial.java` (nuevo)**

Lee `corridas/<etiqueta>/plan_inicial.csv`, `<prefijo>negotiations.csv` y
`<prefijo>messages.csv`, y escribe:

| Archivo | Contenido |
|---|---|
| `configuracion.json` | Escenario, flota, `deadline_ms`, los cinco pesos, paso del barrido, interruptores, fecha, versión de Java, commit, `git_sucio`, y campos reservados para el LLM (`llm_modelo`, `llm_recomendacion`, `llm_latencia_ms`) |
| `negociacion_inicial.csv` | `pala, inicio, fin, duracion_ms, propuestas, rechazos, ganador, acepto, motivo_cierre, id_conversacion` |
| `resumen_plan.json` y `resumen_plan.txt` | Validez, producción, palas, negociación, mensajes y advertencias |
| `corridas/resumen_corridas.csv` | Agrega una fila acumulativa |

- **Fase inicial:** se consideran las filas con `t_sim_ms = -1` cuyo fin (o
  marca de hora) es anterior o igual al instante de captura.
- **Pesos de la pala:** son variables locales de
  `ShovelAgent.decideAndSendAcceptRejectMulticriterio`. **No se tocó la pala:**
  se copiaron como constantes (`W_ESPERA_PALA = 2.0`, `W_VIAJE_VACIO = 1.0`,
  `W_VIAJE_CARGADO = 1.0`, `W_ESPERA_DESCARGA = 1.5`, `W_CAPACIDAD = 1000.0`,
  `PASO_BARRIDO_MS = 60000`), con un `TODO` que obliga a sincronizarlos a mano.
- **JSON:** se escribe sin bibliotecas externas.

**`MainContainer.java`.** Campo estático `contenedorPrincipal` y la llamada a
`verificarEtiquetaLibre()`.

**`CsvNegotiationsLogger.java`.** Solo el encabezado: decía
`refusesRecibidas, proposesRecibidas`, pero el código escribe primero
`numProposes`. Era el único cambio permitido en un logger.

**`preprocesar_registros.py`.** Solo un comentario al inicio:

- el script quedó **obsoleto** (era para los episodios de reprogramación);
- el encabezado ya está corregido;
- ya no hay que reinterpretar columnas.

La lógica del script no se tocó.

**`DOCUMENTACION_REGISTROS.md` (nuevo).** Cubre:

- cada archivo y campo, con unidad, y si "mejor" es mayor o menor;
- la fórmula de cada estadística;
- las limitaciones;
- los archivos antiguos y su estado.

### 4.5 Criterio para contar mensajes: tres versiones

El criterio aprobado en el plan suponía que la columna `receivers` siempre
tenía los destinatarios. Al probarlo con datos reales apareció el defecto H2:
los `SEND` de los camiones traen `receivers` vacío. El criterio evolucionó así
(cifras de la corrida "antes r1"):

| Versión | Regla | Resultado |
|---|---|---|
| 1 (plan) | Entregas = receptores de cada SEND, más los RECEIVE cuyo emisor es un punto de descarga | **24.329** frente a 48.206 en el control (no calza: faltaban todas las respuestas de los camiones) |
| 2 | SEND con `receivers` vacío = 1 entrega | **48.619** frente a 48.556 (diferencia de 63) |
| 3 (final) | Vacío = 1 entrega **solo si lo envía un camión**. En una pala, vacío = 0. El emisor del AGREE de un punto de descarga se cuenta por su fila "AGREE descarga" | **48.556 = 48.556** (diferencia 0) |

La diferencia de 63 en la versión 2 venía de los REJECT_PROPOSAL que la pala
envía a una **lista vacía** cuando no hubo perdedores: no llegan a nadie (H11).

El **control cruzado** final cuenta lo mismo desde el lado del receptor: las
filas RECEIVE, más las filas del punto de descarga que prueban que recibió un
REQUEST ("AGREE descarga") o un INFORM ("INFORM recibido").

### 4.6 Cómo se hicieron las pruebas

- **Corridas "antes" y "después".** Para las "antes" (código original) se copió
  `src/` y se compiló aparte; así se podía seguir editando sin afectar esas
  corridas.
  - En las "antes", el sistema seguía simulando después de la captura. Por eso
    se copiaban los CSV al ver `[PlanBase] plan predictivo capturado` y se
    terminaba el proceso por su PID.
  - Se contaron solo las filas con `t_sim_ms = -1`.
- **Cifras medidas en cada corrida:**
  - duración de la negociación pura;
  - filas de `messages.csv` y de `negotiations.csv`;
  - filas del plan;
  - cargas y toneladas.
- **Prueba fuera de línea:** se probó `EstadisticasPlanInicial` con un arnés que
  leía los CSV de la corrida "antes r1". Así apareció el problema del conteo de
  mensajes sin esperar 25 min por corrida.
- **Diálogo:** se automatizó con `xdotool`. Ver los incidentes de 4.8.

### 4.7 Resultados de la etapa 1 (escenario pequeño)

| Corrida | Código | Negociación (s) | Filas `messages.csv` | Filas `negotiations.csv` | Tareas de camión | Cargas | Toneladas cargadas |
|---|---|---|---|---|---|---|---|
| `pequeno_antes_r1` | original | 1458,9 | 81.086 | 1.587 | 328 | 82 | 26.352 |
| `pequeno_antes_r2` | original | 1414,8 | 76.641 | 1.494 | 380 | 95 | 30.544 |
| `pequeno_despues_r1` | nuevo | 1463,2 | 81.794 | 1.599 | 348 | 87 | 27.998 |
| `pequeno_despues_r2` | nuevo | 1450,9 | 75.798 | 1.479 | 348 | 87 | 28.144 |

**Conclusión:** las cifras "después" quedan dentro de la variación de las
"antes", o a menos de 1 % de sus bordes. La negociación no es determinista
(H4).

Otras verificaciones:

- **Puerto 1099:** una corrida lanzada 4 s después de terminar la anterior
  levantó JADE en `jicp://…:1099` sin error. Tras cada cierre, el proceso
  termina con código 0 en 1 s y el puerto queda libre.
- **Botones del diálogo:**
  - "Abrir carpeta" abrió el explorador de archivos en `corridas/<etiqueta>`;
  - "Cerrar" no la abre y apaga igual.
- **Corrida NO VÁLIDA:** con el tope bajado a 60 s, el diálogo y el resumen
  dicen "CORRIDA NO VÁLIDA (`respaldo_estabilidad`)".
- **Etiqueta repetida:** la corrida no arranca (código 1) y no borra los CSV
  anteriores.
- **Revisión manual** contra `plan_inicial.csv` de `pequeno_despues_r1`:
  - Truck0 tiene 6 cargas (1.800 t);
  - en total, 87 cargas y 27.998 t; 86 cargas y 27.698 t dentro del turno;
    25.752 t descargadas en turno;
  - 13 camiones con trabajo;
  - PA05 tiene 14,25 min ocupados;
  - las agendas de las palas coinciden.

  Todo calza con `resumen_plan.json`.
- **Archivos de reprogramación:** con `NINGUNO` y el cierre al capturar,
  **no se crean** `cascadas.csv`, `liberaciones.csv`, `regeneraciones.csv`,
  `kpis_corridas.csv`, `estabilidad.csv` ni los demás. Solo se sigue creando
  `snapshots/corrida_<fecha>/manifest.csv`, vacío (H14).

### 4.8 Incidentes durante las pruebas (no son defectos del sistema)

- **El script de prueba colgó una JVM.** En `pequeno_despues_r2`, cerrar el
  diálogo con `xdotool windowclose` **destruye** la ventana X en lugar de pedir
  que se cierre, y el diálogo modal de Java quedó esperando para siempre. Se
  terminó el proceso por su PID. Los archivos de la corrida ya estaban
  completos.
  - **Lección:** para automatizar diálogos de Java, nunca usar
    `windowclose`.
- **Enter activa el botón por defecto** ("Abrir carpeta") aunque el foco esté
  en otro botón. Para pulsar "Cerrar" desde el teclado: Tab y luego espacio.
- **`pkill -f` no sirve para buscar procesos:** su patrón coincide con la propia
  línea de comandos de la terminal que lo ejecuta. Hay que buscar por PID o con
  `ps -eo pid,args | grep "[j]ava.*MainContainer"`.

### 4.9 Agregados posteriores de la etapa 1

- **Copia de los registros crudos.** Al cerrar, `messages.csv`,
  `negotiations.csv` y `schedules.csv` se copian a
  `corridas/<etiqueta>/registros_negociacion/`.
  - **Motivo:** la corrida siguiente los borra al iniciar.
  - **Por qué no se reutilizó `CsvSchedulesPorCorrida.copiarRegistrosNegociacion`:**
    es `private`. Se escribió una copia equivalente en `CierrePlanInicial`,
    para no modificar esa clase.
  - **Prueba:** dos corridas seguidas (`prueba_crudos_a` y `_b`). Ambas
    carpetas conservaron sus tres CSV, y las filas copiadas coinciden con
    `negociaciones_total` (71 y 72).
- **¿87 cargas y 348 tareas significa el mismo plan?** Se comparó
  `plan_inicial.csv` de `pequeno_despues_r1`, `pequeno_despues_r2` y el plan
  único `planes_iniciales/plan_inicial_datosShovelTrucksPequeno.csv`:
  - los tres tienen distinto hash;
  - comparten solo **40, 51 y 60 de 522 tareas idénticas**.

  **Coincide el conteo, no el plan** (H4).
- **`plan_inicial_verificacion.txt`** de ambas corridas: "348 operaciones, 87
  ciclos, 13 camiones con plan … limpio: cuatro copias iguales, sin
  superposiciones, capacidades respetadas". Ninguna reemplazó el plan único.
- **`PlanInicialFijo` no carga nada** con `USAR_PLAN_INICIAL_FIJO = false`:
  `cargarEn` sale de inmediato (`if (!usar()) return 0;`,
  `PlanInicialFijo.java:167`), y es el único camino de carga. En los logs no
  aparece ninguna línea de `PlanInicialFijo`.
- Se agregó la sección **"Limitaciones conocidas"** en
  `DOCUMENTACION_REGISTROS.md` (H2 y H4).

---

## 5. Etapa 2: lotes de experimentos sin recompilar

### 5.1 Pedido

Definir en un JSON una lista de configuraciones (escenario, `DEADLINE_MS`,
repeticiones) y lanzarlas con un solo comando, sin recompilar. Estudiar cómo
cambia el plan con valores altos y bajos es parte de la tesis, así que
**no se limitan los valores ni la duración**.

### 5.2 Análisis previo

- **Por qué había que recompilar.** `ETIQUETA_ESCENARIO` y
  `NOMBRE_ARCHIVO_FLOTA` eran `static final` con un valor literal. Java
  **copia** esas constantes dentro de cada clase que las usa al compilar.
  - **Solución:** inicializarlas con una llamada a método. Así dejan de ser
    constantes de compilación y se leen al arrancar.
  - `DEADLINE_MS` ya no era `final`.
- **Cómo funciona el plazo, y qué pasa con valores bajos** (análisis del
  código, luego contrastado con corridas en 5.7):
  - **Con `DEADLINE_MS` mayor que 2000 ms:** el camión responde entre
    D−2000 y D−1000 ms después del CFP. Esa espera le permite ver los CFP de
    otras palas y elegir el más conveniente (`hayUnCFPmasConveniente`).
  - **Con `DEADLINE_MS` de 2000 ms o menos:** la condición del camión se cumple
    de inmediato y responde en su próximo tick, entre 0 y 1 s, sin esperar
    otros CFP. Cambia cómo elige el camión, no solo la rapidez.
  - **Respuestas tardías:** la respuesta de un camión **no hereda la hora
    límite** del CFP, porque `ACLMessage.createReply()` no la copia (se revisó
    el bytecode de JADE). Por eso la pala no descarta las respuestas por
    tardías al recibirlas. Si llegan después del cierre, las ignora porque la
    negociación ya no existe.
- **¿Alcanzaba el tope existente para evitar corridas colgadas?** No, por dos
  razones:
  1. **Una corrida sin ningún slot nunca terminaba.**
     `evaluarArranqueAutomatico` salía en `if (agendas.isEmpty()) return;`
     antes de mirar el criterio de palas llenas y el tope, porque las agendas
     del simulador se crean recién con el primer slot. Con un `DEADLINE_MS` muy
     bajo, ninguna negociación tendría ganador y la corrida no terminaría
     nunca.
  2. **El tope de 90 min cortaba los deadlines altos.** Con unas 300 a 320
     negociaciones por pala, la duración es aproximadamente
     `negociaciones × (D − 1,5 s + espera del tick)`: unos 24 min con 5000 ms,
     unos 40 min con 8000 ms y más de 90 min desde unos 17 a 18 s. Esas
     corridas quedarían NO VÁLIDAS.

### 5.3 Decisiones y fundamentos

| Tema | Decisión | Fundamento |
|---|---|---|
| Parámetros configurables | Etiqueta, escenario, `DEADLINE_MS` y tope de la negociación. **Los pesos y el paso del barrido no se tocan** | Por ahora el único parámetro de negociación evaluado es `DEADLINE_MS` |
| Cómo llegan a Java | Propiedades del sistema `-Dtds.*`, leídas **una vez** al arrancar, antes de crear los agentes | Sin recompilar y sin que un valor cambie a mitad de corrida |
| Valor no entregado | Se usa el del código | Sin propiedades, el sistema se comporta exactamente igual que antes |
| Validación | Solo que el escenario exista y que `DEADLINE_MS` sea un entero positivo. **Sin mínimos ni máximos**. Si algo es inválido, la corrida no arranca | Los extremos son objeto de estudio |
| Tope de 90 min | **Opción (b):** `-Dtds.topeNegociacionMin` y la clave `tope_negociacion_min`; `null` = sin tope; si no se entrega, 90 min | Deja la decisión explícita en cada lote, y una corrida manual sigue igual que antes |
| Corrida sin slots | Se corrige en `VentanaSimulacion`: cierre con **plan vacío**, motivo `negociacion_completa` y el campo `plan_vacio` | Las palas sí completaron su turno; el plan vacío es un resultado válido del experimento |
| Valores por defecto | Los del commit `5d09ee1`: escenario grande original, 5000 ms, etiqueta `grande_F1_COMPLETE`. Se revirtieron los valores de prueba de la copia de trabajo | Que el código por defecto sea el de siempre |
| "grande" | `datosShovelTrucksGrande_original.owl`. `datosShovelTrucksGrande.owl` existe pero **no se usa** | Es el escenario del código y de `planes_iniciales/` |
| Estados del lote | OK, NO VÁLIDA, colgada, **fallida** (el proceso terminó sin resultados) y saltada | Distinguir una configuración inválida o un error de Java de un cuelgue |
| Registro del lote | `corridas/_lotes/<fecha_hora>/` | Fuera de `corridas/<etiqueta>/`: si esa carpeta existe antes de arrancar, la corrida se niega a correr |
| Tiempo máximo por corrida | 360 min por defecto. Es **solo un seguro** contra procesos colgados y no tiene relación con el tope de la negociación | No debe limitar el experimento |
| Ejemplo de JSON | `tope_negociacion_min: null` | Los deadlines altos no deben quedar NO VÁLIDOS |
| Fuente del progreso | La traza que la ventana ya imprimía cada 15 s (`[Negociacion inicial] t+…s palas x/y … slots n`), leída desde el log | No hubo que modificar Java |
| Interfaz | tkinter, en un archivo aparte; **no reemplaza** al script (solo edita el JSON y lo lanza) | El JSON sigue siendo la fuente de verdad |

### 5.4 Implementación en Java

**`ConfiguracionCorrida.java` (nuevo).** Lee estas propiedades:

| Propiedad | Valor | Por defecto |
|---|---|---|
| `-Dtds.etiqueta` | Texto sin `/`, `\` ni comas | `grande_F1_COMPLETE` |
| `-Dtds.escenario` | `pequeno`, `mediano`, `grande` o un archivo `.owl` | `datosShovelTrucksGrande_original.owl` |
| `-Dtds.deadlineMs` | Entero > 0 | 5000 |
| `-Dtds.topeNegociacionMin` | Minutos > 0, o `null` (sin tope) | 90 |
| `-Dtds.sinDialogo=true` | Cierre sin diálogos (el resumen queda en la consola) | Con diálogos |
| `-Dtds.sinVentana=true` | No muestra la ventana de la mina (agregado en la etapa 3) | Visible |

- **Validación.** `validar()` es lo primero que corre en `MainContainer.main`.
  Reúne todos los errores y, si hay alguno, termina con **código 2**, sin borrar
  nada ni crear agentes.
- **Registro del origen.** Anota si cada valor vino de una propiedad o del
  código; queda en `configuracion.json` como `origen_parametros`.

**Cambios en los parámetros (solo la inicialización; mismos valores por
defecto):**

| Archivo | Antes | Ahora |
|---|---|---|
| `ParametrosConfiguracion` | `DEADLINE_MS=5000L` | `DEADLINE_MS=ConfiguracionCorrida.deadlineMs(5000L)` |
| `ParametrosConfiguracion` | `NOMBRE_ARCHIVO_FLOTA = "datosShovelTrucksGrande_original.owl"` | `NOMBRE_ARCHIVO_FLOTA = ConfiguracionCorrida.escenario("datosShovelTrucksGrande_original.owl")` |
| `ParametrosSimulador` | `ETIQUETA_ESCENARIO = "grande_F1_COMPLETE"` | `ETIQUETA_ESCENARIO = ConfiguracionCorrida.etiqueta("grande_F1_COMPLETE")` |
| `ParametrosSimulador` | `TOPE_NEGOCIACION_INICIAL_MS = 5_400_000L` | `TOPE_NEGOCIACION_INICIAL_MS = ConfiguracionCorrida.topeNegociacionMs(5_400_000L)`; "sin tope" vale `Long.MAX_VALUE` |

**Cierre sin slots (`VentanaSimulacion.evaluarCierreSinSlots`).**

- Solo actúa si `TERMINAR_TRAS_PLAN_INICIAL` está activo, no se usa un plan
  fijo y todavía no hay ningún slot. Por eso **no cambia nada cuando hay plan**.
- Cierra con plan vacío en dos casos:
  - todas las palas llenaron su turno y las copias coinciden →
    `negociacion_completa`;
  - se cumplió el tope, contado desde que la ventana empezó a esperar →
    `respaldo_estabilidad`.
- Imprime una traza "sin slots todavía" cada 15 s.

**`GuardarPlanInicial`: un plan vacío no se guarda como plan único.** Sin esta
salvaguarda, en un escenario sin plan único, una corrida con plan vacío lo
habría guardado como plan del escenario.

**Modo sin diálogo (`CierrePlanInicial`):** no muestra el resumen ni el aviso
de etiqueta repetida; genera todo y apaga igual.

**`EstadisticasPlanInicial` (agregados):**

- `configuracion.json` incluye `tope_negociacion_inicial_ms`,
  `tope_negociacion_min`, `origen_parametros` y `sin_dialogo`;
- `resumen_plan.*` incluye `plan_vacio`;
- `resumen_corridas.csv` suma las columnas `tope_negociacion_min` y
  `plan_vacio`. Si el archivo tenía un encabezado anterior, **se reescribe con
  el encabezado nuevo, conservando todas las filas** (las columnas nuevas quedan
  vacías en las filas antiguas).

**Incidente: fin de línea.** `ParametrosSimulador.java` usa CRLF (fin de línea
de Windows). Una edición lo convirtió a LF y git marcó las 451 líneas como
cambiadas. Se restauró el CRLF y el diff volvió a mostrar solo las líneas
reales. Desde entonces, las ediciones conservan el formato de cada archivo.

### 5.5 `correr_lote.py`

- **Validación.** Revisa el JSON completo antes de correr nada y lista **todos**
  los errores juntos:
  - nombres repetidos o con caracteres inválidos;
  - escenarios inexistentes;
  - números inválidos;
  - claves mal escritas.
- **Compilación.** Compila una vez (`javac --release 8` en `bin/`), salvo con
  `--sin-compilar`.
- **Ejecución.** Corre **una corrida a la vez**, con `-Dtds.sinDialogo=true` y
  `-Dtds.sinVentana=true`. Con `nucleos_cpu`, usa `taskset` (solo Linux).
- **Etiquetas:** `<nombre>_r01`, `_r02`, … Con 100 o más repeticiones, tres
  dígitos.
- **Saltar:** si `corridas/<etiqueta>/` existe, la corrida se salta. Así se
  **retoma** un lote relanzando el mismo comando.
- **Tiempo máximo:** si una corrida lo supera, se termina el proceso (SIGTERM, y
  a los 15 s SIGKILL) y se anota como "colgada".
- **Ctrl+C:** termina la corrida en curso, la anota como "interrumpida" y sale
  con código 130.
- **Archivos en `corridas/_lotes/<fecha_hora>/`:**

| Archivo | Contenido |
|---|---|
| `lote.json` | Copia del JSON y, por corrida, comando, inicio, fin, duración, código de salida, estado y motivo. Se actualiza después de cada corrida |
| `lote.log` | Todo lo que imprime el script |
| `<etiqueta>.log` | La salida de consola de cada corrida |
| `estado.json` | Estado en vivo: corrida actual, palas llenas, slots, tiempo transcurrido, terminadas, estimación |

- **Progreso en la terminal:** una línea que se actualiza, por ejemplo
  `[5/30] base_r05  palas 2/5  slots 120  12 min 03 s | lote 4/30 terminadas, resta 3 h 10 min`.
- **Estimación del tiempo restante:**
  - se calcula con la **duración promedio de las corridas ya terminadas
    (OK o NO VÁLIDA) de la misma configuración**;
  - se suma lo que falta de la corrida en curso más lo de las pendientes;
  - si alguna configuración pendiente no tiene ninguna corrida terminada,
    muestra "sin estimación";
  - después de pedir que se detenga, las pendientes ya no cuentan.
- **Detener tras la corrida en curso:** basta crear el archivo `DETENER` en la
  carpeta del lote. Las restantes quedan "no ejecutadas".
- **`--simular`:** muestra qué correría, qué se saltaría y el comando exacto.
  No compila, no ejecuta y no crea carpetas.
- **Extensible:** para agregar un parámetro nuevo basta una línea en el
  diccionario `PARAMETROS`.

### 5.6 `interfaz_lotes.py`

- **Instalación:** requiere tkinter. En Ubuntu se instaló con
  `sudo apt-get install -y python3-tk`; el equipo lo autorizó. En Windows viene
  con Python.
- **Opciones generales:**
  - tiempo máximo, con la casilla "sin límite";
  - tope de la negociación: por defecto del código, sin tope o un número de
    minutos;
  - núcleos de CPU;
  - mostrar la ventana de la mina (agregado en la etapa 3).
- **Tabla de configuraciones:**
  - doble clic para editar una celda, con lista desplegable para el escenario;
  - botones Agregar, Duplicar, Quitar, Subir y Bajar;
  - las columnas se definen en la lista `COLUMNAS`, para sumar parámetros
    fácilmente.
- **Validación:** usa **las mismas reglas que el script**
  (`correr_lote.validar_datos`) y muestra todos los errores juntos.
- **Botones:** Cargar JSON, Guardar, Guardar como, Simular (muestra la salida
  de `--simular`) y Lanzar.
- **Lanzar:**
  - guarda el JSON y pide confirmación;
  - lanza el script en una sesión aparte, para que pueda seguir aunque se
    cierre la ventana;
  - muestra la barra del lote con la estimación, la barra de la corrida actual
    (palas llenas / palas, slots, tiempo transcurrido), el estado de cada
    corrida y el registro en vivo.
- **Detener:** crea `DETENER`; la corrida en curso no se interrumpe.
- **Cerrar con un lote corriendo:** pregunta antes de cerrar:
  - **Sí:** detiene tras la corrida en curso y cierra;
  - **No:** cierra y deja el lote corriendo en segundo plano;
  - **Cancelar:** vuelve a la ventana.
- **Aprobación:** la interfaz se implementó según el diseño propuesto; el equipo
  la aprobó sin revisar el plan detallado.

### 5.7 Pruebas y resultados de la etapa 2

- **Compilación:** compila con `--release 8`.
- **Valores inválidos de Java** (código 2, sin borrar nada):
  - `deadlineMs` igual a `abc`, `0`, `-5` o `1.5`;
  - un escenario inexistente o desconocido;
  - un tope igual a 0;
  - una etiqueta con `/`.
- **`--simular`:** no crea `bin/` ni `corridas/_lotes/`.
- **Validación del JSON:** un archivo con 8 errores los informó todos y no
  corrió nada (código 2).
- **Lote corto:** 3 corridas con tope de 1 min. Corrieron **seguidas** (cada
  una empezó en el segundo en que terminó la anterior). Al relanzar, las 3 se
  saltaron.
- **Red de seguridad:** con un tiempo máximo de 0,5 min y sin tope, la corrida
  quedó "colgada" a los 30 s, sin procesos Java vivos y con el puerto 1099
  libre. Como no dejó carpeta, `--simular` muestra que se repetiría al retomar.
- **Corridas completas:**

| Corrida | `DEADLINE_MS` | Resultado | Cargas | Toneladas descargadas en 6 h | Negociación | Negociaciones (con ganador) | Mensajes (entregas) |
|---|---|---|---|---|---|---|---|
| `prueba_defecto_r01` | 5000 (código) | OK | 88 | 25.752 | 1455 s | 1485 (88) | 45.602 |
| `prueba_defecto_r02` | 5000 (código) | OK | 88 | 25.752 | 1485 s | 1527 (88) | 46.851 |
| `prueba_deadline1500_r01` | 1500 | OK | 92 | 27.025 | 736 s | 1524 (92) | 46.779 |
| `prueba_deadline500_r01` | 500 | OK | 80 | 23.052 | 705 s | 1547 (80) | 50.327 |
| `prueba_deadline1_r01` | 1 | OK, **plan vacío** | 0 | 0 | 723 s | 1810 (0) | 101.360 |

- **El valor se usó de verdad:** `configuracion.json` registra 1500 y 500 con
  origen `propiedad`. En la corrida de 500 ms, **las 1547** filas "Iniciando
  negociacion" de `messages.csv` dicen `(500 ms)`.
- **Corrida con 1 ms:**
  - todas las negociaciones terminaron con "deadline cumplido. No hubieron
    propuestas";
  - la corrida cerró sola con el aviso "termino SIN NINGUN SLOT (5/5 palas con
    el turno lleno)";
  - `plan_inicial_verificacion.txt` dice "No se guarda como plan unico: el plan
    esta vacio";
  - en `resumen_corridas.csv`, `plan_vacio` vale `true`.
- **`resumen_corridas.csv` con encabezado antiguo:** se actualizó al
  encabezado nuevo conservando las 14 filas.
- **Interfaz, lote corto lanzado desde ella:**
  - con un lote corriendo, preguntó antes de cerrar;
  - Detener durante la corrida 2 dejó la 3 "no ejecutada";
  - la estimación pasó de "sin estimación" a 2 min 16 s y fue bajando;
  - al final no quedaron procesos Java vivos y el puerto 1099 quedó libre.
- **Línea de progreso en una terminal real:** se probó con `script`, que simula
  una terminal.

### 5.8 Incidente: el PC se suspendió a mitad de un lote

El 17 de noviembre el equipo necesitaba suspender el PC durante un lote de
prueba. Para no falsear los tiempos (los plazos se miden con el reloj real), se
hizo así:

1. se esperó a que terminara la corrida en curso (`prueba_defecto_r01`, OK);
2. se interrumpió el lote con Ctrl+C. La corrida siguiente quedó "interrumpida"
   y, como no alcanzó a crear su carpeta, se repitió sola al retomar;
3. se copiaron los JSON de prueba a `corridas/_pruebas_lote/`, porque la
   carpeta temporal se borra al reiniciar.

El 23 de noviembre se **retomó** con el mismo comando: la corrida ya hecha se
saltó y las demás se ejecutaron.

---

## 6. Etapa 3: ventana oculta, verificación del código original y pruebas automáticas

### 6.1 Ocultar la ventana de la mina

**Pedido.** Que no se vea la interfaz de la simulación (la mina).

**Análisis.** La ventana no puede eliminarse, porque su reloj (un `Timer` de
Swing cada 50 ms) es el que ejecuta `evaluarArranqueAutomatico` y detecta el
fin de la negociación. Se revisó si mostrarla u ocultarla podía cambiar algo:

- el dibujado del mapa (`paintComponent`) solo ajusta la cámara
  (`encuadreAjustado`) y no toca ninguna lógica;
- el `Timer` funciona igual con la ventana oculta;
- `CapturadorSnapshots` no captura nada si la ventana no está visible
  (`!ventana.isShowing()`).

**Decisión e implementación.** La ventana se crea pero no se hace visible con
`-Dtds.sinVentana=true` (`InicializadorSimulador`, en lugar de
`v.setVisible(true)`):

- los lotes la ocultan por defecto; para verla se usa
  `"mostrar_ventana": true` o la casilla de la interfaz;
- una corrida a mano sigue mostrándola, como siempre;
- queda registrado como `sin_ventana` en `configuracion.json`.

**Pruebas:**

- **Corrida vigilada:** se buscó una ventana "Dashboard MAS" cada segundo
  durante 65 s y hubo **0** apariciones.
- **Corridas completas sin ventana** (`oculta_defecto_r01` y `_r02`): 89 y 83
  cargas, 1455 y 1489 s de negociación, dentro de la variación de las corridas
  con ventana.
- **Escenarios mediano y grande** (tope de 3 min, NO VÁLIDAS a propósito):
  funcionan, con 10 palas, 50 camiones y 6 destinos, y con 15 palas, 105
  camiones y 6 destinos.

### 6.2 Verificación automática del código original

**Motivo.** Durante esta etapa se reforzó la regla de no cambiar nada del
código original de la simulación del scheduling, y se pidió comprobarlo de
forma permanente.

**`pruebas/verificar_codigo_original.py`:**

- compara cada `.java` que existía en el commit `cc87b39` con su versión actual;
- falla (código 1) si cambió un archivo protegido, si se borró un archivo
  original o si cambió un valor por defecto. Los valores revisados son
  `DEADLINE_MS` 5000, escenario grande original, turno de 6 h, etiqueta, tope de
  90 min, `ARRANQUE_AUTOMATICO`, `VARIACION_VELOCIDAD_CAMION = 0.0` y
  `SEMILLA_ALEATORIA = 42L`;
- lista los 7 archivos originales autorizados, con sus líneas cambiadas y el
  motivo.

**Resultado actual.** De 46 archivos originales, **39 protegidos están
intactos** y 7 son modificaciones autorizadas. Los intactos son:

- **Agentes:** `ShovelAgent`, `TruckAgent`, `UnloadingPointAgent`,
  `ReschedulingAgent`.
- **Agenda y mensajes:** `Schedule`, `Slot`, `DocumentoOfertaPropuesta`,
  `DocumentoLiberacion`, `DocumentoReoferta`, `DocumentoRescheduling`,
  `ReservasDescarga`.
- **Registros:** `CsvLogger`, `CsvSchedules`, `LectorCSVSchedules`,
  `RegistroSimulacion`, `RegistroAgendasAgentes`.
- **Plan:** `PlanBase`, `PlanInicialFijo`.
- **Ejecución y reprogramación:** `CalculadorScheduleReal`,
  `CascadingRightShift`, `PartialRepair`, `EpisodioRescheduling`,
  `GestorEventos`, `Utilidades`, entre otros.

**Se comprobó que detecta cambios.** En una copia aparte se agregó un espacio a
`ShovelAgent.java` y se cambió el deadline por defecto a 4000; la verificación
falló y señaló ambos cambios.

### 6.3 Pruebas automáticas (`pruebas/`)

Se corren con `python3 pruebas/correr_pruebas.py`, en unos 45 s y sin levantar
JADE. Primero ejecuta la verificación del código original; si falla, no sigue.

| Suite | Pruebas | Cómo | Qué cubre |
|---|---|---|---|
| `test_estadisticas.py` | 16 | Arma CSV inventados de resultado calculado a mano y ejecuta `pruebas/java/ArnesEstadisticas.java` | Unión de intervalos, cargas anidadas (3 solapes, 10 min), recorte al turno, las tres cifras de toneladas, palas sin cargas, promedios, filtros de la fase inicial (`t_sim_ms`, posteriores a la captura), líneas rotas, `conversationId` repetidos, conteo de mensajes y control cruzado (incluido un caso que no calza), plan vacío, actualización del encabezado de `resumen_corridas.csv`. Además, que Java rechace 12 tipos de parámetros inválidos y la etiqueta repetida **sin borrar nada**, y que acepte valores extremos válidos |
| `test_correr_lote.py` | 21 | Funciones del script y lotes completos con un **`java` falso** que imita cada desenlace | Validación del JSON (13 tipos de error), valores por defecto, `null`, nombres cortos, deadlines de 1 a 10⁹, 100 repeticiones, comandos, estimación de tiempo. Lotes: OK, NO VÁLIDA, plan vacío, fallida, colgada, saltada, retomar, `--simular`, JSON inválido, Detener y Ctrl+C (sin procesos vivos al final) |
| `test_interfaz.py` | 12 | Ventana real de tkinter, con el `java` falso | Ida y vuelta del JSON, opciones generales, agregar, duplicar, quitar, mover, edición con doble clic, mensajes de validación, no guardar con errores, cargar un JSON roto, simular, lanzar con progreso y Detener, y las tres respuestas al cerrar con un lote corriendo |

**Defecto real encontrado por las pruebas.** Un REJECT sin receptores aparecía
como `"REJECT-PROPOSAL": 0` en `mensajes_por_tipo`. El total estaba bien, pero
el 0 confundía. Se corrigió en `EstadisticasPlanInicial`, que es un archivo
nuevo.

**Errores de las propias pruebas, corregidos:**

- un test suponía que no existía `_lotes/`, pero otros tests de la misma carpeta
  ya habían lanzado lotes;
- un test de validación dejaba una fila inválida sin corregir;
- un evento de teclado simulado no llegaba a un campo sin foco.

**Último resultado: las 49 pruebas pasan y la verificación da OK.**

---

## 7. Hallazgos

Cada hallazgo indica qué se observó, la evidencia, cómo interpretarlo y su
estado. Los marcados **(nuevo)** salieron de este trabajo; los demás ya estaban
documentados y aquí se aportan datos.

### H1 (nuevo). Casi toda la flota se concentra en una sola pala

**Observación.** En el escenario pequeño (5 palas, 15 camiones), una pala
recibe la gran mayoría de las cargas del plan y las demás casi nada. Cuál pala
domina cambia entre corridas, pero siempre es PA03 o PA04.

| Plan | PA01 | PA02 | PA03 | PA04 | PA05 | Total | Pala dominante |
|---|---|---|---|---|---|---|---|
| Plan único (oct-2026, **anterior a este trabajo**) | 2 | 2 | **75** | 4 | 4 | 87 | 86 % |
| `pequeno_antes_r1` (código original) | 2 | 2 | 2 | **73** | 3 | 82 | 89 % |
| `pequeno_antes_r2` (código original) | 2 | 13 | **70** | 1 | 9 | 95 | 74 % |
| `pequeno_despues_r1` | 2 | 2 | 1 | **68** | 14 | 87 | 78 % |
| `pequeno_despues_r2` | 3 | 2 | **76** | 3 | 3 | 87 | 87 % |
| `prueba_defecto_r01` | 2 | 3 | **77** | 1 | 5 | 88 | 88 % |
| `prueba_defecto_r02` | 3 | 3 | **77** | 0 | 5 | 88 | 88 % |
| `oculta_defecto_r01` | 4 | 4 | **77** | 1 | 3 | 89 | 87 % |
| `oculta_defecto_r02` | 1 | 4 | 2 | **74** | 2 | 83 | 89 % |
| `prueba_deadline1500_r01` | 16 | 7 | **67** | 2 | 0 | 92 | 73 % |
| `prueba_deadline500_r01` | 6 | 5 | **51** | 16 | 2 | 80 | 64 % |

**Consecuencia en el uso de las palas.** Por ejemplo, en
`pequeno_despues_r1`:

| Pala | PA01 | PA02 | PA03 | PA04 | PA05 |
|---|---|---|---|---|---|
| Uso del turno | 0,5 % | 0,6 % | 1,3 % | 47,3 % | 4,0 % |

El uso promedio es de solo **11 a 17 %** en todas las corridas con 5000 ms.

**Datos del escenario** (`datosShovelTrucksPequeno.owl`), como contexto:

| Pala | Ubicación | `transportationCapacity` | `loadRate` | Destino |
|---|---|---|---|---|
| PA01 | `#RF10A_4270_1012_1` | 74 | 12.778 | `#RB2_PUMA2_EST` |
| PA02 | `#RF10B_4255_1001_1` | 74 | 15.444 | `#CS03` |
| PA03 | `#UB1_4390_EST` | 36 | 27.002 | `#UB1_TRANQUEBERM_EST` |
| PA04 | `#UB1_4390_EST` | 42 | 20.597 | `#UB1_TRANQUEESPA_EST` |
| PA05 | `#RF10A_4270_1012_1` | 79 | 19.006 | `#RB2_PUMA2_EST` |

Las dos palas que dominan **comparten ubicación** (`#UB1_4390_EST`) y son las de
**menor capacidad** declarada. Además, PA01 y PA05 descargan en
`#RB2_PUMA2_EST`, donde aparecen los "ERROR de solapamiento" (H12).

**Hipótesis (ninguna verificada):**

1. Cada camión elige el CFP más conveniente para él
   (`hayUnCFPmasConveniente`). Si una pala queda siempre como la mejor opción
   (por distancia, por momento de oferta o por destino), atrae a todos.
2. La pala que gana el primer camión sigue ofreciendo horas tempranas
   (`getHoraFinUltimoSlot`), mientras las otras avanzan de a 60 s sin
   postulantes. Eso podría generar un efecto de "el que gana sigue ganando".
3. Los rechazos en `RB2_PUMA2_EST` (H12) podrían penalizar a PA01 y PA05.

**Por qué importa.** La tesis mide cómo cambia el plan con `DEADLINE_MS`. Si
este comportamiento domina los resultados, las diferencias entre deadlines
podrían deberse más a él que al parámetro. Y si se decide corregir el modelo
(por ejemplo, los pesos de selección), **todas las corridas hechas antes
quedarían inservibles**.

**Estado.** Sin investigar la causa. **Recomendación:** consultarlo con el
profesor **antes** de lanzar el lote grande. Pregunta sugerida:

> "En la negociación inicial casi toda la flota se concentra en una sola pala
> (64–89 % de las cargas) y las otras quedan casi sin uso. ¿Es un
> comportamiento aceptable del modelo para la tesis, o se debe revisar antes de
> experimentar?"

La causa puede investigarse **solo leyendo los registros**, sin tocar el código:
qué propone cada camión, a qué pala responde y por qué rechaza.

**Cómo reproducir la tabla:** comando B de la sección 9.2.

### H2 (nuevo). Los envíos de los camiones no registran sus destinatarios

- **Qué pasa.** `TruckAgent.getReceiverString` (`TruckAgent.java:375`) hace
  `receiverList.concat(r.getLocalName()+"/");` sin guardar el resultado. En Java
  los `String` no se modifican, así que el método devuelve siempre una cadena
  vacía y la columna `receivers` de los `SEND` de los camiones queda vacía.
- **Impacto:** desde `messages.csv` no se puede saber a quién envió cada
  camión. El conteo de mensajes se compensa (4.5) y el control cruzado lo
  confirma con diferencia 0.
- **Estado:** **no se corrige**, porque está en el código del agente y
  cambiaría el formato de `messages.csv`. Documentado en
  `DOCUMENTACION_REGISTROS.md`, sección 8.

### H3. Dos camiones nunca reciben trabajo en el escenario pequeño

- **Qué pasa.** En todas las corridas, 13 de 15 camiones tienen trabajo.
  Truck2 y Truck7 parten en `#RF11` y `#BOT`, que son vértices sin conexión con
  el grafo de caminos ("Ruta inexistente" en sus REFUSE).
- **Antecedentes:** ya estaba anotado en `FLUJO_EPISODIOS_LLM.md`. En el
  escenario mediano, `TRABAJO-PENDIENTE.md` 1.2 registra unos 10 de 50 camiones
  sin trabajo.
- **Impacto:** `pct_flota_con_trabajo` = 86,67 % como máximo en el escenario
  pequeño.
- **Estado:** decidir si se corrige el escenario o se declara como limitación.

### H4. El plan no es determinista

- **Variación de las cifras.** Con exactamente la misma configuración, el plan
  varió entre **82 y 95 cargas** (328 a 380 tareas de camión). La negociación
  duró entre 1415 y 1489 s.
- **Coincidir en el conteo no significa el mismo plan.** Dos corridas con 87
  cargas y el plan único comparten solo **40 a 60 de 522 tareas** idénticas. Una
  corrida con 89 cargas comparte solo 22 de sus 534 tareas con el plan único.
- **Causa:** ya documentada en `TRABAJO-PENDIENTE.md` 3.1. JADE ejecuta los
  agentes en hilos concurrentes, y el orden de llegada de las respuestas cambia
  en cada corrida.
- **Consecuencia:** hacen falta **varias repeticiones por configuración**
  (10 o más), reportando promedio y dispersión.

### H5 (nuevo). Una corrida sin ningún slot quedaba abierta para siempre

- **Causa:** `if (agendas.isEmpty()) return;` en
  `evaluarArranqueAutomatico`, antes de evaluar el fin de la negociación y el
  tope.
- **Estado:** **corregido** con `evaluarCierreSinSlots` (5.4) y comprobado con
  `DEADLINE_MS = 1`.

### H6 (nuevo). El tope de 90 min invalidaba los deadlines altos

- **Qué pasa.** Con deadlines de unos 17 s o más, la negociación superaría los
  90 min y la corrida quedaría NO VÁLIDA.
- **Estado:** **resuelto**; el tope es configurable y puede quitarse (5.3).

### H7 (nuevo). Un `DEADLINE_MS` bajo no vacía el plan

**Lo que se esperaba (análisis del código).** Con 1000 ms o menos, que casi
ninguna respuesta llegara a tiempo.

**Lo que se midió:**

| `DEADLINE_MS` | Cargas | Duración de la negociación |
|---|---|---|
| 500 | 80 | 705 s |
| 1500 | 92 | 736 s |
| 5000 | 83–89 | 1455–1489 s |
| 1 | 0 | 723 s |

**Lectura:**

- **Bajo 2000 ms, la duración cae a la mitad.** El límite pasa a ser el tick de
  2 s de cada pala (unos 300 a 360 intentos por pala), no el plazo.
- **Por qué con 500 ms todavía hay 80 cargas:** la pala reintenta muchas veces y
  algunas respuestas llegan a tiempo.
- **Por qué con 1 ms no hay ninguna:** no llega ninguna respuesta a tiempo.

**Advertencia:** es **una corrida por valor**. Sirve como orientación, no como
resultado.

### H8 (nuevo). Hay cargas que terminan después del turno

- **Qué pasa.** El plan único del escenario pequeño tiene 2 de 87 cargas que
  empiezan después de las 6 h. La pala oferta hasta las 6:00, pero el camión
  puede llegar más tarde.
- **Consecuencia:** existen tres cifras de toneladas distintas: todas las
  cargas, las cargas dentro del turno y las descargas dentro del turno. Las tres
  se informan; ver `DOCUMENTACION_REGISTROS.md`.

### H9. El encabezado de `negotiations.csv` estaba invertido

- **Antecedentes:** ya conocido (hallazgo 4 de `FLUJO_EPISODIOS_LLM.md`).
- **Estado:** **corregido** (es el único cambio en un logger).

### H10. Una negociación podría registrarse dos veces

- **Qué pasa.** Una negociación puede quedar dos veces en `negotiations.csv`:
  un DISCONFIRM seguido de un REFUSE tardío del mismo camión, detectado al leer
  `ShovelAgent`.
- **En la práctica:** en **ninguna** de las 11 corridas analizadas hubo
  `conversationId` repetidos. Igual se cuentan y se avisan.

### H11 (nuevo). La pala envía REJECT a listas vacías

- **Qué pasa.** Cuando no hubo perdedores, la pala envía igual un
  REJECT_PROPOSAL, pero sin receptores: no llega a nadie.
- **Frecuencia:** entre 63 y 73 por corrida en el escenario pequeño.
- **Tratamiento:** se cuentan como 0 entregas.

### H12. "ERROR de solapamiento" en los puntos de descarga

- **Antecedentes:** ya estaba anotado en `TRABAJO-PENDIENTE.md` 1.1.
- **Datos nuevos:**
  - en el escenario pequeño, 0 a 3 por corrida, todos en `RB2_PUMA2_EST`;
  - en el mediano (3 min de negociación), 20;
  - en el grande (3 min), 104, de los cuales 89 en `RB2_PUMA2_EST`.
- **Origen:** los imprime el `UnloadingPointAgent` original. No los produce
  este trabajo; también aparecen en las corridas "antes".
- **Estado:** sin investigar; puede relacionarse con H1.

### H13. La comparación de `conversationId` con `==`

- **Dónde:** `ShovelAgent.java:525` y `:552`.
- **Estado:** ya conocido. No falla mientras todos los agentes corran en la
  misma JVM: los contadores de propuestas coinciden con `messages.csv`. No se
  corrige (código del agente).

### H14. `snapshots/` se sigue creando

- **Qué pasa.** Cada corrida crea `snapshots/corrida_<fecha>/manifest.csv`,
  pero vacío, porque el turno no se ejecuta. Los archivos de reprogramación
  **no** se crean.

### H15. Más del 90 % de las negociaciones no produce nada

- **Cifras:** en todas las corridas del escenario pequeño, entre el **92,6 % y
  el 93,7 %** de las negociaciones terminan sin propuestas. Por ejemplo, 1381 de
  1485.
- **Antecedentes:** confirma con datos `TRABAJO-PENDIENTE.md` 3.2.
- **Causa:** la pala avanza su oferta 60 s y reintenta. Es el principal costo
  de tiempo de cada corrida.

### H16 (nuevo). Solapes de carga en las palas: ninguno en el escenario pequeño

- **Qué pasa.** `TRABAJO-PENDIENTE.md` 1.3 dice que la pala no verifica solapes,
  pero en **ninguna** de las corridas del escenario pequeño hubo solapes
  (`solapes_total = 0`).
- **Cobertura de la métrica:** está implementada y probada con datos
  sintéticos, por si aparecen en otros escenarios.

### H17 (nuevo). En una corrida NO VÁLIDA las copias pueden no coincidir

- **Qué pasa.** Cuando la negociación termina por el tope, no se espera a que
  coincidan las copias de la agenda. En `prueba_respaldo_60s_b`,
  `plan_inicial_verificacion.txt` informa que al simulador le faltaba el último
  ciclo de Truck1.
- **Control cruzado de mensajes:** también puede no calzar. En
  `oculta_mediano_r01` la diferencia fue de 16 mensajes que estaban en camino
  al cortar.
- **Consecuencia:** nada que corregir. Es una razón más para no usar las
  corridas NO VÁLIDAS.

### H18 (nuevo). Ocultar la ventana no cambia los resultados

- **Datos:** sin ventana, 89 y 83 cargas con 1455 y 1489 s; con ventana, 88 y 88
  cargas con 1455 y 1485 s.
- **Interpretación:** dentro de la misma variación.

---

## 8. Datos de todas las corridas realizadas

Las carpetas de resultados están en `corridas/` (ignorada por git).

| Corrida | Escenario | `DEADLINE_MS` | Tope | Estado | Cargas | Negociación | Propósito |
|---|---|---|---|---|---|---|---|
| `pequeno_antes_r1`, `_r2` | pequeño | 5000 | 90 | completas (código original) | 82, 95 | 1459, 1415 s | Línea base de la etapa 1 |
| `pequeno_despues_r1`, `_r2` | pequeño | 5000 | 90 | OK | 87, 87 | 1463, 1451 s | Comparación de la etapa 1 |
| `prueba_respaldo_60s`, `_b` | pequeño | 5000 | 1 min | NO VÁLIDA | 15, 16 | — | Camino NO VÁLIDA y botones del diálogo |
| `prueba_crudos_a`, `_b` | pequeño | 5000 | 1 min | NO VÁLIDA | 15, 14 | — | Copia de los registros crudos |
| `prueba_lote_corto_r01`–`r03` | pequeño | 5000 | 1 min | NO VÁLIDA | 15, 14, 15 | — | Lote seguido y retomar |
| `prueba_oculta_r01` | pequeño | 5000 | 1 min | NO VÁLIDA | — | — | Ventana oculta |
| `prueba_interfaz_r01`, `_r02` | pequeño | 5000 | 1 min | NO VÁLIDA | — | — | Lanzar desde la interfaz |
| `prueba_defecto_r01`, `_r02` | pequeño | 5000 (código) | 90 | OK | 88, 88 | 1455, 1485 s | Prueba 2 de la etapa 2 (todo por defecto) |
| `prueba_deadline1500_r01` | pequeño | 1500 | 90 | OK | 92 | 736 s | Prueba 3 de la etapa 2 |
| `prueba_deadline500_r01` | pequeño | 500 | 90 | OK | 80 | 705 s | Deadline bajo |
| `prueba_deadline1_r01` | pequeño | 1 | 90 | OK, plan vacío | 0 | 723 s | Cierre sin slots |
| `oculta_defecto_r01`, `_r02` | pequeño | 5000 | 90 | OK | 89, 83 | 1455, 1489 s | Sin ventana, corrida completa |
| `oculta_mediano_r01` | mediano | 5000 | 3 min | NO VÁLIDA | 78 | 182 s | Escenario mediano |
| `oculta_grande_r01` | grande | 5000 | 3 min | NO VÁLIDA | 136 | 184 s | Escenario grande |

Además, `prueba_red_seguridad_r01` quedó **colgada** a propósito (tiempo máximo
de 0,5 min) y no dejó carpeta.

**Tiempos de referencia en este PC** (Lenovo IdeaPad S145, 4 núcleos, 3 GB de
RAM):

- unos 24–25 min por corrida del escenario pequeño con 5000 ms;
- unos 12 min con deadlines de 2000 ms o menos.

---

## 9. Comandos: operación y análisis

Todos se ejecutan desde la raíz del repositorio (`cd ~/Documentos/TDS-MARL`).
En Windows, el separador del classpath es `;` y se usa `python` en lugar de
`python3`.

### 9.1 Operación

```bash
# Compilar a mano (los lotes compilan solos)
javac --release 8 -encoding UTF-8 -d bin \
  -cp lib/jade.jar:$HOME/.m2/repository/org/jgrapht/jgrapht-core/1.0.1/jgrapht-core-1.0.1.jar src/*.java

# Una corrida a mano, sin recompilar (con ventana y diálogo, como siempre)
java -Dtds.etiqueta=prueba1 -Dtds.escenario=pequeno -Dtds.deadlineMs=3000 \
     -cp bin:lib/jade.jar:$HOME/.m2/repository/org/jgrapht/jgrapht-core/1.0.1/jgrapht-core-1.0.1.jar MainContainer
#   opcionales: -Dtds.topeNegociacionMin=<min|null>  -Dtds.sinVentana=true  -Dtds.sinDialogo=true

# Lotes
python3 correr_lote.py experimentos.json --simular       # ver qué haría, sin ejecutar
python3 correr_lote.py experimentos.json                 # lanzar; relanzar = retomar
touch corridas/_lotes/<fecha_hora>/DETENER               # detener tras la corrida en curso
cat corridas/_lotes/<fecha_hora>/estado.json             # estado de un lote en curso
tail -f corridas/_lotes/<fecha_hora>/lote.log            # seguir un lote en curso
python3 interfaz_lotes.py experimentos.json              # interfaz gráfica

# ¿Hay una corrida de Java viva? ¿Está libre el puerto de JADE?
ps -eo pid,etime,args | grep "[j]ava.*MainContainer"
ss -ltn | grep ":1099 " || echo "puerto 1099 libre"

# Pruebas y verificación del código original (antes de cada lote importante y de cada commit)
python3 pruebas/correr_pruebas.py
python3 pruebas/verificar_codigo_original.py
```

### 9.2 Análisis de resultados

Todos se probaron sobre las corridas reales; se muestra un ejemplo de salida.

**A. Promedio ± desviación por configuración** (solo corridas válidas). La
configuración se deduce de la etiqueta quitando `_rNN`. Es la tabla base del
análisis de la tesis.

```bash
python3 - <<'EOF'
import csv, re, statistics as st
from collections import defaultdict
g = defaultdict(list)
for r in csv.DictReader(open("corridas/resumen_corridas.csv", encoding="utf-8")):
    if r["corrida_valida"] == "true":
        g[re.sub(r"_r\d+$", "", r["etiqueta"])].append(r)
cols = ["ciclos_planificados", "toneladas_descargadas_en_turno", "duracion_negociacion_inicial_ms",
        "uso_promedio_palas_pct", "mensajes_total"]
for k, filas in sorted(g.items()):
    v = []
    for c in cols:
        xs = [float(f[c]) for f in filas if f[c] != ""]
        v.append("%s=%.1f±%.1f" % (c, st.mean(xs), st.stdev(xs) if len(xs) > 1 else 0))
    print(k, "n=%d" % len(filas), *v)
EOF
```

Ejemplo de salida:

```
oculta_defecto n=2 ciclos_planificados=86.0±4.2 toneladas_descargadas_en_turno=25638.5±688.0 duracion_negociacion_inicial_ms=1471951.0±24204.3 ...
prueba_deadline500 n=1 ciclos_planificados=80.0±0.0 toneladas_descargadas_en_turno=23052.0±0.0 ...
```

**B. Reparto de cargas por pala en cada corrida** (hallazgo H1):

```bash
for d in corridas/*/plan_inicial.csv; do printf "%-28s " "$(basename $(dirname $d))"
  awk -F, '$2=="CAMION" && $4=="CARGA"{n[$3]++; t++} END{for(p in n) printf "%s=%d(%.0f%%) ", p, n[p], 100*n[p]/t; print ""}' "$d"
done | sort
```

Ejemplo: `oculta_defecto_r01  PA01=4(4%) PA02=4(4%) PA03=77(87%) PA04=1(1%) PA05=3(3%)`

**C. Motivos de cierre de las negociaciones de una corrida** (H15):

```bash
cut -d, -f9 corridas/oculta_defecto_r01/negociacion_inicial.csv | tail -n +2 | sort | uniq -c | sort -rn
```

```
   1376 No hubieron propuestas
     89 recibi inform. ganador acepto
     11 recibi Disconfirm
      4 recibi refuse despues de request
      3 deadline cumplido. No hubieron propuestas
```

**D. Confirmar el `DEADLINE_MS` que usó una corrida:**

```bash
grep -o "(.* ms)" corridas/prueba_deadline500_r01/registros_negociacion/*messages.csv | sort | uniq -c
#    1547 (500 ms)
python3 -c "import json; c=json.load(open('corridas/prueba_deadline500_r01/configuracion.json')); print(c['deadline_ms'], c['origen_parametros'])"
```

**E. Camiones sin trabajo en una corrida** (H3):

```bash
awk -F, '$2=="CAMION" && $4=="CARGA"{c[$1]=1} END{for(i=0;i<15;i++) if(!("Truck" i in c)) print "Truck" i}' corridas/oculta_defecto_r01/plan_inicial.csv
# Truck2  Truck7
```

**F. Duración de las negociaciones de una corrida:**

```bash
python3 -c "
import csv, statistics as st
f = list(csv.DictReader(open('corridas/oculta_defecto_r01/negociacion_inicial.csv')))
d = [int(r['duracion_ms']) for r in f]; a = [int(r['duracion_ms']) for r in f if r['acepto'] == 'true']
print('todas: n=%d media=%.0f ms mediana=%.0f | con ganador: n=%d media=%.0f ms' % (len(d), st.mean(d), st.median(d), len(a), st.mean(a)))"
# todas: n=1483 media=3726 ms mediana=3753 | con ganador: n=89 media=3710 ms
```

**G. ¿Dos planes son el mismo?** Cuenta las tareas idénticas (H4):

```bash
python3 -c "
import csv
from collections import Counter
a, b = [Counter(tuple(r) for r in list(csv.reader(open(f)))[1:]) for f in
        ('corridas/oculta_defecto_r01/plan_inicial.csv', 'planes_iniciales/plan_inicial_datosShovelTrucksPequeno.csv')]
print('tareas identicas: %d de %d / %d' % (sum((a & b).values()), sum(a.values()), sum(b.values())))"
# tareas identicas: 22 de 534 / 522
```

**H. "ERROR de solapamiento" por punto de descarga en los logs de los lotes**
(H12):

```bash
grep -h "ERROR de solapamiento" corridas/_lotes/*/*.log | sed 's/:\? *ERROR.*//' | sort | uniq -c
```

**I. Corridas cuyo plan no salió "limpio":**

```bash
grep -L "limpio" corridas/*/plan_inicial_verificacion.txt
```

**J. Mensajes por tipo, control cruzado y advertencias de una corrida:**

```bash
python3 -c "
import json; d = json.load(open('corridas/oculta_defecto_r01/resumen_plan.json'))
print(d['mensajes_por_tipo']); print(d['control_cruzado_mensajes']); print(d['advertencias'])"
```

**K. Uso de cada pala en una corrida:**

```bash
python3 -c "
import json; d = json.load(open('corridas/pequeno_despues_r1/resumen_plan.json'))
print([(p['pala'], p['cargas'], p['uso_pct']) for p in d['palas']], 'promedio', d['uso_promedio_palas_pct'])"
```

---

## 10. Inventario de archivos

### 10.1 Archivos originales modificados (autorizados)

| Archivo | Líneas (+/−) | Cambio |
|---|---|---|
| `src/VentanaSimulacion.java` | +65 −1 | Cierre al capturar el plan, en vez de simular el turno (`TERMINAR_TRAS_PLAN_INICIAL`); cierre sin slots (`evaluarCierreSinSlots`); campos `tRealFinDetectado` y `tRealInicioSinSlots` |
| `src/InicializadorSimulador.java` | +7 −1 | No hacer visible la ventana con `-Dtds.sinVentana=true` |
| `src/MainContainer.java` | +10 | `ConfiguracionCorrida.validar()`, `CierrePlanInicial.verificarEtiquetaLibre()`, campo `contenedorPrincipal` |
| `src/ParametrosConfiguracion.java` | +4 −2 | `DEADLINE_MS` y `NOMBRE_ARCHIVO_FLOTA` inicializados con `ConfiguracionCorrida` (mismos valores por defecto) |
| `src/ParametrosSimulador.java` | +21 −4 | `ETIQUETA_ESCENARIO` y `TOPE_NEGOCIACION_INICIAL_MS` entregables; `USAR_PLAN_INICIAL_FIJO = false`; `METODO_RESCHEDULING = NINGUNO`; nuevo `TERMINAR_TRAS_PLAN_INICIAL = true` |
| `src/GuardarPlanInicial.java` | +4 | No guardar un plan vacío como plan único |
| `src/CsvNegotiationsLogger.java` | +1 −1 | Encabezado corregido |
| `preprocesar_registros.py` | +13 | Comentario de obsolescencia (lógica intacta) |

Las líneas exactas están en el **Anexo A**.

### 10.2 Archivos nuevos

| Archivo | Líneas | Para qué |
|---|---|---|
| `src/CierrePlanInicial.java` | 199 | Cierre: resultados, copia de registros, diálogo, apagado de JADE, verificación de la etiqueta |
| `src/EstadisticasPlanInicial.java` | 714 | Cálculo y escritura de todos los archivos de resultados |
| `src/ConfiguracionCorrida.java` | 167 | Lectura y validación de los parámetros `-Dtds.*` |
| `correr_lote.py` | 520 | Lanzador de lotes |
| `interfaz_lotes.py` | 449 | Ventana para armar y lanzar lotes |
| `experimentos_ejemplo.json` | 10 | Lote de ejemplo (3 configuraciones × 10 repeticiones) |
| `DOCUMENTACION_REGISTROS.md` | — | Significado de cada archivo y campo, fórmulas, limitaciones |
| `COMO_CORRER.md` | — | Guía de uso, valores bajos de `DEADLINE_MS`, pruebas |
| `HISTORIAL_TRABAJO_CIERRE_SCHEDULE.md` | — | Este documento |
| `pruebas/verificar_codigo_original.py` | 96 | Verificación del código original |
| `pruebas/correr_pruebas.py` | 40 | Corre todas las pruebas |
| `pruebas/test_estadisticas.py` | 353 | Pruebas de los cálculos y de los parámetros de Java |
| `pruebas/java/ArnesEstadisticas.java` | 19 | Arnés Java para esas pruebas |
| `pruebas/test_correr_lote.py` | 334 | Pruebas del lanzador de lotes |
| `pruebas/test_interfaz.py` | 265 | Pruebas de la interfaz |
| `pruebas/__init__.py` | 0 | Hace de `pruebas/` un paquete de Python |

### 10.3 Archivos que generan las corridas (no van a git)

| Ruta | Contenido |
|---|---|
| `corridas/<etiqueta>/` | `configuracion.json`, `negociacion_inicial.csv`, `resumen_plan.json`, `resumen_plan.txt`, `plan_inicial.csv`, `plan_inicial_verificacion.txt`, `registros_negociacion/` (CSV crudos), `schedules_por_reprogramacion/` (plan inicial) |
| `corridas/resumen_corridas.csv` | Una fila por corrida |
| `corridas/_lotes/<fecha_hora>/` | `lote.json`, `lote.log`, `estado.json`, un `.log` por corrida y `salida_script.txt` (lotes lanzados desde la interfaz) |
| `corridas/_pruebas_lote/` | Los JSON usados en las pruebas |
| `<flota>SinRL{messages,negotiations,schedules}.csv` | Registros crudos en la raíz; los borra la corrida siguiente |
| `bin/` | Clases compiladas |

### 10.4 Fuera del repositorio

| Qué | Dónde |
|---|---|
| Paquete del sistema | `python3-tk` (Ubuntu) |
| Notas para retomar el trabajo | `~/.claude/projects/-home-vito-pc/memory/` |

### 10.5 Archivos obsoletos (se conservan como referencia)

- `preprocesar_registros.py`;
- `FLUJO_EPISODIOS_LLM.md`;
- `DUDAS_PROFESOR.md`.

Eran para la etapa de episodios de reprogramación.

---

## 11. Cronología y commits

| Fecha | Etapa | Hechos |
|---|---|---|
| 17-nov | 1 | Plan aprobado (12 decisiones); rama `cierre-schedule-inicial` creada desde `preprocesador-registros`; 2 corridas con el código original; implementación; hallazgo H2 y criterio de mensajes; 2 corridas nuevas; pruebas de puerto, diálogo y revisión manual; commit `5d09ee1` |
| 17-nov | 1 (agregados) | Copia de registros crudos; comparación de planes (H4); verificación de `PlanInicialFijo`; sección "Limitaciones conocidas" |
| 17-nov | 2 | Plan de lotes (análisis de deadlines bajos, del tope y del cuelgue H5); decisiones; `ConfiguracionCorrida`, cierre sin slots, `correr_lote.py`, `COMO_CORRER.md`; pruebas de validación, lote corto, red de seguridad; instalación de `python3-tk`; interfaz; lote largo interrumpido por la suspensión del PC |
| 23-nov | 2 | Lote retomado; deadlines de 5000, 1500, 500 y 1 ms; prueba de la interfaz; se completa "Observado en las pruebas" |
| 23-nov | 3 | Ventana oculta; regla del código original reforzada; verificación automática; 49 pruebas automáticas; corridas sin ventana en los escenarios pequeño, mediano y grande |
| 23-nov | — | Commits del equipo: `40a84b1` (lotes, ventana, interfaz, pruebas), `850074e` (quita `__pycache__`), `56ac91f` (primer historial); push de la rama a GitHub |

**Nota sobre el push.** La primera vez, `git push` falló con "has no upstream
branch", porque la rama no existía en GitHub. Se resolvió con
`git push --set-upstream origin cierre-schedule-inicial`.

---

## 12. Pendientes, riesgos y recomendaciones

**Para la tesis, en este orden:**

1. **Investigar y consultar H1 (concentración en una pala) con el profesor
   antes del lote grande.** Un cambio en el modelo obligaría a repetir todas las
   corridas.
2. **Diseñar el experimento:**
   - valores de `DEADLINE_MS`: una propuesta es 500, 1000, 1500, 2000, 3000,
     5000, 8000 y 12000, que cubre los regímenes de 2000 ms o menos y de más de
     2000 ms;
   - repeticiones: 10 o más por valor, por H4;
   - en qué PC correrlo: 8 valores × 10 repeticiones son unas 40 a 60 h en este
     PC.

   Antes del lote grande, conviene un lote piloto chico (por ejemplo, 3
   deadlines × 3 repeticiones) para medir tiempos.
3. **Definir qué es un "mejor plan".** Es la métrica que optimizará el LLM.
   Candidatas: toneladas descargadas en turno, uso de palas, camiones con
   trabajo, duración de la negociación, mensajes.
4. **Preparar el análisis** sobre `corridas/resumen_corridas.csv` (comando A):
   promedio ± desviación, boxplots, intervalos de confianza.
5. **Construir el componente LLM.** Hay que decidir:
   - qué recibe (por ejemplo, `configuracion.json` y `resumen_plan.json` de
     corridas anteriores);
   - qué devuelve (un `DEADLINE_MS` con justificación);
   - el prompt;
   - contra qué se compara: el mejor valor de la grilla, el valor por defecto o
     uno al azar.

   Los campos `llm_modelo`, `llm_recomendacion` y `llm_latencia_ms` ya están
   reservados en `configuracion.json`.
6. **Crear un nuevo documento de dudas para el profesor**, que reemplace al
   obsoleto: H1, H3 (camiones sin ruta) y el régimen de 2000 ms del camión
   (H7).
7. **Redacción.**
   - **Metodología:** puede basarse en `DOCUMENTACION_REGISTROS.md` (métricas)
     y en este documento (decisiones y limitaciones).
   - **Marco teórico:** Contract Net Protocol, despacho camión–pala, LLM para
     ajustar parámetros.

**Reparto sugerido para el equipo:**

- una persona: puntos 1, 2, 4 y 6 (experimento, análisis y dudas);
- la otra: puntos 3, 5 y 7 (métrica, LLM y redacción).

**Riesgos:**

- **Tiempo de cómputo alto:** cada corrida dura minutos de reloj real y no se
  puede acelerar sin tocar la negociación (H15).
- **Entorno gráfico:** las corridas lo necesitan aunque la ventana esté oculta.
- **Pesos copiados:** si alguna vez se cambian los pesos en `ShovelAgent`, hay
  que actualizar a mano la copia de `EstadisticasPlanInicial` (está marcada con
  `TODO`).
- **Una corrida por valor:** las conclusiones con deadlines bajos (H7) se basan
  en una sola corrida cada una.

**Buenas prácticas:**

- correr `python3 pruebas/correr_pruebas.py` antes de cada commit y de cada lote
  importante;
- usar `--simular` antes de lanzar un lote;
- usar etiquetas descriptivas, que ya traen el número de repetición.

---

## 13. Glosario

| Término | Significado |
|---|---|
| **Actualizar encabezado** | Reescribir `resumen_corridas.csv` con las columnas nuevas, conservando todas las filas |
| **Captura** | Fotografía del plan inicial (`PlanBase.capturar`) |
| **CFP** | *Call for proposals*: la oferta de una pala a los camiones |
| **CNP** | *Contract Net Protocol*: el protocolo de negociación |
| **Colgada** | Corrida que superó `tiempo_maximo_por_corrida_min`; el script la terminó |
| **Control cruzado** | Contar los mensajes desde el emisor y desde el receptor; deben coincidir |
| **Copias de la agenda** | La agenda de cada camión, pala y punto de descarga, y la copia que lee el simulador; deben coincidir |
| **Corrida válida** | La que terminó con todas las palas con el turno lleno (`negociacion_completa`) |
| **Corrida NO VÁLIDA** | La que terminó por el tope de tiempo (`respaldo_estabilidad`); su plan está incompleto |
| **`DEADLINE_MS`** | Plazo que da la pala a los camiones para responder un CFP |
| **Entrega** | Un mensaje recibido por un destinatario (un CFP a 15 camiones = 15 entregas) |
| **Envío** | Una fila SEND (un CFP a 15 camiones = 1 envío) |
| **Fallida** | Corrida cuyo proceso terminó sin dejar `resumen_plan.json` |
| **Fase inicial** | Filas con `t_sim_ms = -1` anteriores a la captura |
| **Lote** | Conjunto de corridas definido en un JSON |
| **Plan único** | Plan guardado por escenario en `planes_iniciales/` (para `USAR_PLAN_INICIAL_FIJO = true`) |
| **Plan vacío** | Plan sin ninguna carga |
| **Slot / tarea** | Una actividad de la agenda (viaje vacío, carga, viaje cargado, descarga) |
| **Tope de la negociación** | `TOPE_NEGOCIACION_INICIAL_MS`: si se supera, la corrida queda NO VÁLIDA |
| **Tiempo máximo por corrida** | Seguro del script contra procesos colgados; no es parte del experimento |
| **Turno lleno** | Cuando la oferta de una pala supera las 6 h |

---

## Anexo A. Líneas exactas cambiadas en los archivos originales

Generado con `git diff cc87b39 HEAD -- <archivo>`, sobre el estado commiteado.
Las líneas que empiezan con `-` son las originales; las que empiezan con `+`,
las nuevas.


### `src/VentanaSimulacion.java`

```diff
@@ -88,6 +88,8 @@ public class VentanaSimulacion extends JFrame {
     private boolean planFijoListoImpreso = false;
     private long    tRealPalasCompletas = 0L;
     private boolean avisoCopias         = false;
+    private long    tRealFinDetectado   = 0L;   // fin de la negociacion detectado (CierrePlanInicial)
+    private long    tRealInicioSinSlots = 0L;   // inicio de la espera sin ningun slot (evaluarCierreSinSlots)
     private long ultimaVersionEventos = -1; // recalcular también si cambian los eventos
 
     public VentanaSimulacion(Graph<Vertice, DefaultWeightedEdge> grafo) {
@@ -398,7 +400,12 @@ public class VentanaSimulacion extends JFrame {
         if (PlanInicialFijo.usar() && !planFijoListo(btnPlayPause)) return;
 
         Map<String, Schedule> agendas = RegistroSimulacion.getInstancia().getAgendasCamiones();
-        if (agendas.isEmpty()) return;      // todavia no llego ningun slot
+        if (agendas.isEmpty()) {            // todavia no llego ningun slot
+            if (ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL && !PlanInicialFijo.usar()) {
+                evaluarCierreSinSlots(btnPlayPause);
+            }
+            return;
+        }
 
         long firma = firmaDelPlan(agendas);
         long ahora = System.currentTimeMillis();
@@ -449,6 +456,9 @@ public class VentanaSimulacion extends JFrame {
         boolean porPalas = ShovelAgent.negociacionInicialCompleta();
         boolean porRespaldo = (ahora - tRealPrimerSlot)
                 >= ParametrosSimulador.TOPE_NEGOCIACION_INICIAL_MS;
+        // Instante (reloj de pared) en que se detecto el fin de la negociacion,
+        // antes de esperar las copias. Solo se registra (espera_copias_ms).
+        if ((porPalas || porRespaldo) && tRealFinDetectado == 0L) tRealFinDetectado = ahora;
 
         // Negociacion terminada: antes de fotografiar el plan, se espera a que
         // las cuatro copias de la agenda tengan las mismas tareas. El lector
@@ -478,6 +488,20 @@ public class VentanaSimulacion extends JFrame {
                         ShovelAgent.getPalasRegistradas());
             }
             yaArranco = true;
+            if (ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL) {
+                // Solo interesa el schedule inicial: se fotografia el plan
+                // (igual que antes, PlanBase.capturar escribe plan_inicial.csv),
+                // NO se arranca el turno (el reloj queda en 0) y se cierra la
+                // corrida: registros, resumen y apagado de JADE.
+                String motivo = !porPalas ? "respaldo_estabilidad"
+                        : PlanInicialFijo.usar() ? "plan_inicial_fijo" : "negociacion_completa";
+                long tCaptura = System.currentTimeMillis();
+                PlanBase.capturar(tiempoSimulacionMs, motivo);
+                btnPlayPause.setEnabled(false);
+                btnPlayPause.setText("✔ PLAN INICIAL GENERADO - cerrando");
+                CierrePlanInicial.cerrar(motivo, tCaptura, tRealFinDetectado);
+                return;
+            }
             pausado   = false;
             // Fotografia del PLAN PREDICTIVO justo antes de que el cursor avance:
             // es la referencia contra la que se mide la estabilidad al final.
@@ -503,6 +527,46 @@ public class VentanaSimulacion extends JFrame {
         }
     }
 
+    /**
+     * Negociacion inicial SIN NINGUN SLOT (p. ej. DEADLINE_MS tan bajo que
+     * ninguna negociacion tiene ganador). Sin esto la corrida quedaba abierta
+     * para siempre: evaluarArranqueAutomatico sale antes de mirar el criterio
+     * de palas llenas y el tope, porque las agendas del simulador se crean
+     * recien con el primer slot. Solo actua con TERMINAR_TRAS_PLAN_INICIAL y
+     * solo mientras no hay slots, asi que no cambia nada cuando hay plan.
+     *
+     * Cierra con plan vacio cuando todas las palas llenaron su turno (motivo
+     * negociacion_completa) o, si eso no pasa, al cumplirse el tope contado
+     * desde la primera evaluacion de la ventana (motivo respaldo_estabilidad).
+     */
+    private void evaluarCierreSinSlots(javax.swing.JButton btnPlayPause) {
+        long ahora = System.currentTimeMillis();
+        if (tRealInicioSinSlots == 0L) tRealInicioSinSlots = ahora;
+        if (ahora - tRealInicioSinSlots >= 15_000L && ahora - tRealUltimaTraza >= 15_000L) {
+            tRealUltimaTraza = ahora;
+            System.out.printf("[Negociacion inicial] t+%.0fs  palas %d/%d  camiones 0  slots 0  (sin slots todavia)%n",
+                    (ahora - tRealInicioSinSlots) / 1000.0,
+                    ShovelAgent.getPalasConTurnoLleno(), ShovelAgent.getPalasRegistradas());
+        }
+        boolean porPalas = ShovelAgent.negociacionInicialCompleta();
+        boolean porRespaldo = (ahora - tRealInicioSinSlots) >= ParametrosSimulador.TOPE_NEGOCIACION_INICIAL_MS;
+        // Si los agentes tienen tareas que el simulador aun no lee, se espera:
+        // el camino normal se hace cargo apenas aparezca el primer slot.
+        if (porPalas && !PlanInicialFijo.copiasAlDia()) return;
+        if (!porPalas && !porRespaldo) return;
+        if (tRealFinDetectado == 0L) tRealFinDetectado = ahora;
+        yaArranco = true;
+        String motivo = porPalas ? "negociacion_completa" : "respaldo_estabilidad";
+        System.err.printf("[VentanaSimulacion] AVISO: la negociacion inicial termino SIN NINGUN SLOT "
+                + "(%d/%d palas con el turno lleno, motivo=%s). Se cierra con plan vacio.%n",
+                ShovelAgent.getPalasConTurnoLleno(), ShovelAgent.getPalasRegistradas(), motivo);
+        long tCaptura = System.currentTimeMillis();
+        PlanBase.capturar(tiempoSimulacionMs, motivo);
+        btnPlayPause.setEnabled(false);
+        btnPlayPause.setText("✔ PLAN INICIAL (VACIO) GENERADO - cerrando");
+        CierrePlanInicial.cerrar(motivo, tCaptura, tRealFinDetectado);
+    }
+
     /**
      * Plan inicial unico: true cuando todos los agentes cargaron su agenda del
      * archivo y el simulador ya leyo todas las tareas. Si el archivo falta o no
```

### `src/InicializadorSimulador.java`

```diff
@@ -94,7 +94,13 @@ public class InicializadorSimulador {
             // Ventana en el EDT.
             javax.swing.SwingUtilities.invokeLater(() -> {
                 VentanaSimulacion v = new VentanaSimulacion(grafoMina);
-                v.setVisible(true);
+                // -Dtds.sinVentana=true: la ventana existe (su reloj detecta el fin
+                // de la negociacion) pero no se muestra la mina.
+                if (ConfiguracionCorrida.sinVentana()) {
+                    System.out.println("[sim] ventana de la simulacion oculta (-Dtds.sinVentana=true)");
+                } else {
+                    v.setVisible(true);
+                }
                 System.out.println("[sim] ventana abierta. escenario=\""
                     + ParametrosSimulador.ETIQUETA_ESCENARIO + "\"");
                 // Capturador de snapshots: periodico + por evento. Si la ventana
```

### `src/MainContainer.java`

```diff
@@ -20,8 +20,17 @@ import java.io.File;
  * Clase principal que inicializa el contenedor JADE y lanza varios agentes Shovel y Truck.
  */
 public class MainContainer {
+    /** Contenedor principal de JADE; lo usa CierrePlanInicial para apagar la plataforma. */
+    public static volatile AgentContainer contenedorPrincipal;
+
     public static void main(String[] args) {
 
+    	// Parametros entregados al lanzar (-Dtds.*): si alguno es invalido, no arranca.
+    	ConfiguracionCorrida.validar();
+
+    	// Si la corrida termina tras el plan inicial, la etiqueta no puede estar usada.
+    	CierrePlanInicial.verificarEtiquetaLibre();
+
     	// === simulador (plus) === borra CSVs de la corrida anterior para empezar limpio
     	InicializadorSimulador.limpiarArchivosPrevios();
 
@@ -35,6 +44,7 @@ public class MainContainer {
         // Perfil por defecto (localhost, puerto 1099, GUI de JADE)
         Profile profile = new ProfileImpl();
         AgentContainer mainContainer = rt.createMainContainer(profile);
+        contenedorPrincipal = mainContainer;
         crearAgentesUnloadingPoints(mainContainer);
         crearAgentesPalas(mainContainer, grafoMina);
         crearAgentesCamiones(mainContainer, grafoMina);
```

### `src/ParametrosConfiguracion.java`

```diff
@@ -3,12 +3,14 @@ public class ParametrosConfiguracion {
 
 	public static int NUMERODESHOVELS;//en main container se inicializa.
 	public static int NUMERODETRUCKS; //en main container se inicializa.
-	public static long DEADLINE_MS=5000L;
+	// DEADLINE_MS, NOMBRE_ARCHIVO_FLOTA: el valor escrito es el POR DEFECTO; se pueden
+	// entregar al lanzar con -Dtds.deadlineMs y -Dtds.escenario (ver ConfiguracionCorrida y COMO_CORRER.md).
+	public static long DEADLINE_MS=ConfiguracionCorrida.deadlineMs(5000L);
 	public static final long SEGUNDO_FINAL_HORARIO = 6*60*60*1000; // turno de 6 horas en milisegundos;
 	public static final String LEARNING_ENABLED = "false";
 	//public static final String NOMBRE_ARCHIVO_FLOTA = "datosShovelTrucksPequeno.owl";
 	//public static final String NOMBRE_ARCHIVO_FLOTA = "datosShovelTrucksMediano.owl";
-	public static final String NOMBRE_ARCHIVO_FLOTA = "datosShovelTrucksGrande_original.owl";
+	public static final String NOMBRE_ARCHIVO_FLOTA = ConfiguracionCorrida.escenario("datosShovelTrucksGrande_original.owl");
 	
 	
 	public static final String prefijoArchivos() {
```

### `src/ParametrosSimulador.java`

```diff
@@ -15,7 +15,8 @@ public class ParametrosSimulador {
      *   "mediano_baseline_capa1"
      *   "grande_baseline_capa1"
      */
-    public static final String ETIQUETA_ESCENARIO = "grande_F1_COMPLETE";
+    // Valor POR DEFECTO; se puede entregar con -Dtds.etiqueta (ver ConfiguracionCorrida).
+    public static final String ETIQUETA_ESCENARIO = ConfiguracionCorrida.etiqueta("grande_F1_COMPLETE");
 
     /**
      * Switch maestro de incertidumbre.
@@ -64,7 +65,7 @@ public class ParametrosSimulador {
     }
 
     public static final MetodoRescheduling METODO_RESCHEDULING =
-            MetodoRescheduling.COMPLETE_REGENERATION;
+            MetodoRescheduling.NINGUNO;
 
     /**
      * Variacion maxima del factor de velocidad propio de cada camion, como
@@ -133,7 +134,7 @@ public class ParametrosSimulador {
      * plan_inicial_verificacion.txt dice si partio exactamente del plan unico.
      * Ver PlanInicialFijo y GuardarPlanInicial.
      */
-    public static final boolean USAR_PLAN_INICIAL_FIJO = true;
+    public static final boolean USAR_PLAN_INICIAL_FIJO = false;
 
     /**
      * Cada cuántos milisegundos releé el CSV de schedules para detectar nuevos
@@ -206,5 +207,21 @@ public class ParametrosSimulador {
      * camiones cada negociacion tarda mas. 90 minutos deja margen para el
      * grande; el tope solo debe actuar si algo quedo realmente colgado.
      */
-    public static final long TOPE_NEGOCIACION_INICIAL_MS = 5_400_000L; // 90 min
+    // 90 min POR DEFECTO; -Dtds.topeNegociacionMin=<min|null> lo cambia (null = sin
+    // tope, vale ConfiguracionCorrida.SIN_TOPE). Ver ConfiguracionCorrida.
+    public static final long TOPE_NEGOCIACION_INICIAL_MS = ConfiguracionCorrida.topeNegociacionMs(5_400_000L);
+
+    /**
+     * Terminar la corrida apenas se genera el schedule inicial (nov-2026).
+     *
+     *   true  = al capturar el plan inicial NO se ejecuta el turno: se escriben
+     *           los registros y estadisticas del plan en corridas/<etiqueta>/,
+     *           se muestra un resumen y se apaga JADE (CierrePlanInicial).
+     *           Ademas, si corridas/<etiqueta>/ ya existe, la corrida no
+     *           arranca (para no sobrescribir resultados).
+     *   false = comportamiento anterior: la simulacion del turno arranca sola.
+     *
+     * Ver DOCUMENTACION_REGISTROS.md.
+     */
+    public static final boolean TERMINAR_TRAS_PLAN_INICIAL = true;
 }
```

### `src/GuardarPlanInicial.java`

```diff
@@ -91,6 +91,10 @@ public final class GuardarPlanInicial {
             } else if (!"negociacion_completa".equals(motivo)) {
                 conclusion = "No se guarda como plan unico: la negociacion inicial no termino (" + motivo + ").";
                 lineas.add(conclusion);
+            } else if (plan.isEmpty()) {
+                // Negociacion sin ningun slot (VentanaSimulacion.evaluarCierreSinSlots).
+                conclusion = "No se guarda como plan unico: el plan esta vacio.";
+                lineas.add(conclusion);
             } else if (!problemas.isEmpty()) {
                 conclusion = "No se guarda como plan unico: el plan tiene problemas.";
                 lineas.add(conclusion);
```

### `src/CsvNegotiationsLogger.java`

```diff
@@ -14,7 +14,7 @@ public class CsvNegotiationsLogger {
     private static PrintWriter writer;
 
     // Cabecera para el CSV (opcionales).
-    private static final String HEADER = "timestamp,agent,inicioNegociacion,finNegociacion,conversationId,content, duracion, ganador, refusesRecibidas, proposesRecibidas, informRecibidos, cancelRecibidos, t_sim_ms";
+    private static final String HEADER = "timestamp,agent,inicioNegociacion,finNegociacion,conversationId,content, duracion, ganador, proposesRecibidas, refusesRecibidas, informRecibidos, cancelRecibidos, t_sim_ms";
 
     // Se ejecuta una vez al cargar la clase
     static {
```

### `preprocesar_registros.py`

```diff
@@ -1,4 +1,17 @@
 #!/usr/bin/env python3
+# ─────────────────────────────────────────────────────────────────────────────
+# OBSOLETO (nov-2026). Este script era para los EPISODIOS DE REPROGRAMACION, que
+# el trabajo de titulo ya no estudia. Desde la rama cierre-schedule-inicial cada
+# corrida termina al generarse el schedule inicial y escribe sus propios
+# registros (ver DOCUMENTACION_REGISTROS.md y EstadisticasPlanInicial.java).
+#
+# Ademas, el encabezado de negotiations.csv YA ESTA CORREGIDO: las columnas 9 y
+# 10 dicen "proposesRecibidas, refusesRecibidas", en el orden en que se
+# escriben. Si se reutiliza este codigo, ya NO hay que invertir ni reinterpretar
+# esas columnas al leer (leer_negociaciones las toma por posicion, que sigue
+# siendo correcto).
+# Se conserva solo como referencia; no se modifico su logica.
+# ─────────────────────────────────────────────────────────────────────────────
 """Preprocesador de registros de TDS-MARL.
 
 Lee los CSV que el simulador ya genera y produce, por corrida, un JSON compacto
```
