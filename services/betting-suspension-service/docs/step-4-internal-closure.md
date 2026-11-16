# Paso 4 — Cierre interno y preparación E2E

2026-11-16. Rama `integration/Valeria-mvp-dev`, HEAD inicial `c1df3f7`. Cambios/evidencias previos conservados. No se modificó ni eliminó infraestructura y no se repitió la certificación PostgreSQL.

## Hallazgo y corrección

`JdbcOpenLeaseIssuer` comprobaba estado OPEN, pendientes y versión bajo lock, pero el checkpoint era solo texto aportado por el caller. Aunque no estaba conectado a un bean/emisor automático, su constructor permitía emitir sin verificar la fuente.

Se añadió `LeaseSourceVerifier`, interfaz interna que recibe partido, versión y checkpoint. `UnavailableLeaseSourceVerifier` deniega siempre, sin red, claves o reglas de confianza inventadas. Es el bean predeterminado y el fallback del constructor existente del emisor. No hay propiedad que lo convierta en un verificador real.

El emisor ahora exige verificación positiva **bajo el mismo lock de mercado**, antes de persistir; ausencia, negativa o excepción provoca rollback sin lease. La implementación aprobada futura debe inyectarse explícitamente mediante el constructor de tres argumentos, tener ejecución acotada y demostrar la semántica de frescura acordada. El emisor continúa sin bean/endpoint/scheduler: no se habilitó emisión ni aceptación.

El fixture PostgreSQL preparado ahora inyecta explícitamente un verificador sintético para su prueba de lease; no declara verificar Kafka. No se ejecutó ese fixture ni se alteraron los datos certificados en este paso.

## Quién emite, firma, valida y revoca

| Función | Implementación real y límite |
|---|---|
| Emitir | JdbcOpenLeaseIssuer, interno y desconectado; usa reloj DB y TTL máximo 5 s. Ahora bloqueado sin fuente verificable |
| Firmar | No existe firma criptográfica: es una fila protegida de PostgreSQL, no un token bearer. No hay contrato de firma/emisor externo aprobado |
| Verificar fuente/catch-up | Interfaz preparada; adaptador predeterminado deniega. No existe implementación autoritativa aprobada |
| Validar lease para aceptar | JdbcAtomicAuthorization + funciones SQL: base/epoch esperados, transacción/DataSource actual, OPEN consistente, cero pendientes, versión/UUID/tiempos del registro autoritativo; grant y ACCEPTED en la misma transacción |
| Invalidar/revocar | Vencimiento y cambio de estado/versión impiden reutilización; epoch diferente se rechaza. No hay servicio automático de revocación/rotación de epoch |

Los comandos externos no pueden suministrar evidencia para autorizar; el decoder cerrado rechaza campos desconocidos. El puerto de lectura de evidencia por defecto devuelve UNKNOWN. Lease ausente, vencida, revisión equivocada o autoridad no confiable no autoriza; una caída DB propaga error y evita confirmar una operación falsa. No se añadió un segundo mecanismo de autorización ni se sustituyeron locks por REST.

## Adaptadores revisados, sin activarlos

| Adaptador | Implementado | Dependencia E2E |
|---|---|---|
| Kafka Archiver/Suspension | Bytes originales, clave/DTO V1 validado, recibo/evento durable, identidad compuesta, ack posterior al commit; cuatro entregas y pausa operativa ante fallo persistente | Kafka aislado autorizado, grupos/retención, eventos y escenarios de recuperación reales |
| Mercado → Rabbit | Outbox en transacción del estado, orden por partido, message_id estable, mandatory, mensajes persistentes, confirms/returns; incertidumbre conserva pendiente y permite reentrega | Topología/contrato aprobado, bindings y pruebas reales de confirmación/reentrega |
| Rabbit → Worker | Cola/exchange explícitos, identidad command/operation, auditoría durable, duplicados/conflictos; cuatro intentos y stop/requeue, commit antes de ack | Productor de comandos y topología aprobados, broker real y recuperación operativa |
| Recuperación/orden | Estado e inbox en PostgreSQL; cierres versionados rechazan evidencia vieja/ambigua. VAR V2 interno separado de V1 | Protocolo definitivo, replay/reconciliación y pruebas de servicios completos |

