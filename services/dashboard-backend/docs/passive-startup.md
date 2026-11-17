# Arranque pasivo del Dashboard durable

El perfil `passive` permite validar un catálogo ya provisionado sin inicializarlo.
No constituye autorización para iniciar infraestructura o ejecutar migraciones.

## Configuración propuesta

Seleccionar `SPRING_PROFILES_ACTIVE=passive` solo en el entorno aprobado, manteniendo
las variables existentes de conexión, partido, equipos y nombres. No combinarlo con
`memory`: este perfil valida el catálogo PostgreSQL durable.

El archivo `application-passive.properties` establece explícitamente:

| Propiedad | Valor |
|---|---|
| dashboard.catalog.bootstrap-enabled | false |
| simulator.write-enabled | false |
| spring.flyway.enabled | false |
| spring.sql.init.mode | never |
| spring.rabbitmq.listener.simple.auto-startup | false |
| spring.rabbitmq.listener.direct.auto-startup | false |
| spring.rabbitmq.dynamic | false |
| management.health.rabbit.enabled | false |

Revisar que argumentos, propiedades directas SPRING_* o configuración externa de
mayor prioridad no reemplacen estos valores. El perfil es configuración explícita,
no una frontera de seguridad contra overrides administrativos o invocaciones
programáticas. No declara colas ni inicia listeners bajo estos valores.

## Catálogo

Por defecto `dashboard.catalog.bootstrap-enabled=true`, configurable mediante
`DASHBOARD_CATALOG_BOOTSTRAP_ENABLED`. El modo normal conserva INSERT idempotente,
validación de IDs de equipo y actualización de nombres.

Con bootstrap deshabilitado, el constructor de ScoreProjection ejecuta únicamente
SELECT por match_id y valida IDs y nombres. Un partido ausente, nombres o equipos
distintos, o la imposibilidad de consultar el esquema provocan fallo de arranque
con diagnóstico; no se crean ni reparan registros. Es necesario provisionar antes
el esquema y catálogo mediante un procedimiento autorizado independiente.

Deshabilitar solo el bootstrap no apaga listener, simulador, Flyway o RabbitAdmin;
usar el conjunto explícito del perfil. El usuario PostgreSQL de esta fase puede
restringirse a los SELECT necesarios, como defensa adicional coordinada con el DBA.
El servicio conserva consultas REST y STOMP: estas requieren también los objetos
persistidos correspondientes, incluyendo stream_epoch e inbox. Validar el catálogo
al iniciar no certifica que todo el esquema esté completo ni que el servicio esté
preparado para consumir. La comprobación Rabbit se excluye del health en modo pasivo.

No se cambia receive(), snapshots, inbox, revisiones, publicación STOMP, marcador,
VAR ni recuperación. No afecta a los demás servicios ni los convierte en OFF.

## Verificación offline

- PassiveCatalogBootstrapTest: preservación del bootstrap normal; SELECT exclusivo
  en modo pasivo; discrepancias en ambos IDs y nombres; catálogo ausente; error
  de acceso al esquema. JdbcTemplate y el transaction manager son dobles.
- PassiveStartupConfigurationTest: contexto web Spring sin servidor HTTP, datasource
  y JDBC simulados; carga del perfil real, proyección durable, ausencia de Flyway
  y RabbitAdmin, listener no iniciado y ausencia de conexiones al datasource doble.
- Maven verify normal conserva todas las pruebas locales; el perfil PostgreSQL
  externo no se activa. Ninguna prueba nueva necesita PostgreSQL o RabbitMQ.

Estas comprobaciones no prueban permisos reales, PostgreSQL, RabbitMQ ni E2E.
No ejecutar Docker, SQL, migraciones ni consumidores como parte de esta preparación.

Resultado local sobre base ee7e3db: Maven verify offline con JDK 17.0.12 y Maven
3.9.16, 159 pruebas aprobadas, cero fallos, errores u omitidas; BUILD SUCCESS y
empaquetado Spring Boot correcto. Incluye las 150 pruebas previas y nueve casos
nuevos. Log final: target/passive-final-verify.log. Los intentos previos se conservan
en target/passive-*.log: acceso a caché y ajustes del harness de contexto, resueltos
antes de este resultado final. No se ejecutaron las suites externas PostgreSQL.
