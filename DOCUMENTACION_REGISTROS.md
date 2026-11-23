# Registros de una corrida: negociación inicial y schedule inicial

Este documento explica qué archivos deja cada corrida del sistema multiagente,
qué significa cada dato y cómo se calcula. Está pensado para alguien que no
conoce el código.

**Para lanzar corridas** (una a mano o un lote de experimentos con distintos
escenarios y `DEADLINE_MS`, sin recompilar), ver [COMO_CORRER.md](COMO_CORRER.md).

## 1. Qué hace una corrida

1. Se crean los agentes JADE: palas, camiones y puntos de descarga (destinos).
2. Las palas negocian con los camiones (Contract Net): cada pala ofrece una hora
   de carga, los camiones proponen o rechazan, la pala elige al mejor y el
   camión confirma. Así se va llenando el turno de 6 h de cada pala.
3. Cuando **todas las palas tienen el turno lleno**, la negociación inicial
   terminó. Se toma una fotografía del plan (el *schedule inicial*).
4. Con `ParametrosSimulador.TERMINAR_TRAS_PLAN_INICIAL = true` (valor por
   defecto), **la corrida termina ahí**: no se simula el turno. Se escriben los
   archivos de este documento, aparece un diálogo con un resumen y los botones
   "Abrir carpeta de resultados" y "Cerrar", y se apaga JADE (agentes y
   plataforma, libera el puerto 1099).

Si la negociación no terminó por sí sola sino por el **tope de tiempo**
(`TOPE_NEGOCIACION_INICIAL_MS`: 90 min por defecto, configurable o sin tope, ver
[COMO_CORRER.md](COMO_CORRER.md)), los archivos se generan igual pero la
corrida queda marcada **NO VÁLIDA** (`corrida_valida = false`) en el resumen y
en el diálogo: su plan está incompleto y no es comparable con las demás.

Si **ninguna** negociación tuvo ganador (por ejemplo, con un `DEADLINE_MS` muy
bajo), la corrida termina igual cuando las palas recorrieron su turno, con un
**plan vacío** (`plan_vacio = true`). Antes una corrida así no terminaba nunca.

### Antes de lanzar

- Cada corrida necesita una etiqueta nueva: `-Dtds.etiqueta=...` o, a mano,
  `ParametrosSimulador.ETIQUETA_ESCENARIO`. Si `corridas/<etiqueta>/` ya existe,
  **la corrida no arranca** (aviso por consola y, salvo en modo sin diálogo, en
  un diálogo), para no sobrescribir resultados. `correr_lote.py` las genera solo.
- El escenario se elige con `-Dtds.escenario=...` o en
  `ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA` (valor por defecto).
- Para estudiar la negociación: `USAR_PLAN_INICIAL_FIJO = false` (si es `true`
  no se negocia: se carga un plan guardado) y `METODO_RESCHEDULING = NINGUNO`.

## 2. Dónde quedan los archivos

| Archivo | Dónde | Cuándo se escribe |
|---|---|---|
| `configuracion.json` | `corridas/<etiqueta>/` | Al final de la corrida |
| `plan_inicial.csv` | `corridas/<etiqueta>/` | Al capturar el plan (`GuardarPlanInicial`) |
| `plan_inicial_verificacion.txt` | `corridas/<etiqueta>/` | Al capturar el plan (`GuardarPlanInicial`) |
| `negociacion_inicial.csv` | `corridas/<etiqueta>/` | Al final de la corrida |
| `resumen_plan.json` y `resumen_plan.txt` | `corridas/<etiqueta>/` | Al final de la corrida |
| `resumen_corridas.csv` | `corridas/` (acumulativo, una fila por corrida) | Al final de cada corrida |
| `<flota>SinRLmessages.csv` | raíz del repo (se borra al iniciar la siguiente corrida) | Durante la corrida, una fila por evento |
| `<flota>SinRLnegotiations.csv` | raíz del repo (ídem) | Durante la corrida, una fila por negociación cerrada |
| `<flota>SinRLschedules.csv` | raíz del repo (ídem) | Durante la corrida; lo lee el simulador (`LectorCSVSchedules`) |
| `schedules_por_reprogramacion/schedule_01.csv` | `corridas/<etiqueta>/` | Al capturar el plan |
| `registros_negociacion/<flota>SinRL{messages,negotiations,schedules}.csv` | `corridas/<etiqueta>/` | Al final de la corrida: copia de los tres CSV crudos de la raíz |

