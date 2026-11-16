> Fase 6: antes del arranque se necesitan V1 y V2. Revisar owners, base común y permisos en ../../betting-suspension-service/docs/phase-6-atomic-security.md antes de usar cualquier comando de migración/activación anterior. Ninguno se ejecutó; consumidores/publicación/aceptación continúan apagados.

# Integración aislada — betting-suspension-service

Fecha: 2026-11-16. Preparación, no ejecución. Plan común y autorizaciones: [Fase 5](../../betting-suspension-service/docs/phase-5-integration-preparation.md).

## Preparación local sin infraestructura

Java 17 / Boot 3.5.7. Las pruebas locales normales no requieren una DB. Los tests PostgreSQL se omiten sin habilitación explícita.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
mvn -f services/betting-suspension-service/pom.xml test
mvn -f services/betting-suspension-service/pom.xml package
```

Perfil opcional: dev3-integration. No está activo por defecto. Documentadas en integration.env.example las variables DEV3_CONTROL_DB_URL/DB_USERNAME/DB_PASSWORD y HTTP_PORT; las restantes dependen del transporte. Completar un archivo privado ignorado, fuera de evidencias compartidas, y exportar esas variables solo cuando se autorice el entorno. No colocar passwords en la URL ni en argumentos de procesos.

## Migración y arranque: REQUIEREN autorización posterior

Primero aprobar una base/rol aislados y revisar las migraciones de src/main/resources/db/migration. El perfil exige conexión explícita; no hay creación automática de DB ni roles. La ejecución siguiente aplica migraciones y arranca HTTP, por eso **no se ejecutó en esta fase**:

```bash
java -jar services/betting-suspension-service/target/betting-suspension-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev3-integration --spring.flyway.enabled=true --betting.consumer.enabled=false
```

Nunca habilitar consumidores o aceptación junto a la primera migración. Tras migración autorizada, para ejecución ordinaria usar el mismo comando sin --spring.flyway.enabled=true: queda Flyway apagado y el consumidor también. StorageReadiness comprueba tablas antes de consumir; no sustituye revisar versión/esquema/permisos. El esquema propio es betting_control y el historial betting_control.flyway_schema_history.

Health HTTP: /actuator/health, /actuator/health/liveness y /actuator/health/readiness. Perfil aislado readiness incluye DB; salud no certifica lag cero ni autorización de apuestas. No ejecutar peticiones o conexiones a servicios existentes como parte de la preparación actual.

## Prueba PostgreSQL preparada: NO ejecutar sin autorización

Usa una DB NUEVA con nombre dev3_it_betting_control_<token>, rol dev3_it_* y puerto local exclusivo. Se rechazan puertos 5432, 55432, 55433 y 55434. No usar DB del MVP. El schema betting_control debe estar ausente; el test aplica sus migraciones y deja datos para inspección, sin borrar nada.

Solo después del permiso real, con DEV3_IT_DB_URL, DEV3_IT_DB_USERNAME y DEV3_IT_DB_PASSWORD exportados de forma privada:

```bash
DEV3_RUN_POSTGRES_TESTS=true DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS mvn -f services/betting-suspension-service/pom.xml -Dtest=PreparedPostgresIntegrationTest test
```

Este comando habilita explícitamente migraciones en la DB de test y **no se ejecutó**. Los flags no sustituyen permiso del responsable. No inicia contenedores, no crea bases y no prueba brokers/E2E. Para repetir, proporcionar otra base nueva aprobada. No hay comandos de limpieza automática.

## Activaciones pendientes

betting.consumer.enabled=false en el perfil. No modificarlo para pruebas reales hasta aprobar Kafka/Rabbit y recursos propios. Publicación y aceptación, si corresponden, también siguen false por configuración del perfil; no inferir un permiso de aceptación a partir del arranque o del healthcheck.

MATCH_CONTINUES no abre desde Kafka; la correlación sigue pendiente. Publicación outbox apagada y sin topologías declaradas.
