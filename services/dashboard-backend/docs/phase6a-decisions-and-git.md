# Fase 6A — Decisiones bloqueantes, responsables y estado Git

**Documento de coordinación, no autorización de implementación o ejecución.**
Consolida la [matriz de contratos](phase6a-contract-matrix.md), la
[propuesta VAR](phase6a-var-v2-proposal.md), los
[criterios verificables](phase6a-acceptance-criteria.md) y el
[plan aislado](phase6a-isolated-integration-plan.md).

## 1. Registro de decisiones pendientes

| ID | Decisión y propuesta mínima | Quién debe aprobar | Qué bloquea / criterio para desbloquear |
| --- | --- | --- | --- |
| D01 | Formalizar MatchEvent, ScoreUpdate y LiveAlert existentes antes de evolucionar campos; acordar obligatoriedad/nullable, IDs opacos, timestamps, enteros, desconocidos y versionado | DEV 2 coordina; DEV 1 dueño de entradas/salidas deportivas; DEV 3 valida archivado; equipo ratifica | Esquemas compartidos y aceptación contractual final; aprobar snapshot por commit para laboratorio V1 sin editar schemas unilateralmente |
| D02 | Elegir P0/P1/P2, revisiones generales/simultáneas, cierre dirigido, referencias desconocidas/cerradas y anulación directa; mantener V1 | Equipo completo; DEV 1 implementaría motor, DEV 2 lectura/escritura/presentación, DEV 3 sus efectos internos | Escritura/semántica P2, versionado de reglas y cualquier activación V2; acta explícita con escenarios y alcance por partido |
| D03 | Identidad de negocio y de fuente después de restaurar; conservar payload outbox original; política de épocas de dashboard/cursor y source_id del archivador | DEV 1, DEV 2, DEV 3 e infraestructura según recurso | Restore/replay y garantías entre épocas; especificar IDs reutilizables/prohibidos, comparación de contenido, rotación y rollback sin asumir limpieza automática |
| D04 | Orden de ScoreUpdate por partido durante envío, fallos y recuperación; definir garantía del productor/outbox o evolución contractual aprobada | DEV 1 y DEV 2; DEV 3 revisa la semántica compartida | Criterio ORDER02 y certificación anti-regresión ante mensajes Rabbit nuevos fuera de orden; timestamps/revision local no bastan |
| D05 | Harness vigente, ownership 9F, manifiesto y acciones; reutilizar recursos aprobados si son compatibles | DEV 1 e infraestructura con DEV 3; DEV 2 revisa guardas de sus suites | Cualquier prueba externa; tests/9f ausente en este checkout no autoriza crear otro stack ni omitir controles |
| D06 | Bases/esquemas/roles exclusivos y ejecución Flyway V1 notification, V1/V2 dashboard; alcance de fallos/reinicios | Infraestructura/DEV 3 autoriza recursos; DEV 2 propone sus migraciones; DEV 1 coordina laboratorio | Setup de IT que hace DDL, escrituras SQL y pruebas de reinicio; hashes, permisos, inventario y acciones aprobados antes de activar |
| D07 | Topic/grupos Kafka, vhost/exchange/bindings Rabbit y colas/DLX/DLQ independientes; aislamiento del flujo conjunto | DEV 1 para origen; infraestructura provisiona; DEV 2 sus colas; DEV 3 su archivador | Consumo/publicación real; confirmar dos copias Rabbit y grupos Kafka separados; no reutilizar cola dashboard como competidor |
| D08 | Puertos, URLs, advertised listeners y CORS exacto; distinguir host/contenedor y localhost/127.0.0.1 | Infraestructura/DEV 3 y cada responsable de servicio | Arranque conjunto y navegador real; resolver colisión default 8082 Notification/Archiver, sin inventar asignaciones ni credenciales |
| D09 | Redacción de alertas con IN_PROGRESS, supresión MATCH_CONTINUES, ventana/retención y alcance de partidos | DEV 2 propone; equipo aprueba semántica; infraestructura retención | Aceptación UX P2/retención; no anunciar cierre global ni generar alerta nueva hasta acuerdo; no bloquea por sí sola IT técnica V1 acotada |
| D10 | Actualización autorizada de rama y PR documental, distinguiendo código ya integrado | Usuario y coordinación del equipo | Operaciones Git futuras; no duplicar Fase 5D ni actualizar rama automáticamente |

Ninguna fila figura aprobada por este documento. Anotar fecha, responsable,
referencia de revisión, decisión exacta y límites antes de ejecutar el siguiente
paso dependiente. Aprobar contratos no autoriza migraciones ni fallos provocados;
aprobar un laboratorio no aprueba políticas deportivas.

## 2. Riesgos concretos

1. Los cuatro JSON Schema compartidos tienen 0 bytes: no hay validación contractual
   formal distribuida. Los codecs actuales estrictos pueden rechazar un campo
   nuevo que otro servicio considere aditivo.
2. La igualdad no es uniforme: timestamp textual/nullable normalizados de maneras
   distintas pueden producir conflicto en un servicio y duplicado en otro.
   No reserializar eventos históricos o outbox como estrategia de recuperación.
