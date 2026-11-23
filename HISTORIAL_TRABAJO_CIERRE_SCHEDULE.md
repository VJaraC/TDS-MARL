# Historial del trabajo: rama `cierre-schedule-inicial`

Registro de todo lo hecho entre el 17 y el 23 de noviembre de 2026: qué se
pidió, qué se decidió, qué se cambió (con las líneas exactas en los archivos
originales), qué comandos se usan y qué resultados dieron las pruebas.

- **Repositorio:** `~/Documentos/TDS-MARL`.
- **Rama:** `cierre-schedule-inicial`, creada desde `preprocesador-registros`.
- **Commit original de referencia:** `cc87b39`, anterior a todo este trabajo.
- **Commits de la rama:**

| Commit | Contenido |
|---|---|
| `5d09ee1` | Cierre al generar el schedule inicial y estadísticas del plan |
| `40a84b1` | Lotes sin recompilar, ventana oculta, interfaz y pruebas |

Documentos relacionados:

- [DOCUMENTACION_REGISTROS.md](DOCUMENTACION_REGISTROS.md): qué significa cada
  archivo y campo de resultados.
- [COMO_CORRER.md](COMO_CORRER.md): cómo lanzar corridas y lotes.

---

## 1. Contexto y regla principal

El trabajo de título estudia **solo la negociación inicial** del sistema
multiagente (JADE 4.6.0, Java compilado con `--release 8`) y el **schedule**
que genera. No interesan la ejecución del turno, los atrasos ni la
reprogramación.

**Regla que se respetó en todo momento:** no cambiar la lógica de los agentes
ni de la negociación o el scheduling. Se comprobó comparando con el commit
original `cc87b39`. Los siguientes **39 archivos `.java` siguen idénticos**:

- **Agentes:** `ShovelAgent`, `TruckAgent`, `UnloadingPointAgent`,
  `ReschedulingAgent`.
- **Agenda y mensajes:** `Schedule`, `Slot`, los `Documento*`,
  `ReservasDescarga`.
- **Registros:** `CsvLogger`, `CsvSchedules`, `LectorCSVSchedules`,
  `RegistroSimulacion`, `RegistroAgendasAgentes`.
- **Plan:** `PlanBase`, `PlanInicialFijo`.
- **Ejecución y reprogramación:** `CalculadorScheduleReal`,
  `CascadingRightShift`, `PartialRepair`, `EpisodioRescheduling`,
  `GestorEventos`, `Utilidades`, etc.

Se puede verificar en cualquier momento con
`python3 pruebas/verificar_codigo_original.py` (sección 7).

---

## 2. Etapa 1: la corrida termina al generar el schedule inicial

**Pedido.** Que cada corrida termine apenas se genera el plan inicial; que deje
registros entendibles y estadísticas del plan; que apague JADE y ofrezca abrir
la carpeta de resultados.

**Forma de trabajo.** Primero un plan, que el usuario aprobó con 12 respuestas;
después la implementación.

### 2.1 Decisiones del usuario

| # | Tema | Decisión |
|---|---|---|
| 1 | Mensaje a varios destinatarios | Cuenta cada entrega (un CFP a 15 camiones = 15). Se informa además `envios` (1 por fila SEND) |
| 2 | Cargas que terminan después de las 6 h | Se informan el total y lo de dentro del turno. El tiempo ocupado de las palas se recorta a [0, 6 h]. Se agrega `toneladas_descargadas_en_turno`, con el mismo criterio de `plan_inicial_verificacion.txt` |
| 3 | `minutos_solapados` | Suma de las intersecciones de cada par de cargas |
| 4 | `negociaciones_sin_propuestas` | Por motivo de cierre; se informa aparte "con 0 propuestas recibidas" |
| 5 | Ganador | Se deja tal cual y se agrega la columna `acepto`; `negociaciones_con_ganador` = las que aceptaron |
| 6 | `conversationId` repetidos | Una fila por cada fila del CSV, sin deduplicar; se informa como advertencia |
| 7 | Formato de fechas | `yyyy-MM-dd HH:mm:ss.SSS`, más `duracion_ms` |
| 8 | `resumen_corridas.csv` | En `corridas/`, ignorado por git |
| 9 | Fin por el tope de tiempo | Se generan los archivos igual, pero la corrida queda **NO VÁLIDA** |
| 10 | Interruptor | `TERMINAR_TRAS_PLAN_INICIAL = true` |
| 11 | Etiqueta repetida | Se verifica al iniciar: si `corridas/<etiqueta>/` existe, la corrida no arranca |
| 12 | Estado de git | Se registra `git_sucio` en `configuracion.json` |
| — | Duración | `duracion_negociacion_inicial_ms` = negociación pura (último fin − primer inicio). Se informan aparte `espera_copias_ms` y `duracion_hasta_captura_ms` |
| — | Versión de Java | Se registra en `configuracion.json` |

