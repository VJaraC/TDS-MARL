# TDS-MARL — Trabajo pendiente y hallazgos

Documento de traspaso. Recoge lo que quedó sin resolver y lo que se fue
descubriendo durante la corrección del simulador, para que quien continúe no
tenga que volver a encontrarlo.

Autor del estado actual: Piero Tandaypan.
Última actualización: 2026-09-08.

---

## Cómo compilar y correr

```bash
cd TDS-MARL1
CP="C:/Users/piero/Documents/JADE-all-4.6.0/JADE-bin-4.6.0/jade/lib/jade.jar;C:/Users/piero/libs/jgraphx.jar;C:/Users/piero/libs/jgrapht-core-1.0.1.jar"
javac --release 8 -encoding UTF-8 -cp "$CP" -d bin src/*.java
```

**`--release 8` es obligatorio.** El `javac` del PATH es v25 pero el runtime es
Java 1.8. Sin esa opción el bytecode sale v69 y al correr da
`UnsupportedClassVersionError`.

Los parámetros de cada corrida están en `ParametrosSimulador.java` y
`ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA`. Son `static final`, así que hay
que **recompilar entre corridas**.

### Protocolo de corridas (importante)

- **Una corrida a la vez**, con la máquina sin otra carga. Los agentes negocian
  en tiempo real, así que la contención de CPU se mete como variable oculta.
- **No tocar el slider de velocidad.** Ya no hace nada: la velocidad sale de
  `ParametrosSimulador.VELOCIDAD_REPRODUCCION`.
- **Esperar el arranque automático.** El botón PLAY queda bloqueado hasta que
  las 10 palas declaran su turno lleno (unos 26 min en el escenario mediano).
- **Verificar en `kpis_corridas.csv`**: `motivo_arranque` debe decir
  `negociacion_completa`, `palas_completas` = `palas_totales`, y
  `cierres_valvula` = 0. Si no, la corrida no es comparable y hay que repetirla.

---

## 1. Errores conocidos sin corregir

### 1.1 `RB2_PUMA2_EST: ERROR de solapamiento` durante la negociación
Aparece decenas de veces en cada corrida, siempre en ese punto de descarga. Es
un `UnloadingPointAgent` rechazando solapamientos. **No se investigó.** Es
preexistente al trabajo de esta tesis. Conviene revisarlo: puede estar
rechazando asignaciones válidas y degradando el plan inicial.

### 1.2 Diez de cincuenta camiones nunca reciben trabajo
En el escenario mediano participan consistentemente ~40 de los 50 camiones del
OWL. Los otros nunca ganan un CFP y terminan el turno sin una sola operación.
No se determinó si es sobredimensionamiento de la flota o un sesgo del
protocolo de negociación. Afecta a `num_camiones` frente a `flota_total` en los
KPIs, y hay que tenerlo presente al interpretar cualquier métrica per cápita.

### 1.3 Sobre-agendamiento de palas en el plan
`ShovelAgent.AgregaGanador` incorpora el slot ganado **sin verificar solape ni
capacidad**. El plan que produce el CNP contiene solapamientos de CARGA en una
misma pala. Se midió y resultó ser estructural del protocolo, no introducido por
la reprogramación: el baseline presentaba más pares solapados que Partial
Repair. No se corrigió porque no resultó ser un factor material, pero está ahí.

### 1.4 Asimetría en la protección de ciclo
`PartialRepair` extiende el corte hasta el fin de la descarga cuando el camión
está a mitad de un ciclo comprometido. `CascadingRightShift` no tiene el
equivalente. Físicamente da igual —desplazar no interrumpe nada— pero es una
inconsistencia de diseño.

### 1.5 `solapes_pala.csv` agrupa por ubicación, no por pala
Una ubicación aloja hasta seis palas, así que su columna
`max_camiones_simultaneos` **sobreestima la infactibilidad**: cuatro camiones
cargando a la vez en un punto con seis palas es perfectamente factible. Quedó
superado por `utilizacion_palas.csv`, que mide por pala física, pero el archivo
viejo sigue generándose y puede inducir a error.

---

## 2. Limitaciones del modelo

### 2.1 Las perturbaciones son deterministas y únicas
`GestorEventos` carga tres eventos de clima (a los 60, 150 y 240 min) y
`InicializadorSimulador` tres averías fijas: un camión a 1:30, una pala a 2:00 y
un punto de descarga a 3:20. **Iguales en todas las corridas.**

