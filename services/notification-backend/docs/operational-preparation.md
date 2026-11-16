# Notification — preparación local, CI y entrega a infraestructura

Este documento no autoriza infraestructura ni migraciones. No se modificó el
workflow compartido ni Compose. Los scripts están dentro de este módulo DEV 2.

## Verificación local offline

`scripts/verify-local.ps1` requiere rutas existentes de JDK 17 completo, Maven y
repositorio Maven. Verifica java/javac por sus rutas completas; no ejecuta Wrapper
con descarga implícita, no instala herramientas y siempre pasa `-o` a Maven.

Ejemplo desde la raíz, sustituyendo solo rutas autorizadas ya existentes:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File services/notification-backend/scripts/verify-local.ps1 `
  -JdkHome 'D:\Program Files (x86)\java' `
  -MavenCommand '<Maven existente>\bin\mvn.cmd' `
  -LocalRepository 'D:\Universidad\Live-JVG\services\notification-backend\target\maven-cache'
```

ExecutionPolicy afecta solo al proceso nuevo en ese ejemplo, no a Windows de forma
permanente; omitirlo si la política local ya permite el script. El script no cambia
la política. JAVA_HOME y los overrides eliminados temporalmente se restauran en
el proceso. PATH global no se toca. No acepta argumentos Maven arbitrarios.
Si falta una dependencia o plugin en caché, falla; no cambiar a resolución online
sin aprobación. Tampoco acepta Java 21 como sustituto silencioso de Java 17.

Se eliminan solo del proceso de verificación variables heredadas de infraestructura,
JVG_NOTIFICATION_IT y opciones Java/Maven que podrían alterar el alcance. Se
desactivan almacenamiento, consumo Rabbit, SQL init y Flyway; se desactiva el perfil
notification-integration y el flag de autorización IT. Los reportes anteriores se
copian antes de ejecutar, sin borrar evidencia. El resultado usa solo XML renovados
durante la ejecución; un reporte antiguo no produce un falso aprobado.

Salida: `target/local-verification-<fecha>/verify.log`, reportes Surefire y
summary.json con conteos, código de salida y external_integration=NOT_EXECUTED.
Un error Maven, cero pruebas, error/fallo de tests o reporte externo inesperado
produce salida distinta de cero. No invocar el script desde una shell que deba
mantener un contexto Maven o credenciales de infraestructura activas.

## Preflight de entradas aisladas

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File services/notification-backend/scripts/test-integration-inputs.ps1
```

Este preflight únicamente lee el entorno del proceso y verifica presencia y
patrones de entradas, puertos excluidos, destino exclusivo, credenciales no
triviales y existencia de un path de manifiesto bajo `.9f/<run>/`. No genera
credenciales, IDs, manifiestos o recursos. No imprime valores secretos. Falta de
entradas devuelve `BLOCKED_BEFORE_INFRASTRUCTURE` y código 2.

Un resultado `INPUT_SCREEN_ONLY` **no significa READY ni autorización**: no analiza
la aprobación, resuelve symlinks como Java, comprueba hashes/ownership ni consulta
Docker, PostgreSQL o Rabbit. IntegrationGate/IntegrationSettings de Java siguen
siendo la autoridad y deben validar manifiesto vigente, paths reales, acciones,
fuentes, compilados/JAR, migración y contenedores antes de abrir las fixtures.
El preflight no ejecuta ni envuelve las IT y no puede saltarse sus guardas.

No existe `.9f` autorizado ni harness `tests/9f` en el checkout auditado. Esto
bloquea las IT; no autoriza recrear un stack ni usar recursos MVP por conveniencia.
Las suites, manifiesto y acciones exactas están en
[Fase 3B.4](phase3b4-isolated-tests.md). No ejecutar NotificationPostgresIT ni
NotificationRabbitIT hasta que DEV 1 e infraestructura aprueben recursos/acciones.
Su setup puede ejecutar Flyway: autorizar consumo solamente no autoriza ese DDL.

Pruebas del script sin infraestructura:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File services/notification-backend/scripts/test-preparation-scripts.ps1
```

