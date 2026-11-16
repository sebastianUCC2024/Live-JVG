# Informe — DEV 3, Fase 3

Fecha: 2026-11-15, America/Bogota. Rama mantenida: `integration/Valeria-mvp-dev`.

## 1. Resumen funcional

Betting Suspension Service implementado exclusivamente en su módulo con Java 17 y Boot 3.5.7: agregado fail-closed, JDBC/transacciones, decoder del formato deportivo provisional, recibos/cuarentena, deduplicación por partido, revisiones, historial y outbox. Endpoint GET de estado. Sin dinero real ni Betting Worker.

## 2. Archivos creados y modificados

Tres existentes modificados: `pom.xml`, `src/main/resources/application.properties` y `src/test/java/com/live_jgv/betting/BettingSuspensionServiceApplicationTests.java`. Clase principal intacta. Inventario completo de nuevos archivos en `files-created.txt`.

## 3. Estados y transiciones

- UNKNOWN inicial/desconocido bloquea apuestas.
- VAR_CHECK suspende y registra revisión pendiente.
- GOAL no abre; GOAL_ANNULLED suspende y no cierra revisiones.
- MATCH_CONTINUES de Kafka no autoriza apertura con el contrato actual.
- Cierre interno explícito con revisión/continuación del ledger, actor/motivo y expectedVersion: solo abre al cerrar la última revisión sin incertidumbre.
- Duplicado exacto no genera transición adicional; conflicto de identidad bloquea como UNKNOWN.
- Mensajes inválidos se conservan en cuarentena y no autorizan apertura. Anulación tardía bloquea hasta reconciliar; no se presupone orden de llegada.

## 4. Contratos pendientes

Cuatro contratos compartidos vacíos, sin modificaciones de esta fase. Decoder compatible provisionalmente con formato inspeccionado y cinco fixtures históricos. El productor actualizado mantiene DTO/snake_case/clave; añade `OutgoingReferencePolicy` para nuevas publicaciones, que no redefine la correlación de revisiones ni obliga a rechazar hechos históricos de otro modo válidos.

ReviewClosure es interfaz interna sin adaptador Kafka/REST. Autoridad, correlación y reconciliación requieren aprobación. No se afirma integración compatible con un Betting Worker aún inexistente.

Outbox propone BETTING_SUSPENDED/BETTING_RESUMED con payload interno versionado; no es contrato compartido aprobado. Publicación apagada y sin destino predeterminado. No se declaran exchanges, colas o bindings. Confirms/mandatory/returns gobiernan el marcador de publicación; timeout y fallos de commit admiten reentrega. No hay exactly-once.

## 5. Persistencia y migraciones

V1__betting_control.sql preparada para esquema propio y su historial Flyway. Siete tablas: market_state, processed_event, pending_review, input_receipt, closure_command, transition_history y suspension_outbox. **No ejecutada**. Flyway, listener y publicación desactivados por defecto.

Identidad y cambios se serializan con fila por partido y FOR UPDATE; WHERE version añade fencing. Evento, revisiones, historial, outbox y recibo forman una transacción; error revierte todo y evita retorno exitoso al listener.

## 6. Pruebas y resultados reales

- Compilación/package inicial: BUILD SUCCESS.
- Primera suite: 77 pruebas. Ampliación: 83. Suite final: **86 pruebas, 0 fallos, 0 errores, 0 omitidas**.
- `mvn test`: BUILD SUCCESS. `mvn package`: BUILD SUCCESS, vuelve a ejecutar las 86 pruebas y empaqueta el JAR. Los conteos son ejecuciones sucesivas; no deben sumarse.
- `dependency:tree` offline: BUILD SUCCESS. Spring Kafka 3.3.10, Spring Rabbit 3.2.8, PostgreSQL driver 42.7.8 y Flyway 11.7.2, gestionados por Boot.
- Cobertura de decoder, UNKNOWN/OPEN/SUSPENDED, cierres válidos/ambiguos, varias revisiones, anulación tardía, duplicate/conflict, cuarentena, rollback, bloqueo/fencing, outbox, confirms unitarios, reintentos sin commit, REST fail-closed y configuración inactiva.
- `git diff --check`, espacios/marcadores en archivos nuevos, dependencias, alcance y patrones de credenciales: ver evidence/static-review.txt.

No se conectó a Kafka/Rabbit/PostgreSQL reales ni se ejecutó Flyway. JDBC/manager/concurrencia usan mocks: verifican protocolo SQL y límites transaccionales, **no locks/aislamiento PostgreSQL efectivos**. Confirms simulados son cobertura del adaptador, no evidencia de broker real. Se usó la caché temporal escribible existente; no se borró caché ni se detuvieron procesos.

Evidencias: `evidence/local-verification.txt`, `evidence/dependencies.txt` y `evidence/static-review.txt`.

## 7. Limitaciones y riesgos

- Reanudación automática bloqueada hasta aprobar correlación/autoridad. Habilitar el listener no activa cierre por MATCH_CONTINUES.
- No hay reconciliación forzada, replay ni endpoint administrativo. Cierre rechazado revierte; no se promete una auditoría durable de intentos rechazados.
- Retries agotados pausan hasta intervención/reinicio. Retención Kafka condiciona recuperación.
- Snapshot durable no garantiza actualidad frente a VAR aún en tránsito. Consultar OPEN no resuelve la carrera con órdenes en otro servicio.
- Outbox mantiene conexión/lock durante confirmación acotada; cambiar destino con resultado incierto requiere revisión. Versiones pueden saltar porque no todos los eventos producen transición.
- Migración no aplicada/validada en PostgreSQL 17. Pendientes contrato/topología, DB/usuario exclusivo, autorización de migraciones, pruebas reales y política de acceso/retención.

## 8. Git y alcance: avance externo detectado

HEAD inicial observado: d2128b7. Durante la fase avanzó **por operación externa** hasta `9d8aae20a5371d41b18bda7f10bfd66f42f22094`; este agente no ejecutó commits, merge, pull ni otros comandos Git de escritura. La rama sigue siendo `integration/Valeria-mvp-dev`.

La comparación de 447 hashes iniciales encontró 45 diferencias fuera del módulo; todas coinciden byte por byte con archivos ya presentes en el HEAD actual (cambios externos comprometidos). Los 42 archivos inventariados del archiver y documento Fase 1 permanecen idénticos al inicio. No se atribuyen esos cambios externos a la Fase 3 ni se afirma que HEAD haya permanecido fijo.

Los únicos archivos rastreados modificados sin commit fuera del módulo siguen siendo los tres de Historical Archiver preexistentes. Sus archivos nuevos y documento Fase 1 se conservaron. No se modificaron otros servicios, contratos o Compose por este agente. No se iniciaron contenedores ni se accedió a secretos privados.

## 9. Próxima fase recomendada

Revisar correlación/autoridad de cierre y contrato outbox; autorizar por separado DB/migraciones y pruebas reales aisladas. Iniciar Betting Worker solo con nueva autorización y contrato de comandos aprobado. Detener esta fase al entregar el informe.