No se encontró otro defecto funcional comprobado en esos adaptadores que justificara reescritura. Las pruebas existentes cubren nacks/returns/timeouts, rollback de outbox, duplicados, retries y carga desde una nueva instancia. Mocks no certifican Kafka/Rabbit reales ni exactamente-una-vez. Tras stop/pausa se requiere revisión/reanudación operativa; no hay recuperación automática inventada.

`D1_4A_REGLAS_VAR_PROPUESTA.md` declara expresamente PROPUESTA sin aprobación de Gabriel/DEV 2 ni Valeria/DEV 3. Los cuatro contratos compartidos siguen vacíos. No se conectó closeReviewV2 al listener ni se reinterpretó GOAL_ANNULLED. CLEAR/OPEN de score no autoriza apuestas.

## Pruebas y preservación

Cuatro nuevas pruebas locales por el defecto de emisión: constructor predeterminado rechaza, fuente desconocida rechaza, fallo de autoridad revierte y request malformado no toca almacenamiento/verificador. Se amplió el test existente de orden lock → verificación → INSERT → commit y el contexto prueba el bean denegador y ausencia de emisor automático. El fixture preparado distingue su verificador sintético explícito.

Maven **test y package** en los tres POM, Java 17 explícito, sin activar gates de infraestructura:

| Servicio | Exitosas | Omitidas | Fallos | Errores |
|---|---:|---:|---:|---:|
| historical-archiver | 76 | 1 | 0 | 0 |
| betting-suspension-service | 130 | 1 | 0 | 0 |
| betting-worker | 103 | 15 | 0 | 0 |
| Total | **309** | **17** | **0** | **0** |

Package repite la suite, no duplica casos. Evidencia/comandos/hashes: `evidence/step-4-local-results.json`. `git diff --check` y revisión de alcance/secretos satisfactorios. No se volvieron a ejecutar PostgreSQL, Kafka o Rabbit. Las **17/17 PostgreSQL previas** y el reinicio certificado siguen documentados sin modificaciones en `postgres-real-certification.md`; no se atribuyen a este paso ni certifican un verificador de fuente real todavía inexistente.

Cambios de este paso: LeaseSourceVerifier (nuevo), UnavailableLeaseSourceVerifier (nuevo), JdbcOpenLeaseIssuer, SuspensionConfiguration, JdbcOpenLeaseIssuerTest, BettingSuspensionServiceApplicationTests, PreparedPostgresIntegrationTest y este informe/evidencia. Todo dentro de betting-suspension-service. Sin cambios a SQL/POM/configuraciones de activación, contratos, DEV 1/2 o infraestructura.

## PR y condiciones de integración

**Listo para abrir PR de código preparado con restricciones explícitas**, no para habilitar E2E/aceptación. No se abrió PR, hizo staging, commit o push. El árbol conserva trabajo previo y de este paso sin commit; la selección final de archivos debe revisarse antes de guardarlo.

Pendientes externos concretos:

1. DEV 1/equipo: publicar/aprobar eventos V1/V2, identidad/versionado y GOAL_ANNULLED inequívoco; aprobar cierre de bloqueos GOAL/VAR, replay y autoridad de frescura.
2. DEV 2/Gabriel: confirmar política VAR y compatibilidad con Dashboard/Notification; CLEAR no constituye permiso. No se asume aprobación desde un documento de propuesta.
3. Responsable de arquitectura: definir quién verifica catch-up/autoridad y su garantía temporal. Implementar ese adapter del puerto tras aprobarlo; mantener denegación mientras falte. El callback booleano es una frontera interna de confianza, no una firma ni prueba por sí mismo.
4. Equipo: contrato/productor/topología Rabbit de apuestas y permisos/grupos Kafka; autorizar recursos E2E y ejecutar publicación, consumo, confirmación incierta, reentrega y recuperación reales.
5. Validar el despliegue/owners y la integración del verificador nuevo en pruebas aisladas; conservar SQL atómico y reconciliar historiales antiguos de GOAL según el plan pendiente. Solo después aprobar activaciones por separado.

No quedan correcciones internas comprobadas pendientes de esta inspección. Sí quedan capacidades dependientes de esas decisiones; no se presentan como implementadas ni se ocultan con mocks. Consumidores, publicación, aceptación, modo atómico y Flyway predeterminados permanecen desactivados.
