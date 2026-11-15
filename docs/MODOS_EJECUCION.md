# Modos de ejecución y configuración local

Este documento distingue el MVP Compose, el backend durable ejecutado en el host
y el modo histórico `memory`. No intercambiar sus defaults. Las instrucciones
de arranque son para una ejecución futura autorizada: la Fase 9C no inició
infraestructura ni cambió credenciales o puertos publicados.

| Parámetro | MVP Compose existente | Durable en el host | Histórico memory |
|---|---|---|---|
| Dashboard | Contenedor 8080 → loopback 18080 | 8080 por defecto | 8081 por defecto |
| Productor | Contenedor 8080 → loopback 18081 | Configurar PORT=18081 si comparte host con dashboard | Opcional; configurar por proceso |
| Rabbit AMQP | Interno rabbitmq:5672; host 5673 | localhost:5673 por defecto | localhost:5672 por defecto |
| Rabbit administración | Host 15673 | Según el broker autorizado | Según el broker autorizado |
| PostgreSQL | Interno postgres:5432; host 55434 | URL dashboard localhost:55434/live_jvg | Deshabilitado para el dashboard |
| Kafka | Interno kafka:9092; host 19092 | Configurar KAFKA_BOOTSTRAP_SERVERS=localhost:19092 | Según la infraestructura elegida |
| Perfil dashboard | Sin memory | Sin memory | SPRING_PROFILES_ACTIVE=memory |
| Escritura | SIMULATOR_WRITE_ENABLED=true explícito en ambos servicios | false por defecto; habilitar solo en entorno local autorizado | El controlador durable de simulación no está activo |

## Precedencia y fuente de valores

- Los ajustes comunes del dashboard están en `application.properties`.
  `application.yml` conserva únicamente los namespaces propios de dashboard,
  usados también por memory. Ya no duplica server.port ni el puerto Rabbit.
- En la expresión del puerto se prioriza `DASHBOARD_PORT`, después `PORT` y el
  default del modo. Las fuentes externas de Spring para `server.port`, como
  `SERVER_PORT` o `--server.port`, mantienen su precedencia normal.
- Memory sobrescribe solo lo necesario: sin autoconfiguración JDBC/Flyway,
  puerto 8081 y Rabbit 5672. Conserva overrides explícitos.
- Compose fija PORT=8080 en productor y dashboard. Sus mappings del host siguen
  siendo 18081 y 18080. `scripts/start-mvp.sh` espera esos mismos puertos; no se
  cambiaron mappings, volúmenes, roles o credenciales.
- Los `.env.example` son plantillas. Spring Boot no los carga automáticamente.
  No exportar un PORT global para dos procesos Java que necesiten puertos distintos.
- `.env.example` raíz referencia endpoints del MVP desde el host. Los nombres
  POSTGRES_* heredados no configuran por sí solos los datasource actuales.
  Usar DB_USERNAME/DB_PASSWORD y MATCH_STATE_DB_USERNAME/MATCH_STATE_DB_PASSWORD
  con los valores autorizados existentes; no se incluyen valores aquí.

## Durable en el host

Ejecutar cada aplicación en un proceso independiente y suministrar conexiones
autorizadas. Para el dashboard, usar `DASHBOARD_DB_URL`; para estado,
`MATCH_STATE_DB_URL`. Si se reutiliza el PostgreSQL del MVP desde el host, la URL
de ambos apunta a `jdbc:postgresql://127.0.0.1:55434/live_jvg`, con sus schemas
Flyway separados. No se provisiona ni migra esa base durante una prueba local
sin autorización.

El gateway del dashboard apunta por defecto a `http://localhost:18081`.
Configurar ese puerto en el productor del host o definir PRODUCER_URL explícito.
Para publicar y procesar, MATCH_ID/HOME_TEAM_ID/AWAY_TEAM_ID deben coincidir en
productor, estado y dashboard. El ID generado por el script MVP no se deduce a
partir del default `match-001` del frontend histórico.

Para inspección sin conexiones reales, usar únicamente el perfil memory y
deshabilitar explícitamente listener, declaración dinámica Rabbit y su indicador
de salud; eso no valida PostgreSQL ni mensajería:

```text
--spring.profiles.active=memory
--spring.rabbitmq.listener.simple.auto-startup=false
--spring.rabbitmq.dynamic=false
--management.health.rabbit.enabled=false
```

## Frontend React

VITE_WS_URL sigue siendo el override de máxima prioridad. Como alternativa,
definir VITE_DASHBOARD_ORIGIN y omitir VITE_WS_URL: se deriva `/ws` y el esquema
ws/wss sin adivinar otro puerto.