3. Rabbit entrega ScoreUpdate sin secuencia de origen. La revisión de dashboard
   protege al navegador frente a frames obsoletos del mismo stream, pero no frente
   a un marcador anterior recibido como un evento nuevo por el backend.
4. Girar una época de lectura no restablece identidad de negocio. El source de
   inbox notification es fijo match-state-service; la restauración de datos/IDs
   necesita coordinación y evidencia de no sobrescritura.
5. P2 no puede deducirse de un indicador agregado ni de ventanas de historial.
   El simulador actual y la redacción de Notification requieren acuerdos adicionales.
6. Histórico archivado no certifica aplicación deportiva. No crear una dependencia
   de Dashboard/Notification hacia Archiver para reconstruir marcador o revisiones.
7. JAR válido, tests con dobles y CI verde no certifican transacciones, ownership,
   conectividad, CORS ni E2E real. No se verificó disponibilidad de infraestructura
   en Fase 6A. Guardas incompatibles o ausencia de manifiesto bloquean ejecución.

## 3. Git observado localmente

Inspección sin red, fetch ni sincronización. Estado inicial: **limpio**.

| Referencia local disponible | SHA observado | Interpretación |
| --- | --- | --- |
| HEAD / feature/Gabo-react-integration | 556eefb54755602ebacd5e1e1a8416490420dfaf | Merge del PR #19, fuente de esta propuesta |
| origin/dev | 7eb2de42524c6f65e4e89e7996f001eeec7315c0 | Referencia local del merge PR #20; no prueba actualidad de GitHub |
| origin/feature/Gabo-react-integration | 5ca9f81121a9d2b5616852bc8e9d11c312a5795a | Commit Fase 5D del PR #19; árbol igual al HEAD inspeccionado |
| dev | 9d8aae20a5371d41b18bda7f10bfd66f42f22094 | Rama local atrasada; no usarla como aceptación del dev actual |

`git rev-list --left-right --count HEAD...origin/dev` devuelve 0/2.
La rama no contiene commits exclusivos frente a esa referencia. El diff de PR
`origin/dev...HEAD` está vacío antes de crear estos documentos. Los árboles de
Dashboard Backend/Frontend, Notification, Producer, Match State y contratos son
iguales entre HEAD y origin/dev. Las diferencias posteriores al HEAD pertenecen
a Historical Archiver, Betting Suspension y Betting Worker (PR #20), inspeccionables
como objetos Git pero no incorporadas al árbol de trabajo actual.

Esto descarta conflictos de archivos DEV 2 visibles frente a las referencias
disponibles. No demuestra ausencia de cambios remotos posteriores ni compatibilidad
de ejecución conjunta con DEV 3. No se consultó el estado actual de GitHub o CI.

## 4. Los diez archivos de Fase 5D ya están integrados

El rango local `c1df3f7869680d8ffd07ca78447cfa0fb1c8ae7f..HEAD` incluye exactamente:

1. services/dashboard-backend/README.md
2. services/dashboard-backend/docs/phase5d-publication-recovery-and-var-readiness.md
3. services/dashboard-backend/src/main/java/com/live_jgv/dashboard/ProducerGateway.java
4. services/dashboard-backend/src/main/java/com/live_jgv/dashboard/infrastructure/web/ApiExceptionHandler.java
5. services/dashboard-backend/src/main/resources/static/operations.js
6. services/dashboard-backend/src/test/java/com/live_jgv/dashboard/Dev1LiveWireCompatibilityTest.java
7. services/dashboard-backend/src/test/java/com/live_jgv/dashboard/ProducerGatewayRecoveryTest.java
8. services/dashboard-backend/src/test/js/app.test.cjs
9. services/dashboard-backend/src/test/js/operations.test.cjs
10. services/dashboard-frontend/src/components/VarReadCompatibility.test.tsx

Están presentes e iguales en la referencia origin/dev observada. Se conserva la
línea que compila EventPreparationException y las aserciones existentes. **No hay
un PR Fase 5D pendiente que deba prepararse primero**: el PR #19 ya consta fusionado
en la historia local. La recomendación de PR del informe 5D es histórica.

## 5. Entrega de Fase 6A y siguiente paso

Solo se crean estos cinco Markdown, bajo services/dashboard-backend/docs:
phase6a-contract-matrix.md, phase6a-var-v2-proposal.md,
phase6a-acceptance-criteria.md, phase6a-isolated-integration-plan.md y
phase6a-decisions-and-git.md. No se modifica README, código productivo, schemas,
tests, POM, configuración, Docker, CI o servicios ajenos. No se ejecutan suites
externas, instalaciones, servicios ni migraciones. Verificación: git diff --check
y comprobación local de enlaces/alcance de los documentos.

Próximo paso: revisión conjunta de D01/D03/D04 y alcance V1 del laboratorio;
resolver D05..D08 antes de autorizar IT. Revisar D02/D09 en un acta VAR separada.
Coordinar D10 antes de actualizar la rama o publicar. Si se aprueba un PR de Fase
6A hacia dev, incluir exclusivamente los cinco documentos nuevos; no duplicar los
diez archivos ya integrados. No se hace commit, push ni merge en esta fase.