**Cambio del 2026-10-15:** la pala y el punto de descarga ya no son fijos (antes
PA01 y el chancador CS03, que en los tres escenarios no tenían trabajo a esa
hora y no atrasaban a nadie). Ahora se eligen al capturar el plan inicial: la
pala con más cargas planificadas durante su falla y el punto de descarga con
más descargas durante la suya (`InicializadorSimulador.cargarAveriasSegunPlan`).
Con el plan inicial único, los cuatro métodos reciben las mismas fallas.
Grande: PA20 y UB1_TRANQUEESPA; mediano: PA04 y UB1_TRANQUEESPA; pequeño: PA03 y
UB1_TRANQUEBERM.

Ventaja: comparabilidad perfecta entre métodos. Limitación: es **un escenario,
no una distribución**. No se puede afirmar que un método sea mejor "bajo
incertidumbre", solo bajo esta perturbación concreta.

Extensión natural: un modelo estocástico de fallas con MTBF/MTTR por tipo de
equipo, y un factor de timing de las perturbaciones como dimensión experimental
declarada (temprano / medio / tardío) aplicada por igual a los cuatro métodos.
**Nunca mover los eventos solo para un método: invalida toda comparación.**

### 2.2 Tasa de fallas muy baja
Una avería de camión en 50 durante 6 horas está muy por debajo de lo que
ocurre en una faena real.

### 2.3 La variabilidad ±3% aplica solo a viajes
`VARIACION_VELOCIDAD_CAMION` multiplica los tiempos de traslado. Los tiempos de
carga y descarga son fijos, tomados del plan. En la realidad varían con la
pericia del operador y las características del material.

### 2.4 Ejecución greedy: el cronograma no es un horario vinculante
`CalculadorScheduleReal.calcularInicioRealSlot` respeta la hora planificada
**solo en el primer slot del turno**. Del segundo en adelante el camión opera en
cuanto puede.

Consecuencia importante: **el right-shift no puede alterar el resultado
físico.** Desplazar horas planificadas reduce el retraso medido
(`retraso = finReal − horaFin planificada`) sin mover un solo camión. Los únicos
métodos que cambian la operación son los que **reasignan recursos**: reparación
parcial y regeneración completa.

Esto no es un error: es coherente con la operación real, donde un camión no
espera un minuto programado. Pero significa que si alguien quiere que el
right-shift tenga efecto físico, debe cambiar el modelo de ejecución para que el
cronograma sea un horario de cumplimiento obligatorio.

### 2.5 La latencia de decisión es constante
`EpisodioRescheduling.LATENCIA_DECISION_MS` = 30 s simulados, igual para todos
los métodos. En rigor debería crecer con la cantidad de camiones renegociados:
redespachar cuarenta equipos comunica más que redespachar uno. Se dejó constante
para no introducir un parámetro adicional sin fundamento empírico.

### 2.6 El congelamiento del reloj es parcial
El cursor se congela durante el episodio de reprogramación, pero al cerrarse el
episodio **las palas con ventana de re-oferta abierta siguen negociando mientras
el cursor avanza**. Queda un acoplamiento residual con el tiempo real en esa
ventana. Es el primer sospechoso si aparece dependencia de la velocidad.

### 2.7 `DEADLINE_MS` y el ticker de las palas siguen en tiempo real
Es inherente a JADE, que solo despierta behaviours en tiempo de reloj. Se acotó
el efecto con la compuerta `GRANULARIDAD_DETECCION_SIM_MS`, válida para
velocidades hasta 120×. Por encima de eso la granularidad de detección vuelve a
depender de la velocidad.

### 2.8 Q-learning desactivado
`ParametrosConfiguracion.LEARNING_ENABLED = "false"` en todas las corridas de
esta tesis. El MAS base tiene capacidad de aprendizaje por refuerzo sin usar.

---

## 3. Problemas de reproducibilidad que quedan

### 3.1 El plan inicial varía ~21% entre corridas
Medido con el criterio de arranque exacto (las 10 palas con turno lleno):
1116, 1120 y 1360 slots en tres corridas idénticas de `NINGUNO`. Coeficiente de
variación del material: ~11%.

Causa: JADE ejecuta los agentes en hilos concurrentes, así que el orden de
llegada de las postulaciones cambia en cada ejecución y con él quién gana cada
CFP. La semilla fija las velocidades de los camiones, no el orden de los hilos.

Consecuencia práctica: **solo se pueden afirmar diferencias grandes.** Un efecto
del 10% queda dentro del ruido con pocas réplicas. Para afirmar equivalencia
entre dos métodos hace falta más muestra que para afirmar diferencia.

