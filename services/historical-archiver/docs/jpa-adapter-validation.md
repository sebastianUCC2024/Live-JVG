# Historical Archiver — Spring Data JPA / Hibernate

Base: dev `0bd0bde8caf88cc8b0b90557e28e0b4ccf7e19b2`, con PR #38 integrado.
Rama: `feature/valeria-archiver-jpa`. Solo se modifica Historical Archiver.

## Arquitectura y garantías conservadas

`ArchiveKafkaListener → ArchiveService → ArchiveStore → JpaArchiveStore → repositorios Spring Data JPA → Hibernate → PostgreSQL`.

SportEvent continúa como record de dominio; ArchiveStore no cambia. ArchivedEventEntity e InputReceiptEntity pertenecen a infraestructura y mapean las tablas Flyway existentes. InputReceiptId mapea la PK compuesta del recibo. No se añadieron columnas, tablas, versiones de mensajes ni migraciones.

JpaArchiveStore no delega en JdbcTemplate. Utiliza inserciones nativas JPA `ON CONFLICT DO NOTHING` para conservar la adquisición concurrente de identidades sin abortar la transacción por una violación UNIQUE. Hibernate carga y bloquea el recibo mediante PESSIMISTIC_WRITE; su finalización se realiza por dirty checking y flush. El archivo se consulta mediante entidades ORM, incluyendo count y paginación JPQL. Para offsets mayores que int32 se conserva la API anterior usando una consulta nativa JPA con offset long, hidratada como entidades.

La identidad de negocio sigue siendo `(match_id,event_id)`; la de transporte `(source_id,topic,partition_id,record_offset)`. Se mantienen comparación canónica, bytes/headers originales, cuarentena, conflictos y el orden histórico por seq. Los campos originales del recibo son no actualizables en el mapping; ArchivedEventEntity es inmutable. BYTEA usa byte[] sin @Lob ni objetos OID.

Una TransactionTemplate con JpaTransactionManager delimita todo el archivado, incluidas las operaciones de ambos repositorios. Las transacciones de repositorio participan en esa misma transacción. No se captura un fallo de flush/commit como éxito. La lectura paginada mantiene un snapshot REPEATABLE READ de count y filas. No se añade una transacción Kafka distribuida.

## Configuración activa y respaldo

```properties
archive.storage.adapter=${ARCHIVER_STORAGE_ADAPTER:jpa}
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.flyway.enabled=false
```

- Predeterminado: JPA. `ARCHIVER_STORAGE_ADAPTER=jdbc` selecciona únicamente JdbcArchiveStore.
- Un valor desconocido falla al arrancar; no hay fallback silencioso ni dos ArchiveStore activos.
- Un único JpaTransactionManager también permite que el respaldo JDBC participe con su conexión Spring vinculada; ambos modos se verificaron mediante arranque Boot real de prueba, sin servidor HTTP ni consumidores activos.
- El respaldo cambia el adaptador, no elimina Hibernate ni su validación del esquema. No es un bypass de una inicialización ORM defectuosa.
- ArchiveJpaSafety rechaza create/update/create-drop/drop, generación estándar JPA de esquemas y Open Session in View habilitado, antes de crear el EntityManagerFactory. Se admiten validate/none; el valor probado contra V1/V2 es validate.
- Flyway sigue siendo dueño del esquema. No se modificaron migraciones históricas ni contratos. Los consumidores conservan sus flags OFF por defecto.

Se añadió únicamente spring-boot-starter-data-jpa sin versión explícita. El JAR verificado contiene Hibernate 6.6.33.Final y Spring Data JPA administrados por Spring Boot 3.5.7; Java 17.

## PostgreSQL real exclusivo

Se creó un contenedor NUEVO `live-jvg-archiver-jpa-fa71a3c070`, puerto loopback `58147`, volumen `live-jvg-archiver-jpa-fa71a3c070-data` y red `live-jvg-archiver-jpa-fa71a3c070-net`. Imagen PostgreSQL 17 del inventario local. Ningún recurso del E2E conservado o del laboratorio anterior fue reutilizado.

Roles nuevos: `dev3_it_jpa_fa71a3c070_owner` (migraciones), `dev3_it_jpa_fa71a3c070_app` (runtime) y bootstrap administrativo exclusivo del nuevo servidor. Credenciales aleatorias en archivos privados fuera de Git; no se publican.

Permisos runtime: CONNECT a la base de prueba, USAGE de archiver, SELECT/INSERT en archived_event/input_receipt, UPDATE de input_receipt y USAGE de archived_event_seq_seq. Son los permisos pasivos/académicos existentes, sin ampliaciones específicas para JPA. El runtime no tiene CREATE de base/schema, DELETE ni UPDATE de archived_event; no es administrador.