### 2.2 Qué se implementó

- **Punto de enganche:** `VentanaSimulacion.evaluarArranqueAutomatico`, justo
  después de `PlanBase.capturar(...)`, que a su vez llama a
  `GuardarPlanInicial.alCapturar`.
- **Qué pasa en lugar de simular el turno:** el reloj queda en 0, por lo que
  todas las filas mantienen `t_sim_ms = -1`. Luego se llama a
  `CierrePlanInicial.cerrar(...)`, que hace tres cosas:
  1. **Genera los archivos** de resultados con `EstadisticasPlanInicial`.
  2. **Muestra un diálogo** con el resumen y los botones "Abrir carpeta de
     resultados" y "Cerrar". En una corrida NO VÁLIDA, el diálogo lo dice.
  3. **Apaga JADE:** `contenedorPrincipal.kill()`, luego
     `jade.core.Runtime.instance().shutDown()` y `System.exit(0)`. Si se
     cuelga, la salida se fuerza a los 10 s.
- **Archivos que deja en `corridas/<etiqueta>/`:**
  - `configuracion.json`;
  - `negociacion_inicial.csv`;
  - `resumen_plan.json` y `resumen_plan.txt`;
  - una fila nueva en `corridas/resumen_corridas.csv`;
  - los que ya existían: `plan_inicial.csv` y `plan_inicial_verificacion.txt`.
- **Pesos de selección de la pala.** Se copiaron como constantes en
  `EstadisticasPlanInicial`, con un `TODO` que recuerda sincronizarlos a mano
  con `ShovelAgent`. El código de la pala no se tocó.
- **Encabezado de `negotiations.csv`.** Estaba invertido y se corrigió: ahora
  dice `proposesRecibidas, refusesRecibidas`, en el orden real.
  - Su único lector, `preprocesar_registros.py`, lee las columnas por posición,
    así que no se rompe.
  - Al script se le agregó solo un comentario que dice que está **obsoleto**.

### 2.3 Hallazgo: los destinatarios de los camiones no se registran

`TruckAgent.getReceiverString` (`TruckAgent.java:375`) hace
`receiverList.concat(...)` sin guardar el resultado, así que la columna
`receivers` de los `SEND` de los camiones queda siempre vacía. No se corrigió,
porque está en el código del agente.

**Cómo se compensa en el conteo de mensajes** (criterio adaptado y probado):

- **SEND de un camión:** cuenta 1 entrega, porque el camión siempre envía a un
  solo destinatario.
- **SEND de una pala sin receptores:** cuenta 0. Es un REJECT enviado a una
  lista vacía, que no llega a nadie.
- **AGREE de un punto de descarga:** se cuenta por su fila "AGREE descarga",
  porque el punto de descarga no registra SEND.
- **Control cruzado:** las filas RECEIVE, más las filas "AGREE descarga" e
  "INFORM recibido", deben dar el mismo total. En las corridas completas la
  diferencia fue 0.

### 2.4 Pruebas de la etapa 1 (escenario pequeño, `USAR_PLAN_INICIAL_FIJO = false`, `NINGUNO`)

| Corrida | Negociación (s) | Filas `messages.csv` | Filas `negotiations.csv` | Slots de camión | Cargas |
|---|---|---|---|---|---|
| antes r1 (código original) | 1458,9 | 81.086 | 1.587 | 328 | 82 |
| antes r2 (código original) | 1414,8 | 76.641 | 1.494 | 380 | 95 |
| después r1 | 1463,2 | 81.794 | 1.599 | 348 | 87 |
| después r2 | 1450,9 | 75.798 | 1.479 | 348 | 87 |

- **Puerto 1099:** dos corridas seguidas funcionan; el puerto queda libre.
  También se probaron los botones del diálogo, el caso NO VÁLIDA (con un tope
  de 60 s) y la etiqueta repetida.
- **Revisión manual:** 87 cargas y 27.998 t contadas a mano en
  `plan_inicial.csv` coinciden con `resumen_plan.json`.
- **Archivos de reprogramación:** con `NINGUNO` no se crean `cascadas.csv`,
  `liberaciones.csv`, etc. Solo se sigue creando `snapshots/` con un
  `manifest.csv` vacío.

### 2.5 Agregados posteriores de la etapa 1

- **Copia de los registros crudos.** Al cerrar, `messages.csv`,
  `negotiations.csv` y `schedules.csv` se copian a
  `corridas/<etiqueta>/registros_negociacion/`, porque la corrida siguiente los
  borra.
