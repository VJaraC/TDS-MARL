# Dudas para el profesor

Preguntas para la próxima reunión, ordenadas por cuánto cambian lo que hay que
construir. Cada una trae el contexto mínimo, por qué importa y, cuando la hay,
una propuesta para discutir.

Fecha: 16 de noviembre de 2026. Detalle del flujo y de los datos en
[FLUJO_EPISODIOS_LLM.md](FLUJO_EPISODIOS_LLM.md).

---

## Resumen para abrir la reunión

- Ya existe un preprocesador que resume los registros de una corrida en un
  texto de unos 9.000 caracteres para el LLM (objetivo específico 2, primera
  parte).
- Al analizar los registros apareció un problema de fondo: cada reparación
  parcial tarda unos 14 minutos reales, y casi todo ese tiempo son preguntas
  de las palas que ningún camión puede aceptar.
- Eso obliga a decidir tres cosas antes de seguir: contra qué sistema se
  compara el LLM, cuándo actúa y qué puede recomendar.

---

## A. Las que deciden qué se construye

### 1. ¿Se corrige el barrido de re-oferta antes de evaluar al LLM?

**Contexto.** En cada reparación parcial, las 5 palas recorren todo lo que
queda del turno ofreciendo horas de a 60 segundos, aunque solo una tenga
huecos que llenar. En la corrida de ejemplo, 3.096 de 3.170 negociaciones
terminaron sin ninguna propuesta, y 3.022 de esos fallos vinieron de las 4
palas que no tenían nada que reparar. Además, el camión que va a rechazar
espera casi hasta el plazo para contestar.

**Cómo funciona el barrido** (`ShovelAgent.java`, `siguienteOfertaBarrido` y
`siguienteInstanteLibre`):

1. La pala parte desde la hora actual de la mina.
2. Busca su próximo instante libre: si a esa hora ya tiene una carga
   agendada, salta al final de esa carga.
3. Ofrece esa hora a todos los camiones.
4. Si nadie puede, suma 60 segundos y vuelve al paso 2.
5. Repite hasta pasar el fin del turno.

La pala se salta sus propias horas ocupadas, pero no sabe cuándo se desocupan
los camiones: pregunta a ciegas y, si todos están ocupados, prueba un minuto
después. Las palas con pocas cargas en el plan (PA01, PA02, PA04 y PA05, con 4
o 5 cargas en todo el turno) tienen casi todo el turno libre y lo recorren
entero: unas 200 preguntas cada una por episodio.

**Por qué probablemente quedó así.** Es una lectura del código y de sus
comentarios, no una crítica al trabajo anterior:

- No afecta los resultados de la mina: durante el barrido el reloj simulado
  está congelado, así que toneladas, esperas y atrasos salen iguales.
- Se priorizó que fuera correcto: el barrido se agregó para evitar camiones
  sin tareas y huecos liberados que nadie volvía a ofrecer.
- Reutiliza el mecanismo de la negociación inicial, donde avanzar hora tras
  hora es natural.
- En un sistema multiagente cada agente ve solo su agenda; preguntar es la
  forma directa de averiguar quién puede.

**Por qué ahora sí importa.** Esta tesis mide cosas que antes no hacía falta
medir: la carga de comunicación entre agentes y que los tiempos sean
compatibles con la decisión operativa (H1). Lo que era un detalle de
implementación pasa a ser parte del resultado.

**Cómo plantearlo.** Como hallazgo del análisis del sistema existente
(objetivo específico 1): "al medir comunicación y tiempos, el 97,7 % de las
negociaciones de una reparación no produce ninguna asignación".

**Qué está en juego.** Si el barrido se deja así, bajar `DEADLINE_MS` da una
mejora grande y fácil, pero alguien puede objetar que el LLM solo compensa un
defecto que se arregla con un cambio simple de código. Si se corrige, cambia
la línea base y cambia qué puede aportar el LLM.

**Mejoras posibles, de más simple a más profunda** (ninguna está probada):

| Cambio | Qué ahorraría |
|---|---|
| Que solo barra la pala afectada | Cerca del 95 % de las preguntas sin respuesta |
| Que el camión que rechaza conteste de inmediato, sin esperar al plazo | Cada pregunta fallida pasaría de unos 4 s a milisegundos |
| No preguntar a los 2 camiones sin ruta | Menos mensajes |
| Que el camión que rechaza diga desde cuándo está libre y la pala salte ahí | Casi todos los intentos vacíos |