`<flota>` es el nombre del archivo OWL sin extensión, por ejemplo
`datosShovelTrucksPequenoSinRLmessages.csv`. Los tres CSV de la raíz se
borran al iniciar la siguiente corrida, por eso al cerrar se copian a
`corridas/<etiqueta>/registros_negociacion/`. La copia es el archivo completo
(no se filtra por fase); en una corrida NO VÁLIDA puede traer filas posteriores
a la captura del plan, que los resúmenes excluyen. La carpeta `corridas/` está
ignorada por git.

Los archivos nuevos (`configuracion.json`, `negociacion_inicial.csv`,
`resumen_plan.*`, `resumen_corridas.csv`) los escribe
`EstadisticasPlanInicial.java` **leyendo** `plan_inicial.csv`,
`negotiations.csv` y `messages.csv` y el estado en memoria del simulador. No
cambian nada de la negociación.

## 3. `configuracion.json`

Con qué se corrió.

| Campo | Significado |
|---|---|
| `etiqueta` | `ETIQUETA_ESCENARIO` |
| `fecha_hora` | Fecha y hora de término de la corrida |
| `escenario` | Archivo OWL de la flota (`NOMBRE_ARCHIVO_FLOTA`) |
| `palas`, `camiones`, `destinos` | Tamaño de la flota (cantidad) |
| `deadline_ms` | Plazo que da una pala a los camiones para responder un CFP (ms) |
| `pesos_seleccion_pala` | Los cinco pesos con que la pala elige al camión ganador (ver abajo) |
| `paso_barrido_ms` | Cuánto avanza la pala su oferta cuando nadie la acepta (60 000 ms) |
| `turno_ms` | Largo del turno (21 600 000 ms = 6 h) |
| `usar_plan_inicial_fijo`, `metodo_rescheduling`, `terminar_tras_plan_inicial` | Interruptores de `ParametrosSimulador` |
| `tope_negociacion_inicial_ms`, `tope_negociacion_min` | Tope de la negociación inicial efectivamente usado (ms y min); `null` = sin tope |
| `origen_parametros` | De dónde salió cada parámetro configurable (`etiqueta`, `escenario`, `deadline_ms`, `tope_negociacion_min`): `propiedad` (entregado al lanzar, por ejemplo por `correr_lote.py`) o `codigo` (valor escrito en el código) |
| `sin_dialogo` | `true` si la corrida se lanzó sin diálogos (lotes) |
| `sin_ventana` | `true` si no se mostró la ventana de la simulación (lotes, por defecto) |
| `java_version` | Versión de Java con que se ejecutó |
| `git_commit` | Commit del código (vacío si no se pudo obtener) |
| `git_sucio` | `true` si había cambios sin commit (por ejemplo, parámetros editados) |
| `llm_modelo`, `llm_recomendacion`, `llm_latencia_ms` | Reservados para el componente LLM; hoy siempre `null` |

**Pesos de selección.** La pala calcula para cada propuesta un costo y gana la
de menor costo:

`costo = 2,0·espera de la pala + 1,0·viaje vacío + 1,0·viaje cargado + 1,5·espera para descargar − 1000·capacidad del camión`

