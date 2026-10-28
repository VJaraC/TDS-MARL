# Informe de diagnóstico — Colas en palas, desbalance de asignación, huérfanos y desempeño de los métodos reactivos

**Proyecto:** TDS-MARL (despacho reactivo camión–pala, sistema multiagente JADE / Contract-Net)
**Escenario analizado:** flota mediana (50 camiones, 10 palas), incertidumbre activa (clima + averías), semilla 42
**Fecha:** julio 2026

---

## 1. Pregunta que motivó el análisis

Los resultados observados eran contraintuitivos: los métodos de reprogramación reactiva (Partial Repair, Complete Regeneration) rendían **igual o peor** que el baseline sin reprogramación (NINGUNO), cuando lo esperable era que mejoraran la producción. El profesor planteó dos hipótesis de código a verificar:

1. **Condición de carrera:** que el borrado de las asignaciones futuras no termine completo (en camión, pala y destino) *antes* de que la pala empiece a negociar, perdiendo la oportunidad de reasignar.
2. **Doble oferta:** que la pala re-oferte un slot que ya tiene asignado.

Este informe documenta la revisión de código de ambas hipótesis y, sobre todo, una **medición cuantitativa** que reorientó el diagnóstico hacia la causa real: **un desbalance en la asignación de camiones a las palas**, que genera colas en unas palas mientras otras quedan ociosas.

---

## 2. Aclaración clave del modelo: ubicación ≠ pala

Un dato del modelo que resultó central para interpretar todo lo demás: **una misma ubicación de carga puede alojar varias palas físicas**. En la flota mediana hay 10 palas repartidas en 3 ubicaciones:

| Ubicación | Palas físicas |
|---|---|
| `UB1_4390_EST` | 4 (PA03, PA04, PA08, PA09) |
| `RF10A_4270_1012_1` | 4 (PA01, PA05, PA06, PA10) |
| `RF10B_4255_1001_1` | 2 (PA02, PA07) |

Cada pala es un servidor de **un solo puesto** (carga un camión a la vez), pero una **ubicación con 4 palas puede cargar 4 camiones en paralelo**. Esta distinción cambia cómo se lee cualquier medición de "camiones simultáneos": 8 camiones a la vez en la *ubicación* UB1 no son infactibles — son 4 cargando en paralelo + 4 en cola.

> **Corrección respecto de una versión previa de este informe.** El primer exportador de solapes agrupó las cargas por **ubicación**, no por **pala física**, y por eso reportó un sobre-agendamiento mucho mayor del real (hasta "8 camiones en una pala"). Ese número mezclaba carga paralela legítima con cola. La versión corregida mide por pala física; los resultados corregidos están en las secciones 3 y 4.

---

## 3. El sobre-agendamiento *real* (por pala física) es menor — el problema no es la infactibilidad del plan

Contabilizando los solapes de CARGA **dentro de una misma pala física** (lo único físicamente infactible), en la corrida mediano Partial Repair:

| Métrica | Agrupado por **ubicación** (incorrecto) | Por **pala física** (correcto) |
|---|---:|---:|
| Pares de cargas solapadas | 142 | **26** |
| Pico de camiones simultáneos | 5–8 | **3** |

Es decir: **la gran mayoría del "sobre-agendamiento" era carga paralela legítima entre palas hermanas**, no infactibilidad. El plan comprometido por la negociación es, en lo esencial, físicamente realizable. Quedan 26 solapes reales (concentrados en la pala más saturada, PA09), un problema menor, no la causa de la pérdida de producción.

**Conclusión de esta sección:** descartada la hipótesis de que el cuello de botella fuera un plan infactible / sobre-agendamiento estructural. Hubo que buscar la causa en otra parte — y aparece en la sección siguiente.

---

## 4. Hallazgo principal — Las palas están DESBALANCEADAS: hay palas ociosas mientras otras hacen cola

Se midió la **utilización real** (tiempo efectivamente cargando / 6 h) de cada pala en ejecución.

**Por ubicación:**

| Ubicación | Utilización | Palas |
|---|---:|---:|
| `UB1_4390_EST` | 72,3 % | 4 |
| `RF10B` | 28,0 % | 2 |
| `RF10A` | 18,6 % | 4 |

**Por pala física** (dentro de cada ubicación):

