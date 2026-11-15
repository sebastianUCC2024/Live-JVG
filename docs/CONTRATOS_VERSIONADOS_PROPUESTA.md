# M04 — Propuesta de contratos versionados (pendiente de aprobación)

Los cuatro archivos de `contracts/events` existen pero tienen **cero bytes**:
match-event.schema.json, score-update.schema.json, live-alert.schema.json y
betting-command.schema.json. No contienen JSON Schema válido ni certifican las
validaciones del MVP. Se conservan sin cambios; no se rellenan contratos del
equipo desde esta auditoría. La tabla siguiente describe el comportamiento
observado, no una aprobación de un contrato compartido nuevo.

## MatchEvent: productor → Kafka → estado

| Campo wire | Tipo observado | Obligación / significado |
|---|---|---|
| event_id | string no blanco | Obligatorio; identidad lógica del evento dentro del partido |
| match_id | string no blanco | Obligatorio; debe coincidir con la key Kafka y catálogo |
| event_type | string enum | GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES |
| team_id | string o null | Obligatorio no blanco para GOAL; opcional en otros tipos; si existe debe pertenecer al partido |
| minute | integer | Obligatorio, 0..130 inclusive; límite del simulador, no regla universal de fútbol |
| timestamp | string ISO-8601 Instant | Obligatorio; no usar número epoch ni perder nanosegundos |
| reference_event_id | string o null | Obligatorio no blanco en GOAL_ANNULLED; opcional en los demás |

VAR_CHECK con referencia valida un GOAL activo del mismo partido. Sin referencia
inicia revisión general. GOAL_ANNULLED requiere el GOAL aceptado original y activo;
no puede anular dos veces. MATCH_CONTINUES limpia el estado de revisión. Una
referencia no blanca en otros tipos es actualmente admitida por el wire: prohibirla
sería una decisión nueva. Los publicadores nuevos rechazan referencias vacías o
solo espacios; el replay de datos históricos preserva los valores anteriores.

Los identificadores no están restringidos a UUID: el productor puede generar
UUID, pero entradas válidas existentes incluyen IDs de texto. No confundir
event_id con offset Kafka o un intento HTTP. Reintentos conservan ID, timestamp,
referencia y contenido; reutilizar identidad con otro contenido es conflicto.
El orden autoritativo es el de aceptación durable, no timestamp.

## ScoreUpdate: estado → RabbitMQ → dashboard

| Campo wire | Tipo observado | Obligación / significado |
|---|---|---|
| match_id / event_id | string no blanco | Ambos obligatorios; deduplicación por pareja |
| home_score / away_score | integer no negativo | Ambos obligatorios; enteros Java de 32 bits, no incrementos |
| event_type | string enum | Los mismos cuatro tipos deportivos |
| updated_at | string ISO-8601 Instant | Obligatorio, con precisión hasta nanosegundos |
| var_status | string enum | Obligatorio: CLEAR o IN_PROGRESS |
| reference_event_id | string o null | Requerido no blanco para GOAL_ANNULLED; nullable/ausente en los demás |

El productor serializa los ocho campos, incluidos nulls. El decoder admite
referencia ausente donde es nullable, pero rechaza campos desconocidos, tipos
coercionados, enums numéricos, propiedades repetidas y JSON adicional. Por eso
añadir un campo, incluso opcional, **no es retrocompatible** con ese consumidor.
El marcador recibido es completo; el dashboard no vuelve a calcular VAR ni resta
goles. Inbox y snapshot restaurado mantienen la última revisión comprometida.

MatchSnapshot REST/STOMP es un contrato propio del dashboard: añade stream_id y
revision al marcador. No es el schema Rabbit ni deben enviarse esos campos al
decoder ScoreUpdate. revision es secuencia local al stream; después de restaurar
se rota explícitamente la época. El frontend exige enteros seguros JavaScript;
BIGINT PostgreSQL no implica representación exacta ilimitada en el navegador.

## LiveAlert y BettingCommand

No hay schema definido ni flujo aprobado que permita establecer campos,
obligatoriedad, enum, IDs o referencias. Sus responsables deben aportar casos de
uso, fixtures y consumidores antes de fijar una versión. No se implementan los
esqueletos de notificaciones/apuestas/archivado ni se inventan contratos para DEV 3.

## Evolución propuesta para aprobación del equipo

1. Adoptar una versión de JSON Schema explícita (propuesta: draft 2020-12), `$id`
   estable y archivos inmutables por major, por ejemplo `events/v1/...`. Esto
   requiere aprobación y no se crea en esta etapa. `$id` identifica el schema;
   no versiona por sí mismo el mensaje transportado.
2. Definir required, nullability, rangos y additionalProperties por mensaje, con
   condiciones GOAL/VAR/anulación y fixtures de casos válidos e inválidos. La
   existencia/actividad de referencias y el catálogo requieren validación de
   dominio; JSON Schema solo valida estructura.
3. Acordar selección de versión por topic/routing o metadatos de transporte y
   estrategia de coexistencia; no introducir schema_version/envelope en el wire
   actual. Consumidores estrictos deben ampliarse primero de forma aprobada.
4. Versionar como incompatibles campos eliminados/renombrados, cambios de tipo,
   obligatoriedad/nullability más estricta, enums nuevos no soportados y cambios
   de significado de referencias/identidad. No asumir evolución aditiva segura.
5. Mantener matriz productor-versión/consumidor-versión, replay histórico y pruebas
   de rolling upgrade/rollback. Probar primero consumidor nuevo con productor
   anterior; no habilitar productores de versión nueva hasta comprobar lectores.
6. Tras aprobación, incorporar un validador real de JSON Schema a CI con las
   dependencias acordadas. Las pruebas actuales de DTO/wire no sustituyen eso.

## Evidencia de compatibilidad sin cambiar contratos

Dev1LiveWireCompatibilityTest compila fuentes reales de productor/estado, prueba
los cuatro tipos, campos exactos serializados, VAR, anulación y precisión. La
prueba nueva demuestra IDs no UUID, referencia nullable ausente y rechazo de un
campo schema_version no aprobado. Se mantienen los tests negativos del decoder.
No se declara validación contra los cuatro schemas vacíos ni M04 aprobado por el
equipo: queda resuelto el diagnóstico/propuesta y reforzada compatibilidad local.