(tiempos en ms, capacidad en t). **Limitación:** en el código de la pala son
variables locales de `ShovelAgent.decideAndSendAcceptRejectMulticriterio`, y el
paso de 60 s está en `ShovelAgent.generaContenidoCFP`. `configuracion.json`
muestra una **copia** de esos valores (constantes al inicio de
`EstadisticasPlanInicial.java`, marcadas con `TODO`). Si se cambian en la pala,
hay que actualizar la copia a mano.

## 4. `plan_inicial.csv` (ya existía)

Una fila por tarea de la agenda de cada agente, tal como quedó al terminar la
negociación.

| Columna | Significado |
|---|---|
| `agente` | Camión (`Truck0`…), pala (`PA01`…) o destino |
| `tipo` | `CAMION`, `PALA` o `DESCARGA` (destino) |
| `contraparte` | En una CARGA del camión: la pala. En la agenda de una pala o destino: el camión |
| `operacion` | `VIAJE_VACIO`, `CARGA`, `VIAJE_CARGADO`, `DESCARGA` |
| `ubicacion` | Nodo del mapa |
| `inicio_ms`, `fin_ms` | Inicio y fin de la tarea, en ms desde el inicio del turno |
| `material` | Toneladas que lleva el camión |

Cada ciclo de un camión son 4 filas: viaje vacío, carga, viaje cargado y
descarga. `plan_inicial_verificacion.txt` dice si las agendas de camiones,
palas, destinos y simulador coinciden.

## 5. `negociacion_inicial.csv`

Una fila por negociación de la fase inicial, es decir, por cada fila de
`negotiations.csv` con `t_sim_ms = -1` que terminó antes de la captura del plan.
No se eliminan repeticiones (ver `conversationid_repetidos` más abajo).

| Columna | Unidad | Significado |
|---|---|---|
| `pala` | — | Pala que lanzó la negociación |
| `inicio`, `fin` | fecha y hora `yyyy-MM-dd HH:mm:ss.SSS` (reloj del computador) | Cuándo empezó y terminó |
| `duracion_ms` | ms | `fin − inicio` (menor = negociación más rápida) |
| `propuestas` | cantidad | PROPOSE recibidos por la pala en esa negociación, incluidos los que descartó por proponer una hora anterior a la ofrecida |
| `rechazos` | cantidad | REFUSE recibidos |
| `ganador` | — | Camión **seleccionado** por la pala (vacío si no hubo). Ojo: puede no haber aceptado; ver `acepto` |
| `acepto` | true/false | `true` si el seleccionado confirmó (motivo `recibi inform. ganador acepto`). Solo esas negociaciones agregan una carga al plan |
| `motivo_cierre` | — | Por qué terminó la negociación (texto del código de la pala) |
| `id_conversacion` | — | Identificador del Contract Net (sirve para buscar sus mensajes en `messages.csv`) |

Motivos de cierre posibles:

- `recibi inform. ganador acepto`: hubo ganador y confirmó; se agrega la carga.
- `No hubieron propuestas`: respondieron todos los camiones y ninguna propuesta
  era válida. La pala avanzará su oferta 60 s.
- `deadline cumplido. No hubieron propuestas`: igual, pero se agotó el plazo.
- `recibi Disconfirm`: el seleccionado ya no podía (se comprometió con otra
  pala); la pala vuelve a ofrecer la misma hora.
- `recibi refuse despues de request`: el seleccionado rechazó; ídem.

## 6. `resumen_plan.json` y `resumen_plan.txt`

Los dos tienen la misma información. El `.txt` está pensado para leerlo; el
`.json`, para procesarlo con un programa.

### Validez

| Campo | Significado |
|---|---|
| `motivo_fin_negociacion` | `negociacion_completa` (todas las palas llenaron su turno), `respaldo_estabilidad` (se agotó el tope de tiempo) o `plan_inicial_fijo` (no hubo negociación: se cargó un plan guardado) |
| `corrida_valida` | `true` solo si el motivo es `negociacion_completa` |
| `plan_vacio` | `true` si el plan no tiene ninguna carga (ninguna negociación con ganador). Puede ser una corrida válida: las palas recorrieron su turno sin lograr asignar camiones |