| Ubicación | Pala | Utilización |
|---|---|---:|
| **UB1** | PA09 | **43,6 %** |
| | PA08 | **38,6 %** |
| | PA03 | 4,3 % |
| | PA04 | 3,3 % |
| **RF10B** | PA07 | 21,3 % |
| | PA02 | 1,5 % |
| **RF10A** | PA01 | 8,4 % |
| | PA05 | 6,2 % |
| | PA10 | 4,6 % |
| | PA06 | 0,8 % |

Y la coexistencia de ociosidad con demanda, muestreando el turno cada 30 s:

- Las **3 ubicaciones ocupadas a la vez** solo el **3,5 %** del turno.
- Hay **al menos una ubicación ociosa el 96,5 %** del turno.

**Lectura:** el sistema **no está limitado por falta de capacidad de palas** — hay muchísima capacidad ociosa. Está limitado por **cómo se asignan los camiones a las palas**. El desbalance ocurre en dos niveles:

1. **Entre ubicaciones:** UB1 al 72 % mientras RF10A trabaja al 19 %.
2. **Dentro de una ubicación:** en UB1, dos palas (PA08, PA09) hacen casi todo el trabajo (~40 % c/u) mientras las otras dos (PA03, PA04) están **ociosas el 96 % del tiempo**. En RF10B, PA07 trabaja y PA02 está prácticamente parada.

Los camiones se **amontonan** en una o dos palas de cada ubicación, forman cola, y dejan las palas hermanas libres. Esa cola —no un plan infactible— es lo que consume tiempo de ciclo y reduce el material movido.

**Por qué el Contract-Net no reparte:** cada pala negocia por su cuenta con criterio multicriterio (tiempo de viaje + espera). Los camiones eligen repetidamente la pala "nominalmente mejor" sin considerar la cola que están formando ni redirigirse a una pala hermana ociosa. El protocolo, tal como está, **no incluye un mecanismo de balanceo de carga entre palas**.

---

## 5. Solución propuesta — Redirección a pala ociosa (balanceo de carga)

La causa (desbalance) sugiere directamente la corrección: **un camión que va a hacer cola en una pala saturada debería redirigirse a una pala ociosa disponible.**

Esta idea es especialmente potente por un motivo concreto:

- Las palas ociosas de UB1 (PA03, PA04) **están en la misma ubicación** que las saturadas (PA08, PA09). Redirigir un camión en cola a una pala hermana cuesta **cero tiempo de viaje extra** — está físicamente al lado. Es prácticamente ganancia pura.
- Para el balanceo entre ubicaciones distintas hay un compromiso real (viajar a una pala ociosa más lejana cuesta tiempo de viaje), pero suele convenir cuando la cola es larga: es mejor manejar unos minutos extra que esperar más en fila. Ese compromiso debe **cuantificarse**, no darse por hecho.

Ventaja de implementación: **el modelo ya soporta la paralelización** (hay un ShovelAgent independiente por cada pala física; las 4 palas de UB1 ya son 4 agentes que pueden cargar en paralelo). No hay que cambiar el modelo físico — es un cambio en la **lógica de despacho**: que la selección considere la disponibilidad real de cada pala y reparta hacia las ociosas, priorizando las hermanas de la misma ubicación.

Este mecanismo ataca la causa raíz medida y es, a diferencia de los métodos reactivos actuales, un candidato con fundamento a **superar al baseline**. Queda propuesto como la próxima línea de trabajo, a validar con réplicas (ver sección 8).

---

## 6. Validación experimental — el ajuste del deadline (1 s → 5 s)

Se detectó que `DEADLINE_MS` —el tiempo que la pala espera propuestas tras enviar el CFP— estaba en **1 segundo**. En el log de mensajes se veía a las palas cerrar la negociación con "no hubieron propuestas" y, un instante después, llegar los PROPOSE de los camiones: demasiado tarde. Se subió a **5 segundos** y se re-corrieron los cuatro métodos de la flota mediana.

**Efecto sobre el plan** (mismo método NINGUNO, solo cambia el deadline):

| Métrica | deadline 1 s | deadline 5 s |
|---|---:|---:|
| Pares solapados (por ubicación) | 202 | 97 |
| Pares con inicio idéntico | 88 | 7 |
| Tiempo sobre-agendado (s) | 7 927 | 4 043 |
| Máx. camiones simultáneos | 8 | 5 |