| Modo de backend | Ejemplo VITE_DASHBOARD_ORIGIN | VITE_MATCH_IDS |
|---|---|---|
| Compose | http://127.0.0.1:18080 | ID real de GET /api/matches |
| Durable host | http://127.0.0.1:8080 | ID configurado/registrado |
| Memory | http://127.0.0.1:8081 | IDs de los eventos del entorno de prueba |

Sin ninguna de esas variables, React conserva su default memory de puerto 8081
para no romper clientes anteriores. No es autodetección del MVP. Las variables
VITE se aplican al arrancar Vite o generar el bundle, no al reiniciar solo Java.
VITE_API_URL de la plantilla raíz es una referencia histórica y no es leída por
el cliente STOMP actual. Nunca introducir credenciales en variables VITE.

## Timeouts del gateway

| Variable | Default | Semántica |
|---|---|---|
| PRODUCER_CONNECT_TIMEOUT | 2s | Límite de establecimiento de conexión HTTP |
| PRODUCER_READ_TIMEOUT | 5s | Límite de espera de lectura, incluidos headers/cuerpo |

Se exigen valores de al menos 1 ms y representables en milisegundos int. El timeout
de lectura es por espera bloqueante: no representa un deadline absoluto frente
a un emisor que envíe fragmentos continuamente. Tampoco certifica un límite del
resolvedor DNS del sistema.

Un timeout produce resultado incierto y no crea un nuevo eventId. El gateway no
añade reintentos de aplicación. Los 4xx de rechazo conocidos mantienen su estado;
los errores de transporte mantienen 503. Los cambios no convierten HTTP 202 en
confirmación de procesamiento downstream ni modifican los estados de H03/H04.

## Seguridad y validación

### Restricción de escalado del MVP (H06, fase 9D)

El MVP admite **una sola instancia de dashboard**, tanto durable como memory.
La difusión STOMP usa `enableSimpleBroker` y es **local a una instancia**.
RabbitMQ transporta ScoreUpdate; no distribuye los mensajes STOMP entre JVM.
Con dos dashboards consumiendo la misma cola, RabbitMQ reparte las entregas:
los clientes conectados a la otra instancia pueden perder actualizaciones en
vivo aunque ambos procesos lean la misma proyección PostgreSQL. La afinidad de
sesión del balanceador no soluciona este problema.

Compose publica el dashboard en un puerto fijo de loopback (18080). Esto impide
normalmente iniciar dos réplicas con ese mismo mapping en un mismo host, pero
**no constituye exclusión distribuida**: otros puertos, hosts o proyectos pueden
eludirlo. No usar `--scale dashboard=2`, réplicas de orquestador ni balanceo entre
dashboards. La restricción debe conservarse también fuera de Compose.

Compose declara `deploy.replicas: 1` y `container_name: live-jvg-mvp-dashboard`.
El nombre fijo hace que Compose rechace escalar ese servicio a más de un
contenedor. También impide dos copias con ese nombre en el mismo Docker daemon;
no impide arrancar JVM manuales u otros hosts. No se admite despliegue Swarm ni
ningún orquestador que omita esta restricción. No se introduce un broker STOMP
distribuido, dependencia o servicio adicional. No existe soporte de escalado
horizontal. Referencia: [Compose container_name](https://docs.docker.com/reference/compose-file/services/#container_name).

### Época durable y restauración (M09)

El dashboard durable aplica una migración aditiva V2 que almacena un UUID en
`dashboard.stream_epoch`. No cambia en reinicios ordinarios. REST/STOMP mantiene
campos y tipos; el valor stream_id pasa a `durable-<epoch>-<matchId>`. La proyección
memory conserva su identidad volátil. Los clientes React existentes aceptan el
stream nuevo al recuperar una conexión, aunque su revisión sea inferior.

Después de restaurar o reconstruir la base se debe rotar la época explícitamente
con todos los dashboards detenidos. No hay endpoint HTTP de rotación ni rotación
automática al arrancar. Una copia restaurada también restaura el UUID antiguo:
omitir la rotación puede hacer que los clientes descarten snapshots válidos.
El [procedimiento de migración, rotación y rollback de 9D](FASE_9D_CONCURRENCIA_OUTBOX_RENDIMIENTO.md)
describe el script administrativo, la revisión previa y las pruebas pendientes.

Todos los puertos de Compose permanecen publicados solo en loopback. La
habilitación explícita del simulador local no es autenticación y no es adecuada
para exposición pública. No se cambió ninguna credencial.

ExecutionModesTest resuelve la configuración real de Spring sin crear JDBC,
Rabbit ni servidor HTTP. LocalMvpConfigurationTest inspecciona Compose y sus
puertos sin ejecutar Docker. Estas pruebas no acreditan infraestructura activa.