### Producción planificada

Se calcula sobre las filas `CAMION` de `plan_inicial.csv`.

| Campo | Unidad | Cálculo | Mejor |
|---|---|---|---|
| `toneladas_planificadas` | t | Suma de `material` de **todas** las CARGA de los camiones | mayor |
| `toneladas_planificadas_en_turno` | t | Ídem, solo CARGA que **terminan** a las 6 h o antes | mayor |
| `toneladas_descargadas_en_turno` | t | Suma de `material` de las DESCARGA que terminan a las 6 h o antes. Es la cifra de `plan_inicial_verificacion.txt` | mayor |
| `ciclos_planificados` | cantidad | Número de CARGA de los camiones (todas) | mayor |
| `ciclos_planificados_en_turno` | cantidad | CARGA que terminan a las 6 h o antes | mayor |
| `camiones_con_trabajo` | cantidad | Camiones con al menos una CARGA | mayor |
| `flota_total` | cantidad | Camiones de la flota | — |
| `pct_flota_con_trabajo` | % | `camiones_con_trabajo / flota_total × 100` | mayor |
| `filas_plan_inicial`, `tareas_camiones` | cantidad | Filas de `plan_inicial.csv` (todos los agentes) y filas de camiones | — |

**Diferencia entre las tres cifras de toneladas.** Una pala ofrece cargas hasta
las 6:00, pero el camión puede llegar más tarde, así que algunas cargas
terminan después del turno. Y una carga termina antes que su descarga.
Entonces, normalmente:
`toneladas_planificadas ≥ toneladas_planificadas_en_turno ≥ toneladas_descargadas_en_turno`.

- La primera mide todo lo que el plan compromete.
- La segunda, lo que se carga dentro del turno.
- La tercera, lo que llega a destino dentro del turno (lo que efectivamente se
  produce en 6 h).

### Palas

Para cada pala (lista `palas`, incluye palas sin cargas) a partir de las CARGA de
los camiones con esa pala como contraparte.

| Campo | Unidad | Cálculo | Mejor |
|---|---|---|---|
| `cargas` | cantidad | Número de CARGA con esa pala | mayor |
| `toneladas` | t | Suma de su `material` | mayor |
| `tiempo_ocupado_min` | min | Largo de la **unión** de los intervalos de carga, recortados a [0, 6 h]. Si dos cargas se solapan, el tramo común se cuenta una vez | mayor |
| `tiempo_ocioso_min` | min | `360 − tiempo_ocupado_min` (nunca negativo por el recorte) | menor |
| `uso_pct` | % | `tiempo_ocupado_min / 360 × 100` | mayor |
| `solapes` | pares | Pares de cargas de la misma pala cuyos intervalos se superponen (intersección > 0), sin recortar al turno | menor (lo correcto es 0) |
| `minutos_solapados` | min | Suma, sobre esos pares, del largo de la intersección. Con tres cargas superpuestas se suman los tres pares | menor |

Totales: `uso_promedio_palas_pct`, `tiempo_ocupado_promedio_min` y
`tiempo_ocioso_promedio_min` son el promedio simple sobre todas las palas.
`solapes_total` y `minutos_solapados_total` son la suma sobre las palas.

**Limitación de los solapes.** Una pala carga un camión a la vez, así que un
solape es físicamente imposible. Aparecen porque `ShovelAgent.AgregaGanador`
agrega la carga ganada **sin verificar si choca con otra** (ver
`TRABAJO-PENDIENTE.md`, punto 1.3). Es un defecto conocido del protocolo, no
del cálculo. Mientras haya solapes, el uso de la pala está sobreestimado
respecto de lo que podría ejecutarse.

### Negociación inicial

Se calcula sobre las filas de `negociacion_inicial.csv`.

