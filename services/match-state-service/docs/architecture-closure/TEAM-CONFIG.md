# Configuración que debe preparar el equipo

Compose estático: siete Java, PostgreSQL 17, Kafka 3.9.1 KRaft y RabbitMQ 4.
Red y volúmenes internos del proyecto, puertos publicados en loopback. Cuatro servicios
opcionales en integration-prepared; no cambia recursos DEV 3. Kafka-init crea match_events.
Producer y Match State no tienen healthcheck HTTP propio en Compose: usar health de
Producer y evidencia SQL/barriers del harness; Match State no incorpora servidor web.
Los healthchecks opcionales son liveness, no prueba de storage listo.

Gabriel:

- Revisar los cambios compartidos CI/Compose. Dashboard LocalMvpConfigurationTest exige
  container_name=live-jvg-mvp-dashboard; el archivo actual usa live-jvg-dev1-isolated-dashboard.
  ValidationPipelineTest exige matriz de tres Java y prohíbe cualquier comando docker.
  Actualizar esas expectativas para siete servicios y permitir únicamente config estático.
  DEV 1 no modifica esos tests ni los excluye.
- Registrar MATCH_ID=e2e-<run> y home/away en Dashboard y Match State. Migrar schema
  dashboard V1/V2 en base aislada mediante su procedimiento; validar snapshots y STOMP.
- Provisionar rol/schema notification V1 (Flyway automático desactivado), conceder permisos
  mínimos y configurar NOTIFICATION_DB_URL/USERNAME/PASSWORD, STORAGE_ENABLED y
  MESSAGING_ENABLED tras aprobación. Configurar MATCH_IDS, Rabbit host/port/credenciales,
  cola y exchange live_updates, binding score.<match>, DLQ y publisher confirms.
- Corregir texto de alertas ante cierre parcial cuando aprueben V2. MATCH_CONTINUES
  se guarda como SUPPRESSED, no genera alerta: el harness comprueba inbox, no alerta.

Valeria:

- Provisionar en la MISMA base live_jvg aislada schemas betting_control y betting_worker,
  con roles de runtime distintos de migración/owner y entre sí. JdbcAtomicAuthorization
  usa una sola conexión/transacción; la función worker llama control.lock_market_authority.
  Bases distintas no sirven para ese protocolo. URL común en Compose es compatible,
  pero el usuario live_jvg común/superusuario NO certifica permisos ni autorización.
- Orden: control V1/V2 antes de worker V1/V2; archiver V1 independiente. Migraciones
  automáticas siguen desactivadas. Readiness consulta tablas aun con consumers apagados:
  los opcionales no arrancarán funcionalmente sin schemas.
- Aprobar grants EXECUTE de funciones SECURITY DEFINER y revocar escritura directa sobre
  fuentes y operation_authorization según verifyInstallation. No crear leases reales.
- Configurar ARCHIVER/CONTROL/WORKER DB roles/passwords, source IDs y grupos exclusivos,
  misma Kafka source/epoch y tópico. Provisionar exchange/queue/binding de comandos y
  routing key. Habilitar solo consumers revisados; mantener publicación de control apagada
  si no se revisó destino. Worker acceptance=false y authorization.mode=disabled siempre.
- El escenario seguro prueba BLOCKED con autoridad desactivada; no prueba que la causa
  sea la suspensión observada. Para esa causalidad falta autoridad aprobada de DEV 3.

Plantilla segura: scripts/e2e/compose.owner-prepared.example.yml. No contiene activación.
Copiar a tmp y completar con propietarios; no versionar secretos. Dockerfile requiere
red para instalar curl; imagen aún no construida ni validada en ejecución.