**Propuesta.** Corregir al menos "solo barre la pala afectada" y medir al LLM
sobre ese sistema. Alternativa: dejarlo como está y declararlo como limitación.

**Preguntas concretas.**

- ¿El barrido en todas las palas es el comportamiento esperado, o un efecto
  no buscado?
- ¿Se corrige antes de evaluar, o se declara?
- Si se corrige, ¿la versión corregida pasa a ser la configuración base de H1?

### 2. ¿En qué momento actúa el LLM?

**Contexto.** La metodología dice que hay que definir "el momento del ciclo de
despacho en que se solicitan" las recomendaciones. Hay dos lecturas:

- **En cada episodio de reprogramación:** el reloj de la mina ya se congela en
  ese momento; ahí se consulta al LLM y se aplican los parámetros a esa
  renegociación.
- **Entre turnos:** el LLM lee el registro del turno anterior y fija los
  parámetros del siguiente.

**Por qué importa.** La hipótesis H1 habla de "tiempos de generación del plan
compatibles con la toma de decisiones operativa" y el tiempo de respuesta del
LLM es una métrica. Eso solo tiene peso si el LLM actúa dentro del turno. Pero
esa opción exige modificar los agentes.

**Propuesta.** En cada episodio de reprogramación.

### 3. ¿Qué cuenta como "parámetro de negociación"?

**Contexto.** En el código hay tres grupos de parámetros candidatos:

| Grupo | Parámetros | Qué controlan |
|---|---|---|
| A | Cinco pesos con que la pala elige al camión ganador | A quién se asigna cada carga |
| B | `DEADLINE_MS` y el paso del barrido | Cuánto dura y cuesta negociar |
| C | Umbrales de disparo (25, 15 y 45 min; 25 % de la flota) | Cuándo se repara |

**Por qué importa.** Los grupos A y B son claramente de negociación. El grupo C
decide cuándo se negocia, no cómo, y puede quedar fuera del título. Pero en
reparación parcial casi nunca hay más de un camión entre los que elegir (21 de
3.170 negociaciones), así que ahí los pesos influyen poco y el grupo C sería
la palanca principal.

**Pregunta concreta.** ¿Los umbrales de disparo entran en el alcance?

### 4. ¿Hay que demostrar primero que los parámetros importan?

**Contexto.** Todas las corridas disponibles usan los mismos valores
(`DEADLINE_MS` = 5.000, pesos y umbrales por defecto). No hay evidencia de
cuánto cambian los resultados al moverlos.

**Por qué importa.** Si cambiar los parámetros no altera toneladas ni esperas,
ningún recomendador puede mejorar el sistema, sea un LLM u otra cosa.

**Propuesta.** Antes de implementar el componente, un análisis de sensibilidad:
el mismo escenario con dos o tres valores de cada parámetro candidato. ¿Le
parece un paso necesario, y cabe como parte del objetivo 3?

---

## B. Las del diseño experimental

### 5. ¿Se pueden acortar las corridas?

**Contexto.** Una corrida sin reprogramación dura 5 minutos. Una con
reparación parcial, en el escenario Pequeño, apunta a varias horas: en 52
minutos reales la mina avanzó 10 minutos. No hay ninguna corrida completa de
`PARTIAL_REPAIR` ni de `COMPLETE_REGENERATION` con la configuración actual.

**Por qué importa.** La evaluación pide varias repeticiones, por método y por
tamaño de flota, con y sin LLM. A este ritmo no es viable.

**Opciones.** Corregir el barrido (pregunta 1), o bajar `DEADLINE_MS` y los
tickers por igual en ambos brazos, o acotar la evaluación a menos escenarios.

### 6. ¿Qué escenarios, métodos y repeticiones se exigen?

- ¿Los tres tamaños de flota, o basta Pequeño y Mediano?
- ¿Los dos métodos que negocian (`PARTIAL_REPAIR` y `COMPLETE_REGENERATION`)?
- ¿Cuántas repeticiones por configuración?
- ¿Qué prueba estadística espera para contrastar H1?

### 7. ¿Cómo se evita que el LLM ya conozca el escenario?