| Campo | Unidad | Cálculo | Mejor |
|---|---|---|---|
| `duracion_negociacion_inicial_ms` | ms (reloj real) | Último `fin` menos primer `inicio` de las negociaciones de la fase inicial: la negociación pura | menor |
| `espera_copias_ms` | ms | Desde que se detectó el fin de la negociación hasta la captura del plan. Incluye la espera (máx. 30 s) a que coincidan las copias de las agendas y el intervalo del reloj de la ventana (50 ms) | menor |
| `duracion_hasta_captura_ms` | ms | Desde el primer `inicio` hasta la captura del plan | menor |
| `inicio_primera_negociacion`, `fin_ultima_negociacion`, `captura_plan` | fecha y hora | Los instantes usados arriba | — |
| `negociaciones_total` | cantidad | Filas de la fase inicial | — |
| `negociaciones_con_ganador` | cantidad | Filas con `acepto = true` | — |
| `negociaciones_sin_propuestas` | cantidad | Filas cuyo motivo dice "No hubieron propuestas" (con o sin deadline): ninguna propuesta **válida** | menor |
| `negociaciones_con_0_propuestas_recibidas` | cantidad | Filas con `propuestas = 0`. Puede ser menor que la anterior, porque una negociación puede recibir propuestas y descartarlas todas | menor |
| `negociaciones_por_motivo` | cantidad | Conteo por `motivo_cierre` | — |
| `conversationid_repetidos` | cantidad | `id_conversacion` que aparecen en más de una fila. Si es > 0 se anota como advertencia | 0 |

**Limitaciones.**

- Las duraciones son tiempo **real** del computador, no tiempo simulado:
  dependen de la máquina y de su carga.
- La negociación **no es determinista** (los agentes corren en hilos), así que
  dos corridas iguales dan cifras algo distintas.
- Una negociación puede quedar registrada dos veces (por ejemplo, DISCONFIRM y
  después un REFUSE tardío del mismo camión). Se cuentan todas las filas y se
  avisa.

### Mensajes de la fase inicial

Se calcula sobre `messages.csv`, filas con `t_sim_ms = -1` y hora anterior a la
captura.

Cómo registran los agentes:

- **Palas:** escriben una fila `SEND` por cada envío, **una sola vez aunque vaya
  a varios receptores** (un CFP a 15 camiones = 1 fila con 15 nombres en
  `receivers`).
- **Camiones:** escriben una fila `SEND` por envío, pero con la columna
  `receivers` **vacía**. Es un defecto del registro: `TruckAgent.getReceiverString`
  usa `concat` sin guardar el resultado. No se corrigió porque está en el código
  del agente. No afecta el conteo, porque un camión siempre envía a **un solo**
  receptor (responde con `createReply` o agrega un único destinatario).
- **Palas y camiones:** al recibir un mensaje escriben una fila `RECEIVE`.
- **Puntos de descarga:** **no** escriben `SEND` ni `RECEIVE`. Dejan filas
  descriptivas: "AGREE descarga (pendiente INFORM)" cuando reciben un REQUEST y
  responden AGREE, e "INFORM recibido - slot registrado" cuando reciben el INFORM
  final.