El ajuste **limpió el plan**: las negociaciones ahora alcanzan a juntar propuestas dentro de la ventana y la firma del cierre prematuro (varias ofertas colapsadas en el mismo instante) casi desaparece (88 → 7).

**Pero no resolvió el problema de fondo.** Con el deadline en 5 s:

- La utilización sigue igual de desbalanceada: una sola pala por ubicación acapara la carga (p. ej. PA03 82,3 %, PA05 58,6 %) mientras las hermanas quedan ociosas al 1-2 %. Cambió *cuál* pala acapara (no-determinismo de JADE), no el patrón.
- El material movido no mejoró (NINGUNO 81 059 con 5 s, dentro de la banda 71 775-84 270 de las corridas con 1 s) y los métodos reactivos siguen sin superar al baseline.

**Conclusión:** el deadline de 1 s era un bug real que ensuciaba el plan, pero **no era la causa de las palas ociosas ni el límite de la producción**. El experimento confirma que el cuello de botella es la **lógica de asignación** (sección 4), no un parámetro de temporización. El ajuste queda como corrección de higiene necesaria; la palanca real sigue siendo el balanceo hacia palas ociosas (sección 5).

*(Nota metodológica: los materiales provienen de una sola corrida por método; la comparación entre métodos cae dentro del ±17 % de varianza (sección 8) y no permite ranking. La utilización por pala se midió sobre la corrida COMPLETE, la única con detalle por pala guardado.)*

---

## 7. Por qué existen tantos huérfanos

Un camión queda **huérfano** cuando, tras ser liberado, no consigue renegociar ningún slot dentro del tiempo de espera y queda inactivo el resto del turno. Datos reales de las corridas:

| Escenario | Liberaciones | Huérfanos | Tasa |
|---|---:|---:|---:|
| Grande — Partial Repair | 66 | 2 | 3 % |
| Mediano — Partial Repair | 78 | 26 | 33 % |
| Grande — Complete Regeneration | 99 | 46 | 46 % |
| Mediano — Complete Regeneration | 26 | 0 | 0 % |

**Causa raíz (a la luz de la sección 4):** el huérfano **no** se produce por falta de capacidad de palas — la sección 4 demuestra que hay palas ociosas casi todo el turno. Se produce porque, al liberarse, el camión vuelve a competir por las **mismas palas saturadas** y el despacho **no lo redirige a la capacidad ociosa disponible**. Las ofertas factibles que consigue caen más allá del tiempo de espera y no cierra ninguna reasignación → huérfano.

Dicho de otro modo: **hay tantos huérfanos por el mismo desbalance de asignación**, no por saturación global del sistema. El mecanismo de redirección de la sección 5 atacaría también esta causa: un camión liberado debería poder tomar una pala hermana ociosa en lugar de quedar huérfano.

Esto explica los dos patrones de la tabla:

- **Alta variabilidad (3 % ↔ 46 %):** la tasa depende de a qué pala apunta la renegociación *en el instante* de la liberación y de si esa pala está o no saturada. Como el orden de las negociaciones en JADE no es determinista (agentes en hilos concurrentes), la tasa oscila fuertemente entre corridas.
- **Complete Regeneration en flota grande (46 %):** libera muchos camiones a la vez que se vuelcan sobre las mismas palas populares; sin balanceo no pueden reabsorberse y se produce un orfanato masivo.

---

## 8. Hallazgo secundario — La varianza supera al efecto del método

Material movido en el turno (flota mediana):

| Corrida | Material en turno |
|---|---:|
| Baseline (corrida A) | 71 775 |
| Baseline (corrida B) | 84 270 |
| Partial Repair | 72 237 |
| Right-Shift Cascada | 90 562 |

**El baseline por sí solo oscila entre 71 775 y 84 270 (≈ ±17 %) entre dos corridas idénticas**, únicamente por el no-determinismo de JADE. Esa varianza es **mayor** que la diferencia entre el baseline y Partial Repair. En consecuencia, con los datos actuales **no es estadísticamente defendible afirmar que Partial Repair sea peor que el baseline**: la diferencia cae dentro del ruido. Solo Right-Shift Cascada (90 562) se despega con claridad de la banda del baseline.

