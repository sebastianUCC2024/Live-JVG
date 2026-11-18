# Cierre autorizado de dependencias y validaciones locales

Rama: integration/local-academic-v1. Base de este cierre: 1716e1b25a378eed82c43e89b09f871c13050909.
Las ramas publicadas y dev no se modificaron. No se inició ni utilizó infraestructura real.

## Procedencia y versiones

- El POM declara Spring Boot 4.1.2 y no contiene repositorios añadidos. No se modificó el POM ni lockfiles.
- La URL oficial `https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/4.1.2/spring-boot-starter-parent-4.1.2.pom`
  devolvió HTTP200; el metadata oficial también lista esa versión. Hash y URL en evidence/central-verification.json.
- `mvn -B -ntp -U -Dmaven.repo.local=/private/tmp/live-jvg-archiver-m2 -f services/notification-backend/pom.xml verify`
  resolvió las dependencias y pasó. Algunas consultas de metadata a repositorios Flyway existentes dieron 401,
  no bloquearon la resolución desde Central y no se añadieron credenciales ni mirrors.
- Se repitió `verify -o` con el mismo repositorio local y POM original, tras las correcciones de pruebas.
  El JAR contiene BOOT-INF/lib/spring-boot-4.1.2.jar. No se usó 4.1.1 como sustitución.
- Python: `/private/tmp/live-jvg-validation-venv`, Python3.9.6, include-system-site-packages=false.
  Instalación exclusiva desde PyPI oficial: fastjsonschema2.21.2, ruamel.yaml0.19.1, jsonschema4.25.1 y
  rfc3339-validator0.1.4, más sus dependencias. [python-packages.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/docs/integration/local-academic-v1/dependency-close/evidence/python-packages.txt) es evidencia histórica archivada en Git, retirada del árbol actual; no es un lockfile de aplicación. Las dependencias vigentes se declaran en `requirements-validation.txt` en la raíz.
  No se actualizaron pip ni paquetes globales.

## Resultados reales

| Validación | Resultado |
|---|---|
| Notification online inicial | 116 aprobadas, 0 fallos, 0 errores, 0 omitidas |
| Notification offline final | 117 aprobadas, 0 fallos, 0 errores, 0 omitidas; verify y package correctos |
| Otros seis módulos | 752 aprobadas y 22 omitidas del cierre anterior, fuentes funcionales sin cambios |
| Total local Java combinado | 869 aprobadas, 22 omitidas, 0 fallos/errores finales |
| Draft07 propuesto | 4 esquemas / 20 fixtures positivos y negativos verificados |
| Draft2020-12 | 2 esquemas / 3 wire fixtures reales de records productores y 2 golden fixtures académicos |
| MVC Notification → guardas TypeScript | PASS con la respuesta real MockMvc generada por la suite actual |
| Python | 94 tests: 86 anteriores repetidos en venv + 4 UUID + 4 Draft2020-12 |
| Node Dashboard | 34 aprobadas, 0 fallos, 0 omitidas |
| React | 143 aprobadas y build correcto del cierre anterior; código frontend no modificado |

Los tres fixtures LiveAlert se generan a partir de las clases reales de Match State mediante la prueba de
compatibilidad; no son mensajes observados en RabbitMQ ni una secuencia deportiva ejecutada. Los dos
académicos son fixtures golden existentes idénticos en ambos módulos. Se valida también coherencia de los
cinco campos envelope/snapshot. Esto no convierte contratos propuestos en aprobados ni demuestra E2E.

## Defectos corregidos sin alterar comportamiento productivo

1. El validador Draft07 original fallaba al compilar `format: uuid`. Se reprodujeron tres errores en RED.
   Ahora registra un formato UUID canónico explícito, conserva date-time y rechaza pasar un esquema
   Draft2020-12 a un motor que no lo soporte. Cuatro regresiones pasan; no se relajaron contratos.
2. Se añadió validación Draft2020-12 con formatos efectivamente activos para el contrato LiveAlert y el
   contrato académico local. Sus regresiones rechazan UUID/fechas/versiones/campos extra inválidos y
   discrepancias de identidad envelope/snapshot que la descripción del esquema delega al DTO.
3. NotificationPostgresIT esperaba únicamente las tablas V1 aunque su fixture Flyway carga también V2.
   Se actualizó la expectativa a cinco tablas (incluida history) y las dos versiones. El nuevo test local
   NotificationMigrationInventoryTest inspecciona los SQL como texto y verifica el inventario: NO ejecuta DDL.
4. La prueba de compatibilidad exporta sus tres wire fixtures en target para validación cruzada. No cambia
   el DTO, el publicador, la aceptación, las migraciones ni las banderas de integración.

Las dos clases PostgreSQL/Rabbit de Notification siguen fuera del ciclo local: son 13 métodos *IT excluidos,
no omitidos contados por Surefire ni aprobados por estas pruebas. Su expectativa corregida todavía necesita
validarse contra PostgreSQL real cuando exista autorización.

## 22 omitidas y límites operativos

Inventario completo con clases, métodos, líneas y causas en ../skipped-tests.md y evidence/skipped-tests.json.
19 PostgreSQL, 2 Kafka, 1 RabbitMQ; cero convertibles honestamente en PASS offline. Los 14 de atomicidad
incluyen fixtures positivos de autoridad/grants/ACCEPTED y no pertenecen al ensayo académico de cero grants.
Una de las otras ocho, la legacy de Control, también crea un lease sintético y abre mercados: queda igualmente excluida del ensayo estándar. Las otras siete solo son candidatas bajo autorización de infraestructura y bases separadas.
No se activaron sus variables de opt-in. No se usaron mocks como certificación de esas pruebas reales.

Continúan pendientes: aprobación de contratos/metadato y alcance annuls_event_id/reference_event_id,
provisión exclusiva de recursos nuevos, 17 migraciones/owners/roles, ACL/vhosts, PASSIVE/OFF real, activación
académica separada, dos consumidores reales, recuperación/DLQ/reinicios y observación REST/STOMP/React.
ORDER02 global permanece abierto; el modo estricto sigue opt-in y no certificado con infraestructura.
No hay autorización para Docker, SQL, brokers, E2E, push, PR ni merge a dev. No se declara el proyecto terminado.