Usan únicamente fixtures inválidos: ausencia, overrides heredados, puerto nativo,
base no exclusiva, endpoint remoto, reutilización de credenciales, manifiesto
ausente y herramientas ausentes. No se escriben manifiestos ni datos de pruebas.
Estos checks no certifican las guardas reales contra un broker o una base.

## Propuesta CI para DEV 1

[proposed-notification-ci-job.yml](proposed-notification-ci-job.yml) es un artefacto
para revisión, no un workflow activo. CI actual incluye tres backends Java y no
Notification. La propuesta añade un job Java 17 que ejecuta verify, no permite
continuar ante regresiones y conserva reportes incluso tras fallos.

El fragmento reutiliza checkout/setup-java v6 del workflow observado y propone
upload-artifact v7, versión mostrada por su [documentación oficial](https://github.com/actions/upload-artifact).
DEV 1 debe revisar permisos, runner y fijar SHAs de acciones antes de incorporarlo.
No se envió ningún mensaje ni se editó .github/workflows.

El job futuro permite resolución Maven normal como el workflow actual: un runner
nuevo sin caché necesita descargar dependencias. Eso exige la política de red y
repositorios aprobada por DEV 1. No ocurrió ninguna descarga durante esta tarea.
Si se exige CI totalmente offline, DEV 1 debe provisionar previamente caché con
Boot 4.1.2 y todos sus plugins; cache miss debe fallar, no omitir tests. El script
local permanece offline independientemente de esa decisión futura de CI.

## Entrega al Compose de DEV 1

- El Dockerfile local propuesto del módulo requiere el build arg JAVA_RUNTIME_IMAGE
  con runtime Java 17 Linux aprobado por DEV 1, preferiblemente por digest. Ejecuta
  el JAR como UID/GID 10001; no implica permiso para hacer build o pull de imágenes.
- Dentro del contenedor se necesita NOTIFICATION_BIND_ADDRESS=0.0.0.0; loopback
  del host sigue siendo el valor predeterminado seguro para ejecución local.
- NOTIFICATION_PORT debe coordinarse; 8082 también aparece en Archiver. No publicar
  puertos de todos los servicios indiscriminadamente ni usar localhost para DB/
  Rabbit entre contenedores. DEV 1 proporciona DNS/nombres y puerto interno real.
- DB_URL/USERNAME/PASSWORD y host/port/username/password de Rabbit pertenecen al
  entorno autorizado. No colocarlos en Dockerfile, build args, variables VITE_ o Git.
- Runtime puede usar SPRING_RABBITMQ_VIRTUAL_HOST para un vhost explícito aprobado;
  las IT rechazan herencia SPRING_* y exigen su namespace JVG_NOTIFICATION_IT_.
- Exchange, cola independiente, match IDs y bindings deben ser consistentes con
  Match State. No reutilizar mvp-dashboard-scores ni consumir como competidor.
- NOTIFICATION_ALLOWED_ORIGINS admite los orígenes exactos aprobados del frontend;
  `localhost` y `127.0.0.1` no equivalen. La URL de browser usa host publicado,
  no el DNS interno de Docker. VITE_NOTIFICATION_ORIGIN nunca contiene secretos.
- Almacenamiento y consumo siguen deshabilitados hasta que la migración separada
  V1 esté aprobada y aplicada por el responsable. Flyway de aplicación sigue off.
- Liveness `/actuator/health/liveness` verifica proceso; readiness
  `/actuator/health/readiness` requiere almacenamiento y consumidor operativos.
  Un proceso iniciado con dependencias desactivadas no debe figurar listo para E2E.
- DEV 1 coordina artefactos y orden de arranque; infraestructura/DEV 3 autoriza
  bases/esquemas/roles/vhost y migraciones; DEV 2 aporta código, scripts y reportes.
  Probar recuperación, reinicio y DLQ requerirá la ejecución aislada posterior.

Ni esta entrega ni un job verde certifican el flujo real. La arquitectura no añade
correo/SMS, nuevas APIs, almacenamiento compartido ni motor de revisiones VAR.
