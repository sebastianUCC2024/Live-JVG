# DEV 1: arranque durable pasivo local V1

Base: `ee7e3db30ae3f33e1af9ef3154afe05761ba0a8d` (`origin/dev` local).
Rama: `dev1/passive-local-v1`, en el clon existente, sin otro worktree.

## Diseño y controles independientes

| Control | Valor por defecto | Propuesta OFF | Efecto |
| --- | --- | --- | --- |
| `MATCH_STATE_CATALOG_BOOTSTRAP_ENABLED` | true | false | Evita registrar el catálogo JDBC al crear el store. |
| `MATCH_STATE_RECOVERY_ENABLED` | true | false | Evita consultar/reconstruir historias y crear el runner que drena outbox. |
| `MATCH_STATE_PUBLICATION_ENABLED` | true | false | Sustituye el adaptador Rabbit por un publicador que lanza error; nunca confirma entregas ficticias. |
| `KAFKA_LISTENER_AUTO_STARTUP` / `SPRING_KAFKA_LISTENER_AUTO_STARTUP` | true | false | Mantiene el listener detenido y evita crear el AdminClient de identidad Kafka. |
| `SPRING_FLYWAY_ENABLED` | Spring Boot: true | false | No crea Flyway ni ejecuta migraciones. |
| `SPRING_RABBITMQ_DYNAMIC` | Spring Boot: true | false | No crea RabbitAdmin, por tanto no declara exchange/colas/bindings. |
| `SPRING_SQL_INIT_MODE` | Spring Boot | never | No ejecuta inicialización SQL alternativa. |
| `SPRING_KAFKA_ADMIN_AUTO_CREATE` | Spring Boot: true | false | Desactiva la creación automática de topics por KafkaAdmin. |

El store y el ingreso durable conservan sus implementaciones. En modo pasivo no se invocan
sus operaciones de arranque. No se cambia el perfil a `memory` ni se sustituye el outbox por
uno volátil. Los objetos de conexiones pueden existir, sin conexión de arranque en la prueba.

En durable, `recovery=true/publication=false` falla al crear la política.
`listener=true` exige `recovery=true` y `publication=true`. El catálogo puede estar OFF en
modo activo si fue aprovisionado antes. Listener OFF con recuperación y publicación ON
sigue drenando outbox, como antes: detener sólo el listener no significa modo pasivo.
La validación es dependencia del almacenamiento durable y del publicador. La configuración
normal conserva registro, validación de historia, recuperación y arranque posterior de Kafka.
Un fallo de recuperación conserva Kafka detenido.

## Propuesta Compose

Sólo cambia `scripts/e2e/compose.manifest-proposed.yml`; el Compose base conserva sus valores.
El overlay sobrescribe explícitamente los simuladores Producer/Dashboard heredados en ON,
los listeners Kafka y Rabbit simple/direct, Flyway, SQL init, KafkaAdmin y RabbitAdmin.
Los controles existentes de Archiver, Suspension, Worker y Notification permanecen OFF;
no se modifica su código, autoridad, aceptación ni leases.

Cada servicio Java recibe `depends_on: !reset {}` para eliminar dependencias heredadas y
transitivas, incluido `match-state -> dashboard` y las rutas hacia `kafka-init`.
`kafka-init` queda detrás de `provisioning-explicit`, separado de `integration-prepared`.
El inspector de configuración incluye ambos perfiles exclusivamente al resolver `config`
y verifica que todas las dependencias y controles pasivos están efectivamente OFF.

Esto es una propuesta de configuración, no un permiso operativo. No certifica arranque
durable global OFF de todos los servicios ni levanta los bloqueos de DEV 2/DEV 3.
Las definiciones de infraestructura del Compose base siguen pudiendo aprovisionar bases,
vhosts y recursos al arrancarse. No debe interpretarse un arranque completo del Compose,
`--profile '*'`, ni seleccionar explícitamente `kafka-init` como arranque pasivo.
La propuesta contempla servicios de aplicación seleccionados y recursos previamente
aprovisionados por sus responsables. El ejemplo `compose.owner-prepared.example.yml`
por sí solo no contiene estos controles y no reemplaza el overlay del manifiesto.

## Diff y archivos

- `StartupPolicy.java`: tres controles independientes y rechazo de combinaciones incoherentes.
- `DurableStorageConfiguration.java`: gates de catálogo, validación/recuperación y AdminClient.
- `MessagingConfiguration.java`: política y rechazo de publicación cuando está OFF.
- `application.properties`: aliases de variables con defaults true.
- `compose.manifest-proposed.yml`: propuesta pasiva y eliminación de dependencias de inicio.
- `validate-e2e-proposal.py`: verifica los controles del modelo resuelto; no se ejecutó Docker.
- `PassiveStartupTest.java`: matriz de 16 combinaciones, contexto durable OFF, aliases,
  independencia catálogo/recuperación, publicador ON/OFF, orden de recuperación y fallo seguro.
- `ComposeOffProposalTest.java`: parsea ambos YAML con SnakeYAML ya disponible, comprueba
  herencia de variables, tags `!reset`/`!override` y controles OFF sin ejecutar Compose.
- Este documento: alcance, diseño y evidencia.

No cambian eventos, migraciones, ACL, código de Worker, autoridad ni leases.

## Validación offline

Comando ejecutado en este clon (caché existente, sin descargas):

```powershell
mvn -o '-Dmaven.repo.local=C:/Users/JUANSE/.m2/repository' -f services/match-state-service/pom.xml test -q
```

Resultado final: **119 tests, 0 fallos, 0 errores** (7 tests nuevos, incluida la matriz
de 16 combinaciones). `git diff --check` y el parseo sintáctico offline del inspector Python
también pasan.

Las integraciones externas están excluidas por la configuración existente de Surefire.
Las pruebas nuevas usan mocks para JDBC/transacciones, Rabbit y registro de listeners;
ninguna ejecuta SQL ni arranca clientes Kafka reales. El contexto pasivo comprueba
la ausencia de Flyway, RabbitAdmin y KafkaInputSource, listener detenido y cero interacciones
con DataSource. El test de recuperación activa usa sólo mocks y comprueba el orden de llamadas.
El parseo offline del YAML no equivale a validar con el motor Compose instalado.

No se ejecutaron Docker, SQL, migraciones, Kafka, RabbitMQ, consumidores reales ni aceptación.
No se hizo push ni PR. En este clon no existe la referencia `dev1/architecture-closure`
ni aparecen sus cinco eliminaciones staged; no se modificó ni reparó ningún worktree anterior.
El índice inicial del clon estaba limpio.