- **Comparación de planes.** Se comprobó que dos corridas con 87 cargas y el plan
  único del escenario **no son el mismo plan**: comparten solo 40 a 60 de sus
  522 tareas idénticas. Coincide el conteo, no el contenido.
- **`PlanInicialFijo`.** Con `USAR_PLAN_INICIAL_FIJO = false` no carga nada
  (`PlanInicialFijo.java:167`).
- **"Limitaciones conocidas".** Se agregó esa sección en
  `DOCUMENTACION_REGISTROS.md`: el defecto de los destinatarios y la variación
  del plan entre corridas.

---

## 3. Etapa 2: lotes de experimentos sin recompilar

**Pedido.** Definir en un JSON varias configuraciones (escenario,
`DEADLINE_MS`, repeticiones) y lanzarlas con un solo comando, sin recompilar
entre corridas.

### 3.1 Decisiones del usuario

| Tema | Decisión |
|---|---|
| Parámetros configurables | Solo la etiqueta, el escenario, `DEADLINE_MS` y el tope de la negociación. Los pesos y el paso del barrido **no se tocan** |
| Cómo llegan a Java | Propiedades del sistema `-Dtds.*`, leídas una sola vez al arrancar. Si falta una, se usa el valor del código |
| Validación | Solo que el escenario exista y que `DEADLINE_MS` sea un entero positivo, **sin mínimos ni máximos**. Si algo es inválido, la corrida no arranca |
| Tope de 90 min | Opción (b): `-Dtds.topeNegociacionMin` y la clave `tope_negociacion_min`. `null` = sin tope; si no se entrega, 90 min |
| Corrida sin ningún slot | Se corrigió en `VentanaSimulacion`: cierre con plan vacío, motivo `negociacion_completa`, campo `plan_vacio` |
| Valores por defecto | Los del commit `5d09ee1`: escenario grande original, 5000 ms, etiqueta `grande_F1_COMPLETE` |
| Escenario "grande" | `datosShovelTrucksGrande_original.owl`. `datosShovelTrucksGrande.owl` existe pero no se usa |
| Categorías del resumen | OK, NO VÁLIDA, colgada, **fallida**, saltada |
| Registro del lote | `corridas/_lotes/<fecha_hora>/` |
| Tiempo máximo por corrida | 360 min por defecto, solo como seguro contra corridas colgadas |
| Ventana de la mina | Oculta en los lotes (`-Dtds.sinVentana=true`); se puede mostrar con `"mostrar_ventana": true` |

### 3.2 Por qué el tope existente no bastaba

- **Corridas que no terminaban nunca.** Si ninguna negociación tiene ganador,
  `evaluarArranqueAutomatico` salía en `if (agendas.isEmpty()) return;` antes de
  mirar el criterio de palas llenas o el tope. Pasa, por ejemplo, con un
  `DEADLINE_MS` muy bajo. Se corrigió con `evaluarCierreSinSlots`.
- **El tope cortaba los deadlines altos.** Con 90 min y deadlines de unos 17 s o
  más, la negociación se cortaría y la corrida quedaría NO VÁLIDA. Por eso ahora
  el tope es configurable y puede quitarse.

### 3.3 Qué se implementó

| Archivo | Qué hace |
|---|---|
| `src/ConfiguracionCorrida.java` (nuevo) | Lee y valida `-Dtds.etiqueta`, `escenario`, `deadlineMs`, `topeNegociacionMin`, `sinDialogo` y `sinVentana`. Con un valor inválido termina con código 2 sin borrar nada |
| `correr_lote.py` (nuevo) | Valida el JSON, compila una vez, corre las corridas de a una, salta las ya hechas, aplica el tiempo máximo y escribe `lote.json`, `lote.log` y `estado.json`. Muestra el progreso y una estimación de tiempo restante, y se detiene si existe el archivo `DETENER`. Tiene `--simular` |
| `interfaz_lotes.py` (nuevo) | Ventana tkinter que **solo** edita el JSON y lanza el script. Tiene tabla de configuraciones, validación, simular, barras de progreso, Detener y aviso al cerrar |
| `experimentos_ejemplo.json` (nuevo) | Un lote de ejemplo |
| `COMO_CORRER.md` (nuevo) | Instrucciones de uso |

**El progreso** se lee de la traza que la ventana ya imprimía cada 15 s
(`[Negociacion inicial] t+…s palas x/y … slots n`). Para eso no se tocó Java.

**Instalación.** Se instaló `python3-tk` en Ubuntu con
`sudo apt-get install -y python3-tk`, con autorización del usuario.

