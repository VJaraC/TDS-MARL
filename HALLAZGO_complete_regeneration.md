# Hallazgo: por qué quedan camiones huérfanos en el rescheduling (Partial Repair y Complete Regeneration)

> Documento para la tesis. Explica **qué es un huérfano**, **por qué aparecen**, y por
> qué **NO es un error de programación** sino una propiedad del sistema multi-agente.
> Datos: TDS-MARL, semilla 42, incertidumbre activa (clima + velocidad ±15% + colas +
> averías), 3 tamaños de flota (Pequeña 13, Mediana 43, Grande 91 camiones).

---

## 1. Resumen ejecutivo

Tanto **Partial Repair** como **Complete Regeneration** funcionan **liberando** camiones
(borrando sus tareas futuras) y dejando que el **Contract-Net Protocol (CNP)** los
re-asigne mediante negociación. Un camión queda **huérfano** cuando, tras ser liberado,
**no consigue una pala con quién renegociar** y se queda sin tareas para el resto del turno.

La causa de fondo es **una sola y es arquitectónica**: en este MAS las palas son un recurso
**escaso y de reserva-hacia-adelante** (comprometen su agenda hasta el fin de turno muy
temprano y luego **se suspenden**). Cuando un camión se libera a mitad de turno, casi nunca
hay una pala con capacidad libre para reabsorberlo. **No es un bug**: los métodos están
correctamente implementados; los huérfanos son la consecuencia esperable de aplicar
rescheduling sobre un contract-net descentralizado y saturado. **Eso es el hallazgo.**

---

## 2. ¿Qué es exactamente un "huérfano"?

- Un camión cruza el umbral de retraso → el método lo **libera** (borra sus slots futuros).
- El camión queda libre y vuelve a aceptar convocatorias (CFP) de cualquier pala.
- El **verificador** le da **180 segundos REALES** (tiempo de reloj de pared, no de
  simulación) para conseguir al menos un slot nuevo mediante el CNP.
- Si en esos 180 s no consigue ningún slot → se declara **HUÉRFANO definitivo**.

**Aclaración clave (error común):** un huérfano **no** es "no renegoció antes de las 6 h".
El turno de 6 h es solo el corte para medir producción. El huérfano se define por el
**timeout real de 180 s**, independientemente del reloj de simulación.

**¿Quedan horas muertas?** Sí, pero solo para los huérfanos: un camión huérfano queda
**inactivo desde su corte hasta el final** (sus tareas se borraron y no se reemplazaron).
Los demás (los que sí renegociaron + los que nunca se liberaron) **siguen trabajando**
hasta las ~7–9 h. Por eso se ven camiones terminando y el makespan real llega a 7–9 h:
**no es que se apague toda la flota, sino un subconjunto grande de ella.**

**¿Es un huérfano real o un artefacto de medición?** Es REAL. Se verificó de dos formas:
1. Correr a 12x y a 36x dio la misma tasa (~84%): **no es lentitud** (si fuera el timeout
   corto por correr rápido, a 12x habría bajado).
2. En el CSV de schedules, los camiones marcados huérfanos tienen **0 cargas después de
   su corte**: genuinamente nunca se re-asignaron, no es que el contador no los vio.

---

## 3. La causa raíz común: palas de reserva-hacia-adelante que se suspenden

El CNP de este simulador **no reabre capacidad a mitad de turno**:

- Cada pala (`ShovelAgent`) va ofertando horas de carga cada vez más adelante. Cuando su
  oferta supera el fin de turno (6 h), **se suspende** (`HayTareaPorAsignar → doSuspend()`).
- Una pala **suspendida no procesa mensajes** (ni siquiera recibe la orden de liberación)
  y **no emite nuevas convocatorias**.
- Resultado: a medida que avanza el turno, cada vez más palas quedan "cerradas". El camión
  liberado **no tiene con quién renegociar**.

Evidencia directa: al final de las corridas, la consola se llena de
`PAxx mi horario supera el turno` (las palas suspendiéndose).

Sumado a esto, hay **saturación estructural**: con 91 camiones contra ~15 palas, las palas
están copadas incluso antes de la avería del chancador (los huérfanos empiezan en cortes de
1:23–2:00 h, *antes* de la falla de las 3:20 h). Liberar un camión casi nunca encuentra holgura.

---

## 4. Partial Repair: cómo funciona y por qué deja huérfanos

**Cómo funciona:** dispara **por cada camión** que cruza el umbral de retraso (25 min),
de forma **continua durante todo el turno**. Libera los slots futuros de ese camión
(respetando su tarea en curso) y lo deja renegociar.

**Por qué deja huérfanos:** libera camiones **más rápido de lo que el CNP saturado puede
reabsorberlos**. Los primeros (cuando todavía hay holgura) renegocian; la mayoría, no.