**Contexto.** Las corridas son casi deterministas: misma semilla y mismos
eventos dan el mismo resultado. Si al LLM se le entrega el registro de una
corrida anterior del mismo escenario, ya sabe lo que va a pasar.

**Pregunta concreta.** ¿El historial que recibe el LLM debe venir de semillas
o eventos distintos a los evaluados? ¿O se acepta y se declara como
aprendizaje entre turnos?

### 8. ¿Contra qué se compara, además de la configuración base?

**Contexto.** La hipótesis compara "con LLM" contra "configuración base".

**Pregunta concreta.** ¿Hace falta además una comparación contra una regla
simple o contra la mejor configuración fija encontrada en el análisis de
sensibilidad? Sin eso no se distingue el aporte del LLM del aporte de
simplemente ajustar los parámetros.

### 9. ¿Un resultado negativo es aceptable?

Si el LLM no mejora un sistema ya bien configurado, ¿basta con reportarlo y
caracterizar en qué condiciones sí ayudaría?

---

## C. Las de alcance

### 10. ¿La elección del método de reprogramación queda como trabajo futuro?

Hoy el método se fija antes de la corrida. Que el LLM lo elija según la
situación es una idea natural, pero no es un parámetro de negociación y cambia
el objetivo general. Propuesta: mencionarlo en trabajo futuro.

### 11. ¿Se corrige el escenario Pequeño?

De los 15 camiones, 2 nunca trabajan: parten en puntos del mapa sin conexión
(`#RF11` y `#BOT`) y rechazan todas las ofertas por "ruta inexistente". ¿Se
corrige el archivo de flota o se declara que la flota efectiva es de 13?

### 12. ¿Se mantiene el plan inicial fijo?

Con plan inicial fijo no se negocia al comenzar el turno, así que el LLM solo
puede actuar en las reparaciones. ¿Interesa evaluar también su efecto sobre la
negociación del plan inicial, o queda fuera?

### 13. ¿Cuánto se puede modificar el sistema multiagente?

Para que el LLM actúe dentro del turno hay que: permitir cambiar parámetros
sin recompilar, agregar un punto de consulta al inicio de cada episodio y,
según la pregunta 1, corregir el barrido. ¿Hay restricciones sobre qué partes
del código se pueden tocar?

---

## D. Las prácticas

### 14. ¿Qué modelo y con qué configuración?

Hay que documentarlo para reproducibilidad. ¿Hay preferencia o restricción
(modelo local o por API, costo, temperatura fija)?

### 15. ¿El tiempo de respuesta del LLM se suma al tiempo de la corrida?

Durante una reparación el reloj de la mina está congelado. ¿La demora del LLM
se reporta aparte, como métrica propia, o debe traducirse a tiempo de mina?

### 16. ¿Qué entregable espera para la próxima revisión?

Opciones: el diseño de la interacción (prompt y formato de la recomendación),
el análisis de sensibilidad, o una primera corrida con el LLM conectado.

---

## Datos para tener a mano

| Dato | Valor | Fuente |
|---|---|---|
| Duración de una corrida sin reprogramación | 5 min reales | 10 corridas `NINGUNO` |
| Duración de un episodio de reparación parcial | cerca de 14 min reales | `pequeno_PARTIAL_REPAIR_r1` |
| Negociaciones sin ninguna propuesta | 3.096 de 3.170 (97,7 %) | `pequeno_PARTIAL_REPAIR_r1` |
| Negociaciones con más de una propuesta | 21 de 3.170 | `pequeno_PARTIAL_REPAIR_r1` |
| Fallos de palas sin nada que reparar | 3.022 de 3.096 | `pequeno_PARTIAL_REPAIR_r1` |
| Duración de cada negociación | 3,4 a 4,0 s con `DEADLINE_MS` = 5.000 | `pequeno_PARTIAL_REPAIR_r1` |
| Cruces de umbral en un turno sin reparar | 34 | corridas `NINGUNO` |
| Camiones que trabajan en Pequeño | 13 de 15 | plan inicial y registros |
| Tamaño del resumen para el LLM | cerca de 9.000 caracteres | `preprocesar_registros.py` |

Las dos corridas de reparación parcial disponibles se interrumpieron antes de
terminar el turno, así que no tienen KPIs finales: sirven para describir el
comportamiento de los episodios, no para comparar desempeño.
