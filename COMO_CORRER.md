# Cómo correr lotes de experimentos

Cada corrida del sistema negocia el plan inicial, deja sus resultados en
`corridas/<etiqueta>/` y termina sola (ver
[DOCUMENTACION_REGISTROS.md](DOCUMENTACION_REGISTROS.md)). Para correr muchas
corridas con distintos parámetros **sin editar el código ni recompilar**, se
describe el lote en un archivo JSON y se lanza con `correr_lote.py`.

## 1. Requisitos

- Java (JDK) con `javac` y `java` en el `PATH`. El código se compila con
  `--release 8`.
- Python 3, solo biblioteca estándar.
- `lib/jade.jar`, que está en el repositorio.
- `jgrapht-core-1.0.1.jar`. Por defecto se busca en
  `~/.m2/repository/org/jgrapht/jgrapht-core/1.0.1/`. Si está en otro lado, se
  indica con la clave `"jgrapht_jar"` del JSON.
- **Entorno gráfico.** Cada corrida crea la ventana del simulador, porque su
  reloj es el que detecta el fin de la negociación. En los lotes la ventana
  **no se muestra**: no se ve la mina, solo trabaja en segundo plano. Aun así,
  en Linux hace falta una sesión gráfica (`DISPLAY`).

## 2. El archivo de experimentos

Ejemplo (`experimentos_ejemplo.json`):

```json
{
  "tiempo_maximo_por_corrida_min": 360,
  "tope_negociacion_min": null,
  "nucleos_cpu": null,
  "configuraciones": [
    {"nombre": "base",         "escenario": "pequeno", "repeticiones": 10},
    {"nombre": "deadline1000", "escenario": "pequeno", "repeticiones": 10, "deadline_ms": 1000},
    {"nombre": "deadline8000", "escenario": "pequeno", "repeticiones": 10, "deadline_ms": 8000}
  ]
}
```

Conviene copiarlo con otro nombre (por ejemplo `experimentos_tesis.json`) y
editar la copia.

### Opciones generales

| Clave | Valor | Si falta |
|---|---|---|
| `tiempo_maximo_por_corrida_min` | Minutos (número > 0) o `null` = sin límite. Es **solo un seguro contra corridas colgadas**: si una corrida lo supera, el script termina el proceso, la anota como `colgada` y sigue con la siguiente. No es un límite del experimento; ponlo bien por encima de lo que puede durar una corrida normal | 360 (6 h) |
| `tope_negociacion_min` | Minutos o `null` = sin tope. Es el tope **del propio sistema** (`TOPE_NEGOCIACION_INICIAL_MS`): si la negociación inicial no termina en ese tiempo, el plan se captura incompleto y la corrida queda **NO VÁLIDA** | El valor del código, 90 min (como una corrida manual) |
| `nucleos_cpu` | Por ejemplo `"2,3"` o `"0-3"`: fija las corridas a esos núcleos con `taskset` (solo Linux). `null` = sin fijar | Sin fijar |
| `mostrar_ventana` | `true` para ver la ventana de la simulación (la mina) en cada corrida. Ocultarla no cambia el resultado: la ventana se crea igual y su reloj sigue funcionando, solo no se dibuja | `false` (oculta) |
| `jgrapht_jar` | Ruta al jar de jgrapht | La ruta de `~/.m2` de arriba |

**Diferencia entre los dos tiempos.**

- **El tope de la negociación** decide el resultado de la corrida: la
  marca NO VÁLIDA.
- **El tiempo máximo** solo protege al lote de un proceso que no termina.

Con `"tope_negociacion_min": null` ninguna corrida se corta por durar mucho.
Por eso el ejemplo lo trae así: estudiar valores altos de `DEADLINE_MS` implica
negociaciones largas. Duración aproximada en el escenario pequeño, a partir de
lo medido con 5000 ms:

| `deadline_ms` | Duración aproximada |
|---|---|
| 5000 | ≈ 24 min |
| 8000 | ≈ 40 min |
| 20000 | ≈ 1,7 h |

Con el tope de 90 min, desde unos 17–18 s de deadline las corridas quedarían
NO VÁLIDAS.