**Datos — camiones distintos que terminan huérfanos (inactivos el resto del turno):**

| Escenario | Flota | Huérfanos finales | % de la flota inactiva |
|---|---|---|---|
| Pequeña | 13 | 5 | 38% |
| Mediana | 43 | 36 | **84%** |
| Grande  | 91 | 71 | **78%** |

**Consecuencia:** Partial Repair entrega **el menos material** de todos los métodos
(106 k t en Grande, vs 167 k sin hacer nada), justamente porque ~78–84% de la flota se
apaga. Su makespan es corto (~7,1 h) no porque trabaje mejor, sino porque **descartó
trabajo** (los huérfanos ya no producen).

---

## 5. Complete Regeneration: cómo funciona y sus dos capas de falla

**Cómo funciona:** monitorea la flota y, cuando se cruza un umbral **sistémico** (≥25% de
la flota sobre 25 min de retraso) o **catastrófico** (un camión ≥45 min), dispara **una sola
vez**: toma el reloj actual como `t0`, **descarta el plan futuro de TODA la flota**
(respetando la tarea en curso de cada camión) y deja que el CNP reconstruya. Tiene
freno anti-bucle (cooldown 120 s, máximo 3 por turno).

**Resultado por escenario (de `regeneraciones.csv`):**

| Escenario | Disparó en | Camiones liberados | Resultado |
|---|---|---|---|
| Pequeña | 4:46 h | 13 (toda la flota) | ✅ **funcionó** (mejor material y eficiencia) |
| Mediana | 8:37 h | 0 | ⚠️ **no-op** (disparó tarde) |
| Grande  | 9:11 h | 0 | ⚠️ **no-op** (disparó tarde) |

Falla en **dos capas**, ambas reportables:

### Capa A — el dilema del momento de disparo (timing)
- Para ser **realista**, el detector solo reacciona a retrasos **ya ocurridos** (slots ya
  ejecutados): no puede "ver el futuro".
- En flotas grandes, juntar 23+ camiones con >25 min de retraso *real* recién pasa a las
  **8:37 / 9:11 h** → para entonces **el plan ya se agotó** (`t0` cae después del makespan)
  → no hay nada que regenerar → **no-op** (libera 0).
- La versión inicial (no-causal) reaccionaba al retraso *predicho* y disparaba a las
  **0:08 h**, pero (a) eso equivale a "ver el futuro" (irreal) y (b) borrar toda la flota al
  arranque dio **100% de huérfanos** (colapso total, 0 t entregadas).
- **No existe un buen momento para disparar en flota grande:** muy temprano = colapso;
  con timing realista = demasiado tarde. La ventana útil **solo existió en la flota pequeña**
  (4:46 h, con plan todavía por delante).

### Capa B — el límite de re-asignación descentralizada
- Aun disparando a tiempo, regenerar **toda la flota a la vez** necesita palas con capacidad
  libre. Por la causa raíz de la §3 (palas copadas y suspendidas), no la hay → los liberados
  no se re-asignan. Es lo que se vio cuando la versión temprana disparó: **100% huérfanos**.

**Por qué Pequeña sí funcionó:** el umbral sistémico (4 camiones) se cruzó temprano (4:46 h),
cuando todavía había plan por delante **y** las 15 palas tenían holgura para 13 camiones.

---

## 6. ¿Está mal implementado? — No. Es un hallazgo, no un bug

El código de ambos métodos hace **exactamente** lo que define cada estrategia. Que queden
huérfanos (Partial) o que sea no-op (Complete en flota grande) **no es un defecto de
programación**, sino la manifestación de un límite estructural:

> **La regeneración completa es un concepto intrínsecamente CENTRALIZADO ("recalcular un
> plan global desde cero") y NO es realizable mediante un CNP DESCENTRALIZADO y de
> reserva-hacia-adelante. Partial Repair, aunque es local, choca contra el mismo techo:
> bajo saturación, libera más rápido de lo que el contract-net puede reabsorber.**

El detector causal (realista) lo confirma de manera contundente: o dispara tan temprano que
colapsa (no-causal), o tan tarde que no hay nada que regenerar (causal). No hay punto medio
en flotas grandes.

---

## 7. Tabla comparativa final (dentro del turno de 6 h)

| Métrica (Grande, 91 cam.) | Sin resched. | Cascada | Partial Repair | Complete Regen. |
|---|---|---|---|---|
| Material entregado (t) | 166 650 | **191 956** | 106 389 | 163 277 |
| Slots retrasados | 1 797 | **416** | 908 | 1 805 |
| Makespan real (h) | 8,9 | **7,0** | 7,1 | 9,2 |
| % flota huérfana | — | — | **78%** | 0% (no-op) |

- **Right-Shift Cascada** domina: más material, menos retrasos, mejor makespan. No libera
  camiones (los desplaza), así que **no genera huérfanos**.
- **Partial Repair**: pierde producción por los huérfanos.
- **Complete Regeneration**: no-op en flota grande (≈ sin rescheduling).

---

## 8. Qué lo "arreglaría" (fuera del alcance, pero útil mencionarlo)

- **Para Complete Regeneration:** un **planificador global centralizado** que recalcule las
  asignaciones desde `t0` y las escriba directamente, sin pasar por la renegociación
  distribuida. Coincide con la definición de la literatura y evita el colapso del CNP.
- **Para ambos:** que las palas **no se suspendan** y **reabran su oferta** al recibir una
  liberación (renegociar desde el hueco liberado). Reduciría los huérfanos, pero cambia el
  comportamiento del CNP validado y habría que re-correr todo.

Se documentan como trabajo futuro; confirman que la **estrategia descentralizada viable**
en esta arquitectura es la que **no libera** (Right-Shift Cascada), porque reabsorbe sin
depender de capacidad ociosa de las palas.

---

## 9. Correcciones aplicadas tras el hallazgo (Ronda B — igualdad de condiciones)

Tras la revisión de código se detectaron y corrigieron dos asimetrías, para que
los cuatro métodos compitan **bajo las mismas reglas**:

1. **Detector causal para TODOS los métodos.** El filtro de causalidad
   (medir retrasos solo sobre slots ya ejecutados, `finReal <= reloj`) existía
   solo en Complete Regeneration; NINGUNO/CASCADA/PARTIAL "veían el futuro"
   (disparaban sobre retrasos precalculados aún no materializados). Se aplicó
   el mismo filtro al detector per-camión (`ReschedulingAgent.chequearUmbrales`).

2. **Re-oferta de huecos liberados.** La pala ofertaba *append-only* (solo
   después de su último slot): un hueco liberado por Partial Repair /
   Complete Regeneration en el medio de su agenda era invisible → causa
   directa de huérfanos. Ahora (`ShovelAgent`):
   - Al recibir un LIBERAR, la pala registra la ventana del hueco
     [inicio, fin] y la **re-oferta** en su próximo CFP.
   - Reglas: solo huecos **dentro del turno** (< 6 h); los reintentos avanzan
     de a 60 s **acotados por el fin del hueco** (no invaden slots siguientes);
     pala saturada = sin hueco = sin re-oferta (se maneja naturalmente).
   - Las palas **ya no se suspenden** al llenar su turno (`doSuspend`
     eliminado): quedan vivas sin emitir CFPs, para poder procesar
     liberaciones tardías. Un agente suspendido no procesa mensajes.

3. **Prioridad de la re-oferta (fix tras el primer test de Ronda B).** El primer
   test mostró `RE-OFERTA de hueco liberado` en consola pero la pala seguía
   ofertando el final de su agenda (13/13 huérfanos): un flag "sin ganadores"
   viejo consumía la rama antes que la re-oferta y cerraba la ventana sin
   ofertarla. Ahora la re-oferta tiene prioridad y las ventanas vencidas
   (reloj > fin del hueco) se descartan.

4. **El pasado es inmutable (fix tras el primer test de Ronda B).** Con la
   detección causal, el ancla de la perturbación queda detrás del reloj al
   momento de detectar; Partial Repair borraba slots YA EJECUTADOS (producción
   hecha desaparecía del registro) y la cascada shifteaba trabajo ya realizado.
   Ahora el corte se fija en `max(ancla, reloj de simulación)`: solo se
   replanifica desde el presente.

Los datos previos a estas correcciones quedaron respaldados como `*_rondaA.csv`.
Los resultados de las secciones 4–7 corresponden a la Ronda A; la Ronda B debe
re-correrse completa (mismos escenarios, semilla 42) para la comparación final.

## 10. Cómo reproducir

1. `ParametrosSimulador.METODO_RESCHEDULING = COMPLETE_REGENERATION` (o `PARTIAL_REPAIR`).
2. `INCERTIDUMBRES_ACTIVAS = true`; semilla 42; correr a velocidad baja (~5–12x).
3. Datasets generados: `regeneraciones.csv` (disparos de CR), `liberaciones.csv` (disparos
   de PR), `verificacion_partial_repair.csv` (huérfano sí/no por camión), `kpis_corridas.csv`
   (producción, makespan, eficiencia).
4. Tasa de huérfanos por camión distinto:
   `awk -F',' 'NR>1{k=$2"|"$3;f[k]=$9;e[k]=$2}END{for(x in f){t[e[x]]++;if(f[x]=="true")o[e[x]]++}for(s in t)print s,o[s]"/"t[s]}' verificacion_partial_repair.csv`