Solución de fondo: **plan base fijo**. Negociar una vez, guardarlo y cargarlo en
todas las corridas, de modo que la comparación quede pareada
(*common random numbers*). La plomería existe a medias: `Schedule.agregarSlot`
ya persiste el plan a `<prefijo>schedules.csv` y `LectorCSVSchedules` ya lo lee.
Falta un modo de arranque que reconstruya también las agendas internas de palas
y puntos de descarga sin pasar por el CNP. **Riesgo:** si esa reconstrucción
queda distinta del estado que deja el protocolo, las renegociaciones posteriores
se comportan distinto. Hay que validarlo con cuidado.

**Implementado el 2026-10-14** (pedido del profesor): `PlanInicialFijo.java`,
`GuardarPlanInicial.java` y `ParametrosSimulador.USAR_PLAN_INICIAL_FIJO`. Cada
agente carga su agenda de `planes_iniciales/plan_inicial_<flota>.csv` en su
`setup()`. Una corrida que negocia guarda sola su plan como plan único del
escenario si quedó limpio y el escenario no tenía uno; cada corrida deja
`corridas/<etiqueta>/plan_inicial_verificacion.txt`. Para tomar el plan de una
corrida anterior: `java -cp bin GuardarPlanInicial <etiqueta> [--reemplazar]`.
Plan del escenario grande: el de `grande_V7_COMPLETE_v2`. Validado: una
corrida con el plan cargado ejecuta igual, tarea por tarea, que la corrida
original de ese plan hasta su primera reprogramación.

### 3.2 La negociación inicial toma ~26 minutos y el 90% es improductivo
Medido en mediano: construir las ~1360 slots del turno toma unos 26 minutos de
reloj de pared. Entre dos muestras separadas 857 segundos se agregaron 548
slots, es decir cada pala concreta un ciclo cada ~62 s cuando una ronda dura ~6 s.

Causa: una pala sin postulantes **avanza su oferta 60 segundos y reintenta**, sin
agregar nada al plan. Y la ventana de re-oferta se fusiona como el **rango**
`[mínimo inicio, máximo fin]` de los huecos, no como su **unión**, así que la
pala recorre también tramos donde no hay hueco real.

Es el mayor costo de tiempo del proyecto: 28 corridas son ~14 horas, y la mayor
parte es negociación. Reducirlo exige tocar la lógica de negociación, lo que
quedó fuera del alcance de esta tesis por decisión de diseño (no alterar el
comportamiento negociador para preservar la comparabilidad con los resultados
previos y las figuras del informe).

### 3.3 Sin automatización del lote
Los parámetros son `static final` (hay que recompilar por corrida) y la
aplicación **nunca termina sola**: `autoStopYExportar` exporta y pausa, pero la
ventana queda abierta. Como JADE ocupa el puerto 1099, no se puede lanzar la
corrida siguiente hasta cerrar la anterior a mano.

Para automatizar hacen falta tres cosas:
1. Leer los parámetros de propiedades del sistema (`-Dtds.escenario=...`) con
   los valores actuales como respaldo, para no recompilar.
2. Un `System.exit(0)` al final de `autoStopYExportar`, activado por bandera,
   para liberar el puerto.
3. Un script que recorra las configuraciones con timeout por corrida.

No se implementó para no tocar la configuración central antes del lote final.

---

## 4. Líneas de investigación abiertas

Ordenadas por interés, no por dificultad.

### 4.1 Re-ruteo a pala ociosa (la más importante)
**Es el hallazgo central del trabajo.** El cuello de botella no es la capacidad
agregada de un punto de carga sino el **desbalance entre palas hermanas**: se
midió una ubicación con PA09 al 43,6% de las cargas y PA03 al 4,3%, cuando el
reparto equilibrado sería 25%. Camiones haciendo cola frente a una pala mientras
otra del mismo punto está desocupada.

Icarte lo nombra en su propio trabajo como la ineficiencia central del enfoque, y
el sesgo tiene respaldo teórico: los criterios que minimizan el tiempo del camión
favorecen las palas cercanas y subatienden las lejanas (Subtil et al.;
Munirathinam y Yingling).

El mecanismo a explorar: permitir que un camión en cola se traslade a una pala
ociosa del mismo punto. El profesor guía lo ubicó explícitamente en trabajo
futuro. `utilizacion_palas.csv` ya entrega la medición necesaria para evaluarlo.

### 4.2 Reparación parcial con alcance acotado
El AOR canónico **degenera en regeneración completa en este dominio**: con 5
disparos el cierre transitivo de operaciones afectadas alcanzó a los 40 camiones
de la flota, liberando el 86% del plan. El grafo de conflictos del despacho
camión-pala está tan densamente conectado que "operaciones afectadas" ≈ "todas
las operaciones".