- Hay filas que no son mensajes ("Iniciando negociacion", "evaluacion
  multicriterio", "cumplimiento deadline", "llegaron todas las respuestas", etc.).

| Campo | Cálculo | Mejor |
|---|---|---|
| `mensajes_total` | **Entregas**: cada mensaje a cada receptor cuenta una vez, contado del lado del **emisor**. Fila `SEND` de pala: tantas entregas como receptores (un CFP a 15 camiones = 15; un `SEND` de pala sin receptores = 0, por ejemplo un REJECT_PROPOSAL cuando no hubo perdedores). Fila `SEND` de camión: 1. Fila "AGREE descarga" de un punto de descarga: 1 AGREE | menor (a igual plan) |
| `mensajes_por_tipo` | Lo mismo, separado por `performative` (CFP, PROPOSE, REFUSE, REQUEST, CONFIRM, DISCONFIRM, REJECT-PROPOSAL, AGREE, INFORM) | — |
| `envios_total`, `envios_por_tipo` | Filas `SEND` (un CFP a 15 camiones = 1 envío). No incluye los AGREE de los puntos de descarga, que no tienen fila `SEND` | — |
| `control_cruzado_mensajes` | Las mismas entregas contadas del lado del **receptor**: filas `RECEIVE` más las filas "AGREE descarga" (el punto de descarga recibió un REQUEST) e "INFORM recibido" (recibió un INFORM). `diferencia` = emisor − receptor; debería ser 0. Si no lo es, se anota como advertencia | diferencia 0 |
| `filas_messages_no_mensaje` | Filas ignoradas por no representar un mensaje | — |

En `messages.csv`, `t_sim_ms = -1` significa que la fila se escribió con el
reloj de simulación detenido. Como la corrida termina antes de que el reloj
avance, todas las filas de la fase inicial lo tienen.

**Por qué el control cruzado puede no calzar.** Un mensaje enviado justo antes
de la captura que el receptor todavía no leyó tiene `SEND` pero no `RECEIVE`.

### Advertencias

`advertencias` lista lo que conviene revisar: conversaciones repetidas, control
cruzado de mensajes que no calza, filas ilegibles, filas posteriores a la
captura omitidas.

## 7. `corridas/resumen_corridas.csv`

Una fila por corrida, para comparar corridas en una planilla. Tiene la
configuración principal (`etiqueta`, `fecha_hora`, `escenario`, flota,
`deadline_ms`, `tope_negociacion_min`, interruptores, `git_commit`,
`git_sucio`) y los totales de `resumen_plan.json` (incluido `plan_vacio`) con
los mismos nombres y definiciones. Las comas dentro de un valor se reemplazan
por `;`.

Si el archivo viene de una versión anterior con menos columnas, al agregar la
siguiente fila se reescribe con el encabezado actual: las filas antiguas
conservan sus valores y las columnas nuevas quedan vacías.

`corridas/_lotes/` guarda el registro de cada lote lanzado con `correr_lote.py`
(ver [COMO_CORRER.md](COMO_CORRER.md)).

## 8. Limitaciones conocidas

### Destinatarios vacíos en los envíos de los camiones

En `messages.csv`, las filas `SEND` escritas por los camiones traen la columna
`receivers` vacía. La causa es un defecto en `TruckAgent.getReceiverString`
(`src/TruckAgent.java:375`):

```java
receiverList.concat(r.getLocalName()+"/");   // el resultado de concat se descarta
```

En Java los `String` no se modifican: `concat` devuelve una cadena nueva que
aquí no se guarda, así que el método siempre devuelve una cadena vacía. No se
corrigió porque está en el código del agente y cambiaría el formato de
`messages.csv`.

**Cómo se compensa en el conteo de mensajes.** Un camión siempre envía cada
mensaje a **un solo** receptor (responde con `createReply` al emisor, o agrega
un único destinatario al pedir descarga o confirmarla). Por eso cada fila `SEND`
de un camión con `receivers` vacío se cuenta como **1** entrega
(`filas_send_camion_sin_receptores_contadas_como_1` en
`control_cruzado_mensajes`). En cambio, una fila `SEND` de una pala sin
receptores se cuenta como **0**, porque la columna de las palas sí es fiel: es
un REJECT_PROPOSAL enviado a una lista vacía cuando no hubo perdedores, que no
llega a nadie (`filas_send_pala_sin_receptores_contadas_como_0`). El control
cruzado (contar las mismas entregas desde el lado del receptor) confirma la
compensación: en las corridas de prueba la diferencia fue 0. Lo que **no** se
puede saber desde `messages.csv` es a quién envió cada camión; para eso hay que
mirar la fila `RECEIVE` correspondiente.

### El plan varía entre corridas con la misma configuración

La negociación no es determinista: los agentes JADE corren en hilos y el orden
en que llegan las respuestas cambia de una corrida a otra. Con exactamente la
misma configuración (escenario pequeño, `USAR_PLAN_INICIAL_FIJO = false`,
`METODO_RESCHEDULING = NINGUNO`), en las pruebas de noviembre de 2026 el plan
inicial tuvo **82, 95, 87 y 87 cargas** (328 a 380 tareas de camión) y la
negociación duró entre 1415 y 1463 s.

Incluso cuando dos corridas coinciden en la cantidad de cargas, los planes
**no son iguales**. Las dos corridas con 87 cargas y el plan único guardado del
escenario (`planes_iniciales/plan_inicial_datosShovelTrucksPequeno.csv`, también
87 cargas) comparten solo 40 a 60 de sus 522 tareas idénticas (mismo agente,
operación, horas y material). Además reparten la flota de forma muy distinta:
una corrida concentró 68 cargas en PA04, mientras la otra y el plan único
concentraron 75 y 76 en PA03.

Consecuencias:

- Una sola corrida no representa a una configuración. Para comparar
  configuraciones (por ejemplo, distintos `DEADLINE_MS` o pesos) hay que hacer
  varias réplicas y reportar promedio y dispersión.
- Coincidencias en conteos (cargas, tareas) no implican el mismo plan.
- Si se necesita partir siempre del mismo plan, está
  `USAR_PLAN_INICIAL_FIJO = true`, pero entonces no hay negociación inicial
  que estudiar.

## 9. Archivos antiguos

Siguen existiendo pero ya no son el foco:

| Archivo | Para qué sirve hoy |
|---|---|
| `<flota>SinRLmessages.csv`, `negotiations.csv`, `schedules.csv` | Registros crudos de la corrida (fuente de los archivos nuevos). En `negotiations.csv` el encabezado se corrigió: las columnas 9 y 10 son `proposesRecibidas, refusesRecibidas` (antes estaban rotuladas al revés) |
| `plan_inicial.csv`, `plan_inicial_verificacion.txt` | Plan inicial y su verificación (se usan tal cual) |
| `planes_iniciales/` | Planes únicos por escenario (para `USAR_PLAN_INICIAL_FIJO = true`) |
| `kpis_corridas.csv`, `estabilidad.csv`, `cascadas.csv`, `liberaciones.csv`, `regeneraciones.csv`, `evidencia_cascadas.csv`, `solapes_pala.csv`, `utilizacion_palas.csv`, `tonelaje_acumulado.csv`, `verificacion_partial_repair.csv`, `auditoria_simulacion_*.csv` | Resultados de la **ejecución del turno y la reprogramación**. Con `TERMINAR_TRAS_PLAN_INICIAL = true` el turno no se ejecuta y **no se crean** (comprobado con `METODO_RESCHEDULING = NINGUNO`) |
| `snapshots/corrida_<fecha>/manifest.csv` | Capturas de pantalla de la simulación. La carpeta y su `manifest.csv` se siguen creando en cada corrida al abrir la ventana, pero quedan vacíos porque el turno no se ejecuta |
| `corridas/<etiqueta>/schedules_por_reprogramacion/` | Solo contiene `schedule_01.csv`, `agendas_agentes_01.csv` e `indice.csv` (el plan inicial); los siguientes números eran para las reprogramaciones |
| `verificar_schedules.py`, `exportar_excel_schedules.py`, `analisis_variabilidad_planes.py`, `grafico_toneladas_acumuladas.py` | Herramientas de análisis anteriores |
| `preprocesar_registros.py` | **Obsoleto**: preparaba los episodios de reprogramación para el LLM |
| `FLUJO_EPISODIOS_LLM.md` | **Obsoleto**: describía el flujo por episodios de reprogramación |
| `DUDAS_PROFESOR.md` | **Obsoleto**: dudas de la etapa de reprogramación |