### Cada configuración

| Clave | Obligatoria | Significado |
|---|---|---|
| `nombre` | sí | Identifica la configuración. Solo letras, números, `_`, `.` y `-`. No puede repetirse |
| `escenario` | sí | `pequeno`, `mediano`, `grande` o el nombre de un `.owl` de la raíz del repositorio |
| `repeticiones` | sí | Cuántas corridas hacer (entero ≥ 1) |
| `deadline_ms` | no | `DEADLINE_MS` en milisegundos: el plazo que da cada pala a los camiones para responder. Entero > 0, **sin mínimo ni máximo**. Si falta (o es `null`), se usa el del código: 5000 |
| `tope_negociacion_min` | no | Reemplaza la opción general solo para esta configuración |

Nombres cortos de escenario:

| Nombre | Archivo |
|---|---|
| `pequeno` | `datosShovelTrucksPequeno.owl` |
| `mediano` | `datosShovelTrucksMediano.owl` |
| `grande` | `datosShovelTrucksGrande_original.owl` |

Existe también `datosShovelTrucksGrande.owl`, pero **no se usa**: el escenario
grande del trabajo, y su plan único en `planes_iniciales/`, es el `_original`.

**Etiquetas.** Se generan solas: `<nombre>_r01`, `<nombre>_r02`, … Si hay 100
o más repeticiones, se usan tres dígitos.

## 3. Lanzar el lote

Desde la raíz del repositorio:

```bash
python3 correr_lote.py experimentos_tesis.json --simular   # revisar antes: no ejecuta nada
python3 correr_lote.py experimentos_tesis.json             # lanzar
```

En Windows: `python correr_lote.py experimentos_tesis.json`.

Qué hace:

1. Valida el archivo completo. Si hay errores (nombres repetidos, escenarios
   que no existen, números inválidos, claves mal escritas), los lista todos y
   **no corre nada**.
2. Compila una vez (`javac --release 8` en `bin/`). Con `--sin-compilar` usa
   `bin/` tal como está.
3. Corre **una corrida a la vez**, esperando a que termine la anterior. Cada
   corrida se lanza sin diálogo final (`-Dtds.sinDialogo=true`) y con sus
   parámetros como propiedades de Java.
4. Al final imprime un resumen con las corridas en cada estado:

| Estado | Significado |
|---|---|
| **OK** | Terminó con todas las palas con el turno lleno (`corrida_valida = true`) |
| **NO VALIDA** | Terminó por el tope de negociación; su plan está incompleto |
| **colgada** | Superó `tiempo_maximo_por_corrida_min` y el script la terminó |
| **fallida** | El proceso terminó sin dejar `resumen_plan.json` (por ejemplo, un parámetro inválido o un error de Java); ver su log |
| **saltada** | Su carpeta ya existía |

Además se marcan las corridas con **plan vacío** (ninguna negociación con
ganador).

### Progreso en la terminal

Mientras corre, si la salida es una terminal, una línea se va actualizando con
lo siguiente:

- la corrida actual (`[5/30] base_r05`);
- las **palas con el turno lleno** sobre las palas totales, que es el criterio
  con que termina la negociación inicial;
- los **slots** asignados hasta ahora;
- el tiempo transcurrido;
- cuántas corridas del lote terminaron y el **tiempo restante estimado**.

La estimación usa la duración promedio de las corridas ya terminadas (OK o NO
VÁLIDA) **de la misma configuración**. Mientras alguna configuración pendiente
no tenga ninguna terminada, dice "sin estimación".

El dato sale de la traza que la ventana del simulador ya imprime cada 15 s
(`[Negociacion inicial] t+…s palas x/y … slots n`), leída desde el log de la
corrida. No se modificó Java para esto. Consecuencias:

- El progreso se actualiza cada 15 s.
- La barra de palas no avanza en forma pareja: las palas llenan su turno
  recién hacia el final de la negociación, así que puede quedar en 0/5 la mayor
  parte de la corrida. Los slots sí crecen de forma continua.

El mismo estado se escribe en `corridas/_lotes/<fecha_hora>/estado.json`, que
es lo que lee la interfaz.