### 3.4 Resultados con distintos `DEADLINE_MS` (escenario pequeño, una corrida por valor)

| `DEADLINE_MS` | Cargas | Toneladas descargadas en 6 h | Negociación |
|---|---|---|---|
| 5000 (por defecto, 4 corridas) | 88, 88, 89, 83 | 25.752; 25.752; 26.125; 25.152 | 1455, 1485, 1455, 1489 s |
| 1500 | 92 | 27.025 | 736 s |
| 500 | 80 | 23.052 | 705 s |
| 1 | 0 (plan vacío; la corrida terminó sola) | 0 | 723 s |

- **Corrige el análisis previo.** Leyendo el código se esperaba que con 500 ms
  casi no quedaran cargas, pero hubo 80.
- **Plazo y tick de la pala.** Bajo 2000 ms la negociación dura unos 12 min: el
  límite pasa a ser el tick de 2 s de cada pala, no el plazo.

---

## 4. Etapa 3: ventana oculta y pruebas intensivas

- **Ventana oculta.** Con `-Dtds.sinVentana=true` la ventana se crea, porque su
  reloj detecta el fin de la negociación, pero no se muestra. En una corrida
  vigilada no apareció ninguna ventana. Las corridas sin ventana dieron
  resultados dentro de la variación habitual: 89 y 83 cargas.
- **Escenarios mediano y grande.** Se probaron con un tope de 3 min: funcionan,
  con 10 palas y 50 camiones, y con 15 palas y 105 camiones.
- **Pruebas automáticas.** Se agregó la carpeta `pruebas/` (sección 7). Las
  pruebas encontraron un detalle: un REJECT sin receptores aparecía como
  `"REJECT-PROPOSAL": 0` en el conteo por tipo. Se corrigió en
  `EstadisticasPlanInicial`, que es un archivo nuevo.

---

## 5. Todos los archivos tocados

### 5.1 Archivos originales modificados (con permiso)

| Archivo | Por qué |
|---|---|
| `src/VentanaSimulacion.java` | Cierre al capturar el plan, en vez de simular el turno, y cierre cuando no hay ningún slot |
| `src/InicializadorSimulador.java` | No mostrar la ventana con `-Dtds.sinVentana=true` |
| `src/MainContainer.java` | Valida los parámetros, verifica la etiqueta y guarda el contenedor para poder apagarlo |
| `src/ParametrosConfiguracion.java` | `DEADLINE_MS` y escenario entregables al lanzar (mismos valores por defecto) |
| `src/ParametrosSimulador.java` | Etiqueta y tope entregables; `USAR_PLAN_INICIAL_FIJO = false`; `NINGUNO`; `TERMINAR_TRAS_PLAN_INICIAL` |
| `src/GuardarPlanInicial.java` | No guarda un plan vacío como plan único del escenario |
| `src/CsvNegotiationsLogger.java` | Solo el encabezado corregido |
| `preprocesar_registros.py` | Solo un comentario de obsolescencia al inicio |
| `DOCUMENTACION_REGISTROS.md` | Documentación (en la etapa 1 se creó; después se actualizó) |

Las líneas exactas de cada uno están en el **Anexo A**.

### 5.2 Archivos nuevos

| Archivo | Para qué |
|---|---|
| `src/CierrePlanInicial.java` | Genera los resultados, muestra el diálogo, copia los registros crudos y apaga JADE |
| `src/EstadisticasPlanInicial.java` | Calcula las estadísticas y escribe los archivos de resultados |
| `src/ConfiguracionCorrida.java` | Lee y valida los parámetros `-Dtds.*` |
| `correr_lote.py` | Lanza lotes |
| `interfaz_lotes.py` | Ventana para armar y lanzar lotes |
| `experimentos_ejemplo.json` | Lote de ejemplo |
| `DOCUMENTACION_REGISTROS.md` | Qué significa cada archivo y campo |
| `COMO_CORRER.md` | Cómo correr |
| `HISTORIAL_TRABAJO_CIERRE_SCHEDULE.md` | Este documento |
| `pruebas/verificar_codigo_original.py` | Verifica que el código original no cambió |
| `pruebas/correr_pruebas.py` | Corre todas las pruebas |
| `pruebas/test_estadisticas.py` | Pruebas de los cálculos (con `pruebas/java/ArnesEstadisticas.java`) |
| `pruebas/test_correr_lote.py` | Pruebas del script de lotes |
| `pruebas/test_interfaz.py` | Pruebas de la interfaz |
| `pruebas/__init__.py` | Hace de `pruebas/` un paquete de Python |

### 5.3 Fuera del repositorio