Solo se ejecutaron V1/V2 originales en bases nuevas. Cada repetición JPA utilizó otra base nueva y conservó la anterior. La prueba JDBC existente usó una base nueva independiente. Las cinco bases creadas y sus checksums/digests figuran en la evidencia de reinicio físico. No se modificó ni eliminó ningún dato previo.

## Pruebas y resultados reales

| Validación | Resultado |
| --- | --- |
| Maven verify, Java 17 | BUILD SUCCESS, JAR reempaquetado |
| Suite local normal | 96 aprobadas, 0 fallos, 0 errores; 19 externas omitidas por sus gates |
| JpaPostgresIntegrationTest | 17/17 aprobadas con PostgreSQL real; 0 omitidas |
| PreparedPostgresIntegrationTest existente | 1/1 aprobada con PostgreSQL real; 0 omitidas |
| Reinicio físico del PostgreSQL nuevo | PASS: mismas filas, digests y checksums Flyway en las cinco bases; mismo contenedor, imagen, red y volumen |
| git diff --check | PASS |

Las 19 omitidas del ciclo normal son 17 JPA, una PostgreSQL existente y una Kafka. Las 18 PostgreSQL se ejecutaron separadamente; no se declara ejecutada la prueba Kafka. El estado de CI se consulta en el PR, no se infiere de estos resultados locales.

Las pruebas reales cubren GOAL y GOAL_ANNULLED con referencia, duplicados y conflictos, mismo event_id en partidos diferentes, offsets repetidos con evidencia igual/diferente, bytes inválidos y tombstones en cuarentena, fallo inducido después de flush real y rollback de ambas tablas, 12 entregas concurrentes para una identidad, cuatro entregas concurrentes del mismo offset, paginación consistente con una inserción concurrente y offset long, reconstrucción del EntityManagerFactory, permisos mínimos, respaldo JDBC, selección productiva exclusiva y rechazo de DDL inseguro sin alterar esquema.

El conteo inicial del historial Flyway produjo un fallo de aserción de teardown: hay una fila adicional de creación de schema, no una tercera migración SQL. Se corrigió la prueba para comprobar versiones 1/2, ausencia de fallos y validate/checksums, y se repitió satisfactoriamente sobre una base nueva. No se cambió una migración para conseguir PASS.

Evidencias compartibles: [resumen](evidence/jpa/verification.json), [verify](evidence/jpa/local-verify-summary.txt), [PostgreSQL JPA](evidence/jpa/jpa-postgres-summary.txt), [PostgreSQL existente](evidence/jpa/legacy-postgres-summary.txt), [reinicio físico](evidence/jpa/physical-restart.json). Logs completos y configuración privada conservados fuera de Git en `/private/tmp/live-jvg-jpa-review/`.

## Kafka y límites de certificación

ArchiveKafkaListener, ArchiverErrorHandler, ArchiveService y la configuración del container Kafka no cambiaron. Se conserva enable.auto.commit=false, AckMode.RECORD y syncCommits=true. Una prueba con PostgreSQL real invoca el listener en proceso: comprueba commit visible antes de retornar y propagación de fallo con rollback. No se contactó un broker ni se certificaron offsets reales de Kafka en esta etapa.

El Archiver audita los eventos; no calcula ni modifica el marcador. La prueba de anulación comprueba persistencia y referencia, sin atribuirle reglas de Match State.

## Reproducción

Pruebas locales sin infraestructura:

```sh
JAVA_HOME=/ruta/a/jdk17 mvn -f services/historical-archiver/pom.xml verify
```

Pruebas PostgreSQL: requieren servidor 17 EXCLUSIVO, puerto loopback libre no reservado, base NUEVA `dev3_it_archiver_*` sin schema archiver, owner de migración y runtime `dev3_it_*_app`. Exportar credenciales desde un archivo privado; nunca usar el E2E o una base preexistente. La prueba aplica solo V1/V2 originales y los permisos runtime anteriores.

```sh
export DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS
# DEV3_IT_DB_URL, DEV3_IT_DB_USERNAME y DEV3_IT_DB_PASSWORD: migrador/base nuevos.
# ARCHIVER_JPA_IT_APP_USERNAME y ARCHIVER_JPA_IT_APP_PASSWORD: runtime exclusivo.
ARCHIVER_JPA_POSTGRES_TESTS=true JAVA_HOME=/ruta/a/jdk17 \
  mvn -f services/historical-archiver/pom.xml -Dtest=JpaPostgresIntegrationTest test
```

Para la prueba JDBC/Flyway existente, utilizar otra base nueva y DEV3_RUN_POSTGRES_TESTS=true con `-Dtest=PreparedPostgresIntegrationTest`. La repetición no limpia bases: crear otra base exclusiva. No usar clean de Flyway, drops ni permisos ampliados.

La infraestructura nueva se detiene al finalizar conservando volumen, red, bases y evidencias. No se publica ni fusiona el PR automáticamente.
