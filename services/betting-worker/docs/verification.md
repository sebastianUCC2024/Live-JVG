# Informe — DEV 3, Fase 4: Betting Worker

Fecha: 2026-11-15 (America/Bogota). Rama `integration/Valeria-mvp-dev`. HEAD actual `9d8aae20a5371d41b18bda7f10bfd66f42f22094`; inicial observado 9d8aae2. No se ejecutaron comandos Git de escritura.

## 1. Resumen de implementación

Procesador durable de operaciones simuladas con Java 17 / Boot 3.5.7, separación domain/application/infrastructure/entrypoint, identidad y estados terminales, JDBC/transacciones, captura de evidencia y guard de suspensión, Rabbit configurable, reintentos acotados y API de lectura. Sin datos financieros generados, pagos ni proveedores externos.

## 2. Archivos creados y modificados

Tres archivos existentes modificados: `pom.xml`, `src/main/resources/application.properties` y `src/test/java/com/live_jgv/worker/BettingWorkerApplicationTests.java`. La clase principal se conservó. Inventario exhaustivo de nuevos archivos en [files-created.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/services/betting-worker/docs/files-created.txt) (inventario histórico retirado del árbol actual); `target/` contiene artefactos generados ignorados.

## 3. Dominio

RECEIVED versión 0 pasa una sola vez a ACCEPTED, REJECTED, BLOCKED o FAILED, versión 1 con motivo/timestamp. Un resultado terminal no cambia al reentregarse. UNKNOWN/SUSPENDED, ausencia/fallo de fuente, prueba caducada, revisión antigua o integración desactivada bloquean. Intención fuera de la ventana temporal se rechaza; evidencia para otro partido produce FAILED. No se convierte un fallo DB en resultado confirmado.

El guard durable no permite que OPEN inferior a una suspensión observada reabra el mercado. Revisión contradictoria o cambio de autoridad/epoch congela hasta reconciliación. La misma prueba no renueva su TTL. Se conserva evidencia observada y guard usado junto a cada decisión.

## 4. Migraciones preparadas

V1__simulated_operations.sql para esquema e historial Flyway propios `betting_worker`; seis tablas: market_guard, command_inbox, operation, operation_transition, operation_evidence y message_audit. Restricciones únicas, referencias e índices. **No se ejecutó**. Flyway permanece desactivado.

## 5. Mensajería

Entrada Rabbit configurable, desactivada. No declara topologías ni comparte colas del equipo. Valida JSON y exchange recibido, conserva bytes/metadatos y audita inválidos/conflictos. AUTO ack solo tras retorno de transacción confirmada. Cuatro invocaciones como máximo por ciclo; al agotarse se solicita detener el listener y se lanza reencolado explícito. No hay bucle de reinicio automático ni disposición silenciosa.

La propuesta local de comando no sustituye `betting-command.schema.json`, todavía vacío. No hay productor de comandos aprobado ni fuente de suspensión segura conectada. El proveedor predeterminado del puerto devuelve UNKNOWN y la aceptación está desactivada, incluso si el listener se habilitara para capturar comandos.

## 6. Endpoint

`GET /api/betting/operations/{operationId}`: identidad, partido, estado, razón, versión y fechas; omite usuario simulado, payloads y pruebas privadas. 404 si no existe, 400 por parámetros inválidos y 503 por almacenamiento no disponible/inconsistente. Sin endpoints de escritura o reapertura.

## 7. Pruebas y resultados reales

Compilación/package inicial: BUILD SUCCESS. Primera suite: 64 pruebas. Ampliación: 69. Suite final: **73 pruebas, 0 fallos, 0 errores, 0 omitidas**. `mvn test` y `mvn package` finalizaron BUILD SUCCESS; package vuelve a ejecutar la suite. No sumar ejecuciones como pruebas diferentes. dependency:tree offline también exitoso.

Cobertura: comando provisional, JSON/UTF-8/tipos/campos financieros prohibidos; OPEN fresco aprobado, SUSPENDED, UNKNOWN, fuente fallida, expiración y respuesta tardía; guard contra OPEN antiguo/conflicto/epoch distinto; terminales inválidos; duplicate/identity conflict por comando/operación; rollback/row-count, fencing y bloqueo solicitados; audit durable; retry real sobre target local con registry/container simulado; REST/privacidad; nueva instancia JDBC leyendo filas persistentes simuladas; integraciones desactivadas.

No se ejecutaron conexiones o confirmaciones de brokers reales, PostgreSQL/Flyway ni contenedores. JDBC/concurrencia usan mocks: no certifican locks ni aislamiento reales. La prueba de nueva instancia es recuperación **simulada**, no reinicio de DB/JVM real. Se usó offline la caché temporal escribible existente, sin borrar artefactos ni detener procesos.

Evidencias: `evidence/local-verification.txt`, `evidence/dependencies.txt` y `evidence/static-review.txt`.

## 8. Riesgos y contratos pendientes

- Comando, ventanas y terminales son propuestas locales. No se inventa compatibilidad con contratos externos vacíos.
- El REST/outbox provisional de suspensión no ofrece directamente la evidencia de autoridad/epoch/freshness usada por este worker: no se conectó un cliente inseguro.
- Vigencia y revisión local no hacen atómica la suspensión aún en tránsito y una aceptación. Se exige protocolo aprobado de serialización/fencing/lease antes de habilitar aceptación. Freshness se evalúa tras consultar la fuente, no certifica deadline físico del commit ni relojes perfectos.
- Prueba de la misma revisión con otro vencimiento se trata como conflicto; renovaciones y revisión de evidencia frente a versión de negocio deben acordarse. No hay reset/reconciliación administrativa implementada.
- La consulta futura al puerto debe tener timeout acotado porque se mantiene un lock DB. No se implementó red remota sin timeout.
- Ack/commit inciertos admiten reentrega; no hay exactly-once distribuido. Auditoría de inválidos puede repetirse si se pierde ack; las operaciones no se duplican.
- Pendientes DB/usuario exclusivos, permisos, ejecución autorizada de V1, pruebas reales, retención/backups y protección del endpoint. No hay liquidación ni publicación de resultados con un contrato no aprobado.

## 9. Git y preservación

Rama conservada. Diferencias externas frente al inventario inicial: 0; si las hay, coinciden con archivos comprometidos en el HEAD actual y no se sobreescribieron. Se verificaron hashes de 541 archivos fuera del módulo, incluidos 90 archivos de Historical Archiver, Betting Suspension Service y diseño previo; estos últimos permanecen idénticos.

Los únicos cambios realizados por esta fase están en `services/betting-worker/`. Sin commits, push, reset, checkout, stash, clean, merge ni rebase. No se modificaron contratos/Compose ni otros microservicios.

## 10. Recomendación final

Revisar contratos y autoridad/fencing con el equipo; autorizar por separado migraciones y pruebas reales en recursos aislados. Confirmar redes/volúmenes efectivos antes de publicar. No avanzar automáticamente a integración real ni habilitar aceptación por una simple propiedad sin adaptador y protocolo aprobados.
