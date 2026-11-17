# DEV 3: preparación segura de integración y compatibilidad con PR #22

Base: dev. Compare: feature/Valeria-dev3-e2e-preparation.

Prepara Archiver, Suspension y Worker para el ensayo coordinado sin habilitar aceptación.
Añade perfil opt-in para PostgreSQL común con logins separados, readiness de listeners,
observabilidad de backlog y probes pasivos Kafka/Rabbit desactivados por defecto.

Incluye cuatro fixtures propuestos de BettingCommand del PR #22 y su verificación contra
el decoder real, más generador offline de provisión nueva y matriz de compatibilidad.
No modifica contratos compartidos, SQL de migraciones, Compose, CI ni módulos DEV1/2.

Validación: Maven verify en los tres módulos: 330 exitosas, 20 omitidas, cero fallos/errores.
Además 3 tests del generador y 5 tests offline del harness. git diff --check satisfactorio.
17/17 PostgreSQL es certificación ANTERIOR: no repetida ni extendida por esta entrega.
Los probes nuevos permanecen omitidos; no se ejecutó Kafka/Rabbit E2E.

Aceptación, consumidores y publicación siguen OFF. Verificador de leases sigue denegando.
Pendientes: autoridad/frescura/checkpoint, VAR V2 externo y GOAL_ANNULLED, ownership y
topología de comandos, entorno nuevo con roles/migraciones, entrega real y recuperación.

DEV1 debe corregir sus notas: Archiver requiere V1/V2 y deduplica (match_id,event_id);
modo interno de cierre DEV3 es V2_REVIEW. No se alteraron sus archivos.

Detalles: services/betting-suspension-service/docs/accelerated-delivery.md
Resultados: services/betting-suspension-service/docs/evidence/accelerated-delivery-results.json
Certificación previa: services/betting-suspension-service/docs/postgres-real-certification.md
Evidencia previa: services/betting-suspension-service/docs/evidence/postgres-certification/