### Detener el lote sin cortar la corrida en curso

Crea un archivo llamado `DETENER` en la carpeta del lote
(`corridas/_lotes/<fecha_hora>/DETENER`). El script termina la corrida en
curso, no lanza más y deja las restantes como "no ejecutadas". Al retomar, esas
se corren. `Ctrl+C`, en cambio, corta también la corrida en curso.

### Retomar

Si el lote se corta (se apagó el equipo, `Ctrl+C`, etc.), basta con **lanzar el
mismo comando otra vez**. Las corridas cuya carpeta `corridas/<etiqueta>/` ya
existe se saltan.

Ojo con dos casos:

- Una corrida **colgada** o **interrumpida** antes de capturar el plan no deja
  carpeta, así que se repite al retomar.
- Una que alcanzó a crear su carpeta (se cortó mientras escribía los
  resultados) se saltaría. El script lo avisa. Para repetirla, mueve esa
  carpeta fuera de `corridas/` antes de retomar.

### Dónde quedan los resultados

| Qué | Dónde |
|---|---|
| Resultados de cada corrida | `corridas/<etiqueta>/` (ver [DOCUMENTACION_REGISTROS.md](DOCUMENTACION_REGISTROS.md)) |
| Una fila por corrida, para comparar | `corridas/resumen_corridas.csv` |
| Registro del lote: copia del JSON usado y, por corrida, comando, inicio, fin, duración, código de salida y estado | `corridas/_lotes/<fecha_hora>/lote.json` |
| Salida de consola de cada corrida | `corridas/_lotes/<fecha_hora>/<etiqueta>.log` |

`configuracion.json` de cada corrida registra los valores **efectivamente
usados** (`deadline_ms`, `tope_negociacion_min`, escenario, etiqueta) y, en
`origen_parametros`, si cada uno vino del lote (`propiedad`) o del código
(`codigo`).

## 4. Interfaz gráfica (opcional)

```bash
python3 interfaz_lotes.py                        # empieza con una configuración
python3 interfaz_lotes.py experimentos_tesis.json # abre un archivo
```

Necesita tkinter. Viene con Python en Windows; en Ubuntu se instala con
`sudo apt install python3-tk`.

La interfaz **no reemplaza** a `correr_lote.py`: solo edita el JSON, que sigue
siendo la fuente de verdad, y lanza el script con ese archivo.

- **Tabla de configuraciones:**
  - Doble clic en una celda para editarla. Para el escenario hay una lista
    desplegable.
  - Botones Agregar, Duplicar, Quitar, Subir y Bajar.
  - `deadline_ms` vacío significa usar el valor por defecto.
  - Para sumar una columna (un parámetro nuevo), basta agregarla a `COLUMNAS`
    en `interfaz_lotes.py` y a `PARAMETROS` en `correr_lote.py`.
- **Opciones generales:** tiempo máximo por corrida (o "sin límite"), tope de
  la negociación (por defecto del código, sin tope o un número de minutos),
  núcleos de CPU y una casilla para mostrar la ventana de la simulación (por
  defecto, oculta).
- **Cargar JSON / Guardar JSON / Guardar como.** Antes de guardar se valida
  todo con las mismas reglas del script (nombres repetidos, números inválidos,
  escenarios inexistentes) y se muestran los errores juntos.
- **Simular:** guarda y muestra lo que haría `--simular`, sin ejecutar nada.
- **Lanzar:** guarda, pide confirmación y lanza el lote. Se muestran:
  - la barra del lote (terminadas sobre el total) con el tiempo restante
    estimado;
  - la barra de la corrida actual (palas con el turno lleno) con los slots y el
    tiempo transcurrido;
  - el estado de cada corrida;
  - el registro en vivo.
- **Detener tras la corrida en curso:** el lote termina después de la corrida
  actual, sin matarla.
- **Cerrar la ventana con un lote corriendo:** pregunta antes. Puedes pedir que
  se detenga tras la corrida en curso, o dejarlo corriendo en segundo plano. En
  ese caso su avance sigue en `corridas/_lotes/<fecha_hora>/` (`lote.log`,
  `estado.json`).