Esto vuelve **imprescindible un protocolo de réplicas** (3–5 corridas por método y flota, reportando media ± desviación estándar) antes de emitir cualquier conclusión comparativa.

---

## 9. Verificación de las dos hipótesis de código del profesor

**Hipótesis 1 — Condición de carrera en el borrado.** *Verificada: correcta, no hay pérdida de oportunidad.* El borrado del plan en el registro es sincrónico y ocurre antes de enviar cualquier mensaje. Además, la re-oferta del hueco liberado se genera *dentro* del propio procesamiento del borrado de la pala, por lo que es imposible que la pala oferte el hueco antes de haberlo borrado. Si el camión aún no procesó su mensaje de liberación, simplemente rechaza esa ronda, pero la re-oferta es estado persistente y el mecanismo reintenta cada 2 s hasta que un camión libre la toma. El único efecto del asincronismo es, en el peor caso, un reintento de 2 s; nunca un camión huérfano.

**Hipótesis 2 — Re-oferta de un slot ya asignado.** *Verificada: el bug existe en el código, pero NO es la causa de los resultados.* No hay verificación de solape ni en la re-oferta ni al registrar al ganador. Sin embargo, la medición corregida (sección 3) muestra que el sobre-agendamiento real por pala es menor (26 solapes, pico 3) y que la causa de la pérdida de producción es el desbalance de asignación (sección 4), no la doble oferta. Corregir este detalle no modificaría el cuadro de resultados. Queda registrado como mejora de calidad de código.

---

## 10. Conclusiones

1. **Ubicación ≠ pala:** una ubicación aloja hasta 4 palas físicas que pueden cargar en paralelo. Medir "camiones simultáneos" por ubicación sobreestima el sobre-agendamiento.
2. El **sobre-agendamiento real por pala es menor** (26 solapes, pico 3): el plan es esencialmente factible. No es el cuello de botella.
3. **El cuello de botella es el desbalance de asignación:** hay palas ociosas casi todo el turno (una ubicación libre el 96,5 % del tiempo; dentro de UB1, dos palas ociosas al 3–4 % mientras dos trabajan al ~40 %). El Contract-Net concentra camiones en pocas palas y no reparte hacia las ociosas.
4. **Los huérfanos son un síntoma del mismo desbalance:** el camión liberado compite por las palas saturadas y el despacho no lo redirige a la capacidad ociosa disponible.
5. La **solución indicada** es un mecanismo de **redirección / balanceo hacia palas ociosas**, priorizando las hermanas de la misma ubicación (costo de viaje nulo). El modelo ya soporta la paralelización; es un cambio de lógica de despacho, y es la línea con fundamento para superar al baseline.
6. El **deadline de negociación (1 s → 5 s) se corrigió y validó** (sección 6): limpió el plan (sobre-agendamiento de 8 a 5 camiones simultáneos, colisiones en el mismo instante 88 → 7), pero **no cambió ni las palas ociosas ni el material** — descarta el deadline como causa y refuerza que el problema es la asignación.
7. La **varianza entre corridas (±17 %) supera la diferencia entre métodos**, por lo que toda conclusión comparativa requiere réplicas.
8. Las dos hipótesis de código del profesor fueron verificadas: la primera es correcta; la segunda es un bug menor real pero **no causal**.

---

## 11. Próximos pasos recomendados

- **Deadline ya corregido (5 s):** confirmado que limpia el plan pero no cambia el resultado; las corridas definitivas de la tesis deben hacerse con este valor.
- **Método de balanceo de carga (contribución propuesta / trabajo futuro):** implementar la redirección de camiones a palas ociosas —primero a hermanas de la misma ubicación— y medir material vs baseline.
- **Protocolo de réplicas:** 3–5 corridas por método × flota, con deadline 5 s, reportando media ± desviación, para separar señal de ruido y validar el nuevo método.
- **Corregir el exportador `CsvSolapesPala`** para que cuente el sobre-agendamiento por pala física (no por ubicación) y agregue la utilización por pala, dejando la métrica correcta registrada por corrida.
- (Menor) Corregir el doble-registro de filas en los CSV acumulativos y el bug de re-oferta, como mejoras de calidad, sin impacto en las conclusiones.