- **Memoria de Claude:** `~/.claude/projects/-home-vito-pc/memory/`, con notas
  para retomar el trabajo.
- **Paquete del sistema:** `python3-tk`.

---

## 6. Comandos útiles

```bash
cd ~/Documentos/TDS-MARL

# Compilar a mano (los lotes compilan solos)
javac --release 8 -encoding UTF-8 -d bin \
  -cp lib/jade.jar:$HOME/.m2/repository/org/jgrapht/jgrapht-core/1.0.1/jgrapht-core-1.0.1.jar src/*.java

# Una corrida a mano, sin recompilar (en Windows el separador es ';')
java -Dtds.etiqueta=prueba1 -Dtds.escenario=pequeno -Dtds.deadlineMs=3000 \
     -cp bin:lib/jade.jar:<jgrapht.jar> MainContainer
#   opcionales: -Dtds.topeNegociacionMin=<min|null> -Dtds.sinVentana=true -Dtds.sinDialogo=true

# Lotes
python3 correr_lote.py experimentos.json --simular     # ver qué haría
python3 correr_lote.py experimentos.json               # lanzar (relanzar = retomar)
touch corridas/_lotes/<fecha_hora>/DETENER             # detener tras la corrida en curso
python3 interfaz_lotes.py experimentos.json            # interfaz gráfica

# Pruebas y verificación del código original
python3 pruebas/correr_pruebas.py
python3 pruebas/verificar_codigo_original.py

# Git (la rama ya está en GitHub)
git push                                               # cambios siguientes
```

---

## 7. Pruebas automáticas

`python3 pruebas/correr_pruebas.py` (unos 45 s, no levanta JADE). Última
ejecución: **todas pasan**.

| Parte | Resultado | Qué prueba |
|---|---|---|
| Verificación del código original | OK: 39 protegidos intactos, 7 modificados autorizados | Ningún archivo de la simulación o del scheduling cambió, no se borró nada y los valores por defecto siguen iguales. Se comprobó que **sí detecta** un cambio |
| `test_estadisticas` | 16 de 16 | Cálculos con datos inventados de resultado conocido: unión de intervalos, solapes, recorte al turno, tres cifras de toneladas, filtros de la fase inicial, mensajes y control cruzado, plan vacío, actualización de `resumen_corridas.csv`. También que Java rechace parámetros inválidos y etiquetas repetidas sin borrar nada |
| `test_correr_lote` | 21 de 21 | Validación del JSON y comandos. Lotes completos con un `java` falso: OK, NO VÁLIDA, plan vacío, fallida, colgada, saltada, retomar, `--simular`, Detener y Ctrl+C |
| `test_interfaz` | 12 de 12 | Editar, validar, guardar, cargar, simular, lanzar con progreso, Detener y las tres respuestas al cerrar con un lote corriendo |

---

## 8. Pendientes y recomendaciones

1. **Sacar `pruebas/__pycache__/*.pyc` del commit `40a84b1`.** Son archivos
   temporales de Python que entraron por error. Para sacarlos:

   ```bash
   git rm -r --cached pruebas/__pycache__
   echo "__pycache__/" >> .gitignore
   git add .gitignore
   git commit -m "Saca __pycache__ del repositorio"
   git push
   ```

2. **Concentración de la flota en una pala** (consultarlo con el profesor
   **antes** del lote grande):
   - En el escenario pequeño, una pala recibe 68–76 de unas 85 cargas y las otras
     cuatro casi nada; el uso promedio de las palas es del 11–17 %.
   - Ya pasaba en el plan único de octubre, antes de estos cambios.
   - Si se decide cambiar el modelo, por ejemplo los pesos, habría que repetir
     todas las corridas.

3. **Diseñar el experimento:** valores de `DEADLINE_MS`, repeticiones (10 o
   más) y en qué PC correrlo. Una corrida con 5000 ms en el escenario pequeño
   toma unos 25 min.

4. **Definir qué es un "mejor plan"** y preparar el análisis de
   `corridas/resumen_corridas.csv`.

5. **Construir el componente LLM.** Ya están reservados en
   `configuracion.json` los campos `llm_modelo`, `llm_recomendacion` y
   `llm_latencia_ms`.

6. **Documentos obsoletos:** `preprocesar_registros.py`,
   `FLUJO_EPISODIOS_LLM.md` y `DUDAS_PROFESOR.md`, que eran para la
   reprogramación.

---

## Anexo A. Líneas cambiadas en los archivos originales

Generado con `git diff cc87b39 HEAD -- <archivo>`. Las líneas con `-` son las
originales y las líneas con `+` las nuevas.


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
