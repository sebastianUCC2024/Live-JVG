# Informe de implementación — DEV 3, Fase 2

Fecha: 2026-11-15, America/Bogota. Rama conservada: `integration/Valeria-mvp-dev`; HEAD `d2128b7`.

## Trabajo realizado y funcionalidades

Servicio Java 17 / Boot 3.5.7 con puertos/adaptadores, decoder provisional compatible con mensajes capturados del productor, captura transaccional de eventos/recibos, deduplicación y conflicto de identidad, cuarentena durable, reintentos limitados con pausa sin confirmar offset y API REST paginada en SQL. Código en inglés, documentación en español.

## Archivos modificados

1. `pom.xml`: alineación autorizada, JDBC/Web/Validation/Kafka/PostgreSQL/Flyway/Test; sin JPA ni librería de DB emulada.
2. `src/main/resources/application.properties`: configuración propia; Flyway y consumidor desactivados por defecto.
3. `src/test/java/com/live_jgv/archiver/HistoricalArchiverApplicationTests.java`: contexto local sin DB/brokers reales y comprobación de arranque seguro.

## Archivos creados

La lista completa figura en [files-created.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/services/historical-archiver/docs/files-created.txt) (inventario histórico retirado del árbol actual): clases de dominio, aplicación, adaptadores, listener/controlador/errores, V1 SQL, pruebas, fixture histórico, configuración Mockito de test y esta documentación/evidencias. La clase principal existente no se modificó. `target/` contiene solo artefactos generados ignorados.

## Migraciones y endpoint

Preparada `V1__archive_events.sql` para esquema `archiver` e historial propio; **no ejecutada**. Endpoint implementado: `GET /api/history/matches/{matchId}/events?page=0&size=50`, tamaño máximo 200, orden `seq ASC`, count/filas bajo REPEATABLE READ. API probada con MockMvc; no se afirma haber arrancado un servicio contra una DB real.

## Pruebas ejecutadas y resultados reales

- Primer `package` sobre caché original: falló antes de compilar por permisos del archivo de lock. Se copió la caché existente a `/private/tmp/live-jvg-archiver-m2`, sin borrar JAR ni detener procesos.
- Compilación/package offline con la copia: BUILD SUCCESS.
- Primera suite local: 60 pruebas, sin fallos/errores.
- Pruebas dirigidas de reintentos: 3 pruebas, sin fallos/errores.
- Verificación completa posterior: 65 pruebas, sin fallos/errores.
- Verificación final con caso adicional de serialización REST: **66 pruebas, 0 fallos, 0 errores, 0 omitidas**, BUILD SUCCESS y JAR empaquetado.
- Los conteos son ejecuciones sucesivas de la misma suite: no deben sumarse como pruebas distintas.
- `dependency:tree` offline: BUILD SUCCESS. Spring Kafka 3.3.10, PostgreSQL 42.7.8, Flyway 11.7.2, administrados por Boot 3.5.7. No hay H2 ni Hibernate ORM; Hibernate Validator es transitivo de Validation.
- Compatibilidad: cinco fixtures idénticos a payloads previamente capturados del productor. Verificado nuevamente mediante comparación del contenido.
- `git diff --check`, revisión de archivos nuevos y revisión de credenciales: realizadas antes de la entrega; ver `evidence/static-review.txt`.

Evidencias resumidas en `evidence/local-verification.txt` y árbol en `evidence/dependencies.txt`. No se ejecutaron pruebas reales Kafka/PostgreSQL ni Flyway. Los tests JDBC verifican consultas, parámetros, límites, mapeo y commit/rollback con mocks, no la sintaxis/aislamiento efectiva de PostgreSQL. La aprobación de integración real sigue pendiente.

## Riesgos, límites y dependencias del equipo

- Los contratos compartidos continúan vacíos y no se modificaron. Decoder y reglas documentan un formato provisional; campos futuros se conservan en cuarentena.
- Identidad global `event_id` en este archivo: revisar con el equipo frente a la identidad por partido del núcleo antes de aplicar V1. Los conflictos conservan la evidencia, no sobreescriben eventos.
- Tras agotar reintentos el consumidor requiere intervención/reinicio; no hay replay administrativo ni reanudación automática. La retención Kafka debe cubrir indisponibilidad/pausas; no se garantiza recuperar datos que el broker ya eliminó.
- No hay garantía exactly-once distribuida. Páginas distintas no comparten snapshot; OFFSET alto puede ser costoso. Payload/headers raw no se exponen por REST.
- Migración preparada, aún no aplicada ni verificada en PostgreSQL 17. Es necesario aprobar DB/usuario exclusivos, esquema, migración y pruebas reales antes de habilitar consumo.
- Autenticación, retención/backup y replay de cuarentena quedan pendientes de integración, no se presentan como terminados.

## Estado Git y próxima fase

Sin cambio de rama, commits, merges, push, resets, stash ni eliminaciones de archivos fuente. Todos los cambios de esta fase están dentro de `services/historical-archiver/`. El documento previo no rastreado `docs/dev3-phase-1-audit-design.md` se conservó intacto.

Detenerse y esperar aprobación. Recomendación: revisar identidad/contrato y autorizar la verificación real del archiver en recursos dedicados; no iniciar Betting Suspension Service ni Betting Worker automáticamente.