Eso explica por qué la versión angosta original —liberar solo al camión
disparador— rendía mejor: no era una implementación incompleta de Vieira, era la
única que evita el colapso.

Dirección natural: un **alcance acotado** del cierre (profundidad máxima de
propagación, o un presupuesto de camiones a liberar) que interpole entre el
alcance angosto y el canónico, y medir dónde está el óptimo.

### 4.3 Reparación sensible al horizonte restante
Se midió que **la reparabilidad decae con el avance del turno**: 40% de camiones
huérfanos para perturbaciones a las 2 horas, 66% a las 4 horas y 100% a las 5
horas de un turno de 6. La causa es física: liberar consume horizonte, y cerca
del final no queda holgura donde reabsorber el trabajo liberado.

Se verificó que no es artefacto de medición: cuadruplicar el presupuesto de
negociación (15 s → 60 s de estancamiento) dejó el conteo absoluto de huérfanos
idéntico en 40.

Dirección: un método que se vuelva **más conservador a medida que avanza el
turno**, porque el costo de liberar crece mientras el beneficio cae. Ninguno de
los tres métodos clásicos contempla la posición dentro del horizonte.

### 4.4 Match factor como métrica de balance
El *match factor* (Burt y Caccetta) es la métrica estándar de balance
camión-pala en la literatura de despacho minero. No se implementó. Sería la
forma canónica de reportar el desbalance de 4.1 y permitiría comparar contra
otros trabajos.

### 4.5 Intercambio bidireccional de camiones entre palas
Evaluado y descartado por el profesor guía como fuera del alcance de esta tesis.
Queda anotado.

### 4.6 Sincronización conservadora completa del reloj
Lo correcto de manual sería que el reloj de simulación no avanzara mientras
**cualquier** agente esté deliberando, no solo durante un episodio de
reprogramación (ver 2.6). El reloj está centralizado en una sola línea
—`VentanaSimulacion` línea ~207— así que es más abordable de lo que parece.

---

## 5. Deuda menor

- `printStackTrace` y `System.out` en el código base heredado. Se decidió **no
  tocarlos** para no alterar el comportamiento heredado.
- Referencia 43 del informe (Abumaizar y Svestka, 1997) tiene el rango de
  páginas y el DOI **sin verificar**.
- Seis referencias del informe quedaron marcadas `[Completar…]`.
- Existen dos OWL para el escenario grande, `datosShovelTrucksGrande.owl` y
  `datosShovelTrucksGrande_original.owl`. **Confirmar cuál tiene las velocidades
  homogéneas 25/20** antes de usarlo, o las corridas no serán comparables con
  las de mediano.
- El slider de velocidad de la ventana quedó como adorno: ya no controla nada.
  Conviene deshabilitarlo o quitarlo para que no confunda.

---

## 6. Qué se corrigió en esta etapa (contexto para no re-hacerlo)

Resumen de lo que **ya está arreglado**, para que no se vuelva a tocar:

- **Reloj:** el cursor se congela durante los episodios de reprogramación y se
  imputa una latencia de decisión declarada. Antes, negociar consumía tiempo de
  mina (8,3 min por ronda CNP a 100×) y penalizaba a los métodos que más
  renegocian.
- **Detección:** granularidad expresada en tiempo de mina (2 min), constante
  para cualquier velocidad hasta 120×.
- **Huérfano:** definición estructural (el CNP dejó de poder colocarlo) en lugar
  de un timeout de reloj de pared.
- **Cooldown de regeneración:** en tiempo de mina (30 min) en lugar de 120 s
  reales, que a 100× equivalían a 3,3 horas simuladas.
- **Recálculo de la realidad:** se dispara por firma de contenido del plan y no
  por conteo de slots. Antes, liberar 8 slots y renegociar 8 dejaba la realidad
  desactualizada.
- **Arranque:** automático al completarse la negociación inicial, con tope de
  tiempo como único respaldo. La ventana de "quietud del plan" se probó a 10 s,
  90 s y 5 min y **falló en las tres**: el plan se queda quieto minutos mientras
  la negociación sigue trabajando.
- **Velocidad:** fija por código y registrada en cada CSV.
- **Partial Repair:** ahora es el AOR canónico, con cierre transitivo de
  operaciones afectadas directa e indirectamente.
- **Marca de re-disparo:** cada camión registra **su propio** retraso al ser
  reparado, no el del disparador.
- **Instrumentación nueva:** `estabilidad.csv` (familia de estabilidad de
  Vieira) y `utilizacion_palas.csv` (utilización y desbalance por pala física).