## 5. Correr una corrida a mano

Sin propiedades, el sistema usa los valores escritos en el código:
`ParametrosSimulador.ETIQUETA_ESCENARIO`,
`ParametrosConfiguracion.NOMBRE_ARCHIVO_FLOTA`, `DEADLINE_MS = 5000` y el tope
de 90 min. Termina con el diálogo de resumen. Para cambiar valores sin
recompilar:

```bash
java -Dtds.etiqueta=prueba1 -Dtds.escenario=pequeno -Dtds.deadlineMs=3000 \
     -Dtds.topeNegociacionMin=null -cp bin:lib/jade.jar:<jgrapht.jar> MainContainer
```

En Windows, el separador del classpath es `;`.

| Propiedad | Valor |
|---|---|
| `-Dtds.etiqueta` | Texto sin `/`, `\` ni comas |
| `-Dtds.escenario` | `pequeno`, `mediano`, `grande` o un `.owl` |
| `-Dtds.deadlineMs` | Entero > 0 |
| `-Dtds.topeNegociacionMin` | Minutos > 0 o `null` |
| `-Dtds.sinDialogo=true` | Sin diálogos: el resumen queda en la consola |
| `-Dtds.sinVentana=true` | No muestra la ventana de la simulación (la mina) |

Si algún valor es inválido, o el escenario no existe, la corrida **no arranca**
(código de salida 2) y dice por qué. Si `corridas/<etiqueta>/` ya existe,
tampoco arranca (código 1).

## 6. Pruebas automáticas y verificación del código original

```bash
python3 pruebas/correr_pruebas.py                 # todo, unos 40 s, no levanta JADE
python3 pruebas/verificar_codigo_original.py      # solo la verificación
```

**Verificación del código original.** Compara cada `.java` con el commit
`cc87b39`, anterior a estos cambios. Falla si:

- cambió algún archivo de la simulación o del scheduling: agentes, `Schedule`,
  `Slot`, `Documento*`, `ReservasDescarga`, registros, `PlanBase`,
  `PlanInicialFijo`, reprogramación, etc.;
- se borró algún archivo;
- cambió un valor por defecto de los parámetros: `DEADLINE_MS` 5000, escenario
  grande, turno de 6 h, tope de 90 min, semilla, etc.

También lista los 7 archivos originales que **sí** se tocaron y el motivo de
cada uno. Esos cambios están todos alrededor de la negociación: el arranque, el
cierre y los registros. Conviene correr la verificación antes de cada lote
importante y antes de cada commit.

**Pruebas automáticas** (carpeta `pruebas/`):

| Archivo | Qué prueba |
|---|---|
| `test_estadisticas.py` | Los cálculos de `resumen_plan.json`, con datos inventados de resultado conocido: unión de intervalos, solapes, recorte al turno, las tres cifras de toneladas, filtros de la fase inicial, conteo de mensajes y control cruzado, plan vacío, actualización de `resumen_corridas.csv`. También que Java rechace parámetros inválidos y etiquetas repetidas sin borrar nada |
| `test_correr_lote.py` | La validación del JSON, los comandos que se arman y lotes completos con un `java` falso: OK, NO VÁLIDA, plan vacío, fallida, colgada, saltada, retomar, `--simular`, Detener y Ctrl+C |
| `test_interfaz.py` | La ventana de lotes: editar, validar, guardar, cargar, simular, lanzar con progreso, Detener y las tres respuestas al cerrar con un lote corriendo |

## 7. Valores bajos de DEADLINE_MS

> **Análisis del código, no medido.** Lo siguiente se dedujo leyendo
> `ShovelAgent` y `TruckAgent`. No se ha verificado con corridas, salvo lo
> indicado al final.

Cómo funciona el plazo:

- **Pala:** pone en el CFP una hora límite: ahora + `DEADLINE_MS`. Decide apenas
  le responden los 15 camiones o, si no, cuando se cumple el plazo
  (`WakerBehaviour`). Después de cada negociación, la siguiente empieza en su
  próximo tick de 2 s.
- **Camión:** guarda los CFP y los revisa **cada 1 s**. Responde un CFP cuando
  falta **2000 ms o menos** para su hora límite (`TruckAgent.java:231`). Si la
  carga le es factible, antes de proponer consulta al punto de descarga
  (REQUEST → AGREE) y recién entonces envía su PROPOSE.

Consecuencias:

- **`DEADLINE_MS` > 2000 ms** (el caso por defecto, 5000):
  - **Cuándo responde el camión:** entre `DEADLINE_MS − 2000` y
    `DEADLINE_MS − 1000` ms después del CFP.
  - **Para qué le sirve esperar:** en ese tiempo ve los CFP de otras palas y
    puede preferir el más conveniente (`hayUnCFPmasConveniente`).
  - **Costo:** cada negociación dura aproximadamente `DEADLINE_MS − 1,5 s`.
    La duración total crece casi en proporción al plazo.
- **`DEADLINE_MS` ≤ 2000 ms:**
  - **Cuándo responde el camión:** la condición se cumple apenas recibe el CFP.
    Responde en su próximo tick, entre 0 y 1 s después, **sin esperar a ver
    otros CFP**. Cambia la forma en que el camión elige, no solo la rapidez.
  - **Si el plazo supera ~1000 ms más la consulta al punto de descarga:** casi
    todas las respuestas llegan a tiempo y las negociaciones se acortan.
  - **Si el plazo está cerca de 1000 ms o por debajo:** que una respuesta
    llegue a tiempo depende de en qué momento del tick de 1 s cae el CFP. Es
    azar.
  - **Respuestas que llegan después del plazo:** se pierden. Si la pala ya
    cerró sin propuestas, la ignora; si ya eligió a otro, queda guardada pero
    no puede ganar. Además, el camión pudo haber reservado una descarga
    provisoria.
  - **Qué se espera:** más negociaciones "deadline cumplido. No hubieron
    propuestas". Cada una adelanta la oferta de la pala 60 s, así que las palas
    recorren su turno con menos cargas.
- **`DEADLINE_MS` muy bajo** (decenas o pocos cientos de ms):
  - **Qué se espera:** que casi ninguna negociación tenga ganador.
  - **Cómo termina igual:** las palas "llenan" el turno avanzando de a 60 s
    (unos 361 intentos cada una, del orden de 12 min) y la corrida termina con
    **plan vacío** o casi vacío.
  - **Cómo se registra:** `plan_vacio = true` en `resumen_plan.json` y en
    `resumen_corridas.csv`.
  - **Antes de este cambio:** una corrida sin ningún slot nunca terminaba.

Ninguno de estos casos se trata como error: son parte de lo que se estudia.

**Observado en las pruebas** (noviembre de 2026, escenario pequeño, **una sola
corrida por valor**: sirve como orientación, no como resultado):

| `DEADLINE_MS` | Cargas | Toneladas descargadas en 6 h | Negociación | Negociaciones (con ganador) | Mensajes |
|---|---|---|---|---|---|
| 5000 (por defecto, 2 corridas) | 88 y 88 | 25.752 y 25.752 | 1455 y 1485 s | 1485 y 1527 (88) | 45.602 y 46.851 |
| 1500 | 92 | 27.025 | 736 s | 1524 (92) | 46.779 |
| 500 | 80 | 23.052 | 705 s | 1547 (80) | 50.327 |
| 1 | 0 (plan vacío) | 0 | 723 s | 1810 (0) | 101.360 |

Qué se aprende de estas corridas:

- **Con 500 ms el plan no se vacía.** Contra lo que sugería el análisis,
  todavía se asignan 80 cargas: la pala reintenta muchas veces y algunas
  respuestas llegan a tiempo.
- **Con 1 ms ninguna negociación tiene ganador** (todas terminan con
  "deadline cumplido"). La corrida termina con plan vacío, como se esperaba.
- **Bajo 2000 ms la negociación dura unos 12 min**, la mitad que con 5000 ms.
  Ahí el límite deja de ser el plazo y pasa a ser el tick de 2 s de cada pala:
  unos 300–360 intentos por pala.

Para afirmar algo hacen falta varias repeticiones por valor.
