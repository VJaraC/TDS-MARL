# TDS-MARL

Simulador de despacho reactivo camión–pala en minería, basado en un sistema
multiagente (JADE) con negociación Contract-Net. Compara métodos de
reprogramación ante perturbaciones: right-shift en cascada, reparación parcial
y regeneración completa.

## Requisitos

- JDK 8 o superior (con `javac`)
- Maven 3

**Ubuntu/Debian:**

```bash
sudo apt install openjdk-21-jdk maven
```

**Windows:**

1. Instalar un JDK (por ejemplo [Temurin](https://adoptium.net/)) y marcar la
   opción que agrega Java al `PATH` y define `JAVA_HOME`.
2. Instalar Maven: descargar el zip desde <https://maven.apache.org/download.cgi>,
   descomprimirlo (por ejemplo en `C:\maven`) y agregar `C:\maven\bin` al `PATH`.
   Con [winget](https://learn.microsoft.com/windows/package-manager/winget/):
   `winget install EclipseAdoptium.Temurin.21.JDK` y `winget install Apache.Maven`.
3. Abrir una terminal nueva (PowerShell o CMD) y comprobar:

```powershell
java -version
javac -version
mvn -version
```

**macOS:** `brew install openjdk maven`.

No hay que descargar librerías a mano: JADE 4.6.0 viene en `lib/jade.jar` y
`jgrapht` lo descarga Maven.

## Compilar y ejecutar

```bash
git clone https://github.com/VJaraC/TDS-MARL
cd TDS-MARL
mvn compile
mvn exec:java
```

Los comandos son los mismos en Linux, macOS y Windows (PowerShell o CMD).
Ejecutar siempre **desde la raíz del repo**: el programa busca ahí los `.owl` y
`planes_iniciales/`. Se abre la ventana del simulador.
En IntelliJ: *File → Open* sobre la carpeta y aceptar *Import Maven project*.

## Escenarios

Los datos de cada flota están en ontologías `.owl` en la raíz:

| Archivo | Escenario |
|---|---|
| `datosShovelTrucksPequeno.owl` | Pequeño |
| `datosShovelTrucksMediano.owl` | Mediano |
| `datosShovelTrucksGrande_original.owl` | Grande (el que usa el código por defecto) |

El escenario se elige en `src/ParametrosConfiguracion.java`
(`NOMBRE_ARCHIVO_FLOTA`). El grande tarda unos 26 minutos en completar la
negociación inicial; para pruebas rápidas conviene el pequeño.

Los parámetros (`ParametrosSimulador.java`, `ParametrosConfiguracion.java`) son
`static final`: hay que volver a ejecutar `mvn compile` después de cambiarlos.

## Estructura

- `src/`: código Java del simulador y los agentes.
- `planes_iniciales/`: plan inicial fijo de cada escenario, compartido por todas las corridas.
- `lib/`: `jade.jar` (JADE no está en Maven Central).
- Scripts de análisis en Python: `exportar_excel_schedules.py`,
  `grafico_toneladas_acumuladas.py`, `analisis_variabilidad_planes.py` y
  `verificar_schedules.py`. Leen los resultados que genera el simulador.

## Resultados

Cada corrida escribe sus resultados en `corridas/<etiqueta>/`, además de CSV
acumulativos en la raíz (`kpis_corridas.csv`, etc.) y capturas en `snapshots/`.
Nada de eso está versionado: se genera al ejecutar.

## Más información

- `TRABAJO-PENDIENTE.md`: errores conocidos, limitaciones del modelo y líneas de trabajo abiertas.
- `HALLAZGO_complete_regeneration.md` e `INFORME_solapes_huerfanos.md`: análisis del comportamiento del sistema.
