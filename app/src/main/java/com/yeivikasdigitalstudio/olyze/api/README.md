# com.yeivikasdigitalstudio.olyze.api

Reservado para **EliNer API**. Vacío a propósito — la Etapa 4 del plan de
refactorización es explícita: *"No quiero crear EliNer API todavía... Todavía
sin implementar la API. Solo preparando el proyecto."*

## Por qué es un paquete aparte de `engine/`

```
Usuario → UI → EliNer API → EliNer Engine → Render / Audio / Animación / Física / Exportación
```

`EliNer API` es la capa que se ve DESDE afuera del motor — no es parte del
motor. Por eso no vive dentro de `engine/` (ni siquiera en `engine/core`):
`engine/core` son contratos que los módulos DEL MOTOR comparten entre sí
(ej. `PixelColorSource`, que hoy implementa `engine.render.GLRenderer`).
`api/` va a ser la fachada que el motor completo expone hacia afuera —
un nivel más arriba, entre `viewmodel/` y `engine/`.

## Qué va a pasar acá cuando se construya

Hoy el flujo es:

```
UI → EditorViewModel → engine/*
```

Cuando se construya `EliNer API`, pasará a ser:

```
UI → EditorViewModel → api.EliNerApi → engine/*
```

`EditorViewModel` (ver su KDoc de clase) ya está documentado como el punto
de inserción: sus funciones públicas de hoy (`addLayer`, `setKeyframe`,
`exportVideo`, etc.) son candidatas 1 a 1 a pasar de llamar `engine/*`
directo a llamar `EliNerApi.algo(...)`. El mapeo completo de qué llamada
de hoy correspondería a qué método de la futura API está en
`ETAPA4_PREPARACION_ELINER_API.md`, en la raíz del proyecto de
refactorización (no se versiona acá para no mezclar documentación de
proceso con código fuente).

## Qué NO incluye este paquete (todavía)

- Ninguna clase, interfaz ni función real.
- Ningún cambio en cómo `EditorViewModel` llama a `engine/*` hoy —
  sigue llamándolo directo, a propósito, hasta que esta capa exista.

## Alcance: qué queda fuera de EliNer API

`ProjectsViewModel` (gestión de la biblioteca de proyectos: listar, crear,
duplicar, borrar) queda fuera de este diagrama a propósito. `EliNer API`
es la fachada del MOTOR (render/audio/animación/física/exportación) — la
gestión de archivos de proyecto es un concern de persistencia/aplicación,
no del motor, y seguirá hablando con `data/ProjectStorage` directo como
hoy, sin pasar por acá.
