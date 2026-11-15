# L01 — Validación de módulos reales y CI

## Maven local

El POM raíz es un descriptor pom, no el parent de los servicios Spring Boot.
Sin perfil no agrega módulos ni ejecuta sus pruebas. Se incorpora audit-services
como agregación explícita de productor, estado y dashboard, conservando los
parents Boot 3.5.7 de cada módulo y sin tocar los cuatro servicios secundarios.

```powershell
mvn -Paudit-services test
```

Este comando ejecuta los tres módulos; no equivale al anterior mvn test/verify
en raíz sin perfil. El repositorio local de esta máquina está en .m2/repository:

```powershell
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -Paudit-services test
```

No activar perfiles integration-* durante la validación local segura. Ver
[selección y exclusiones durables](PRUEBAS_INTEGRACION_DURABLE.md). El reactor
local ejecuta contextos de prueba memory y mocks, no los servicios distribuidos
del despliegue. No se afirma integración real con un verify raíz vacío.

## Empaquetado aislado sin sobrescribir JAR existentes

Los tres servicios incorporan el perfil isolated-package, que cambia únicamente
project.build.directory. Su default crea un subdirectorio target/audit-fecha;
para mayor control se debe suministrar una carpeta nueva única y comprobar que
no exista. No ejecutar clean ni reutilizar directorios si se quiere conservar
todos los artefactos. Ejemplo para un servicio, después de pasar la suite completa:

```powershell
$module = 'live-feed-producer'
$outputDir = Join-Path $PWD ('target/audit-' + $module + '-' + [guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $outputDir) { throw 'La salida ya existe' }
mvn -f "services/$module/pom.xml" -Pisolated-package "-Daudit.outputDirectory=$outputDir" -DskipTests package
```

Repetir con match-state-service y dashboard-backend. Aquí skipTests evita repetir
las pruebas ya ejecutadas; no sustituye la suite previa y no se usa en CI. Boot
repackage trabaja sobre el nuevo JAR dentro de esa carpeta y conserva allí su
.jar.original; los JAR anteriores de services/*/target permanecen intactos.
Inspeccionar BOOT-INF/classes, BOOT-INF/lib y manifest Main-Class/Start-Class.
Eso valida empaquetado ejecutable, no conectividad ni arranque durable real.

En 9E se ejecutó este procedimiento offline con éxito en los tres servicios.
La comprobación de acceso exclusivo de solo lectura no reprodujo el antiguo
bloqueo Windows 32; aun así no se intentó repackage sobre los JAR existentes.
No se borraron artefactos ni se detuvieron procesos para liberarlos.

React se prueba con npm test. Para conservar dist anterior en esta máquina:

```powershell
npm.cmd run build --prefix services/dashboard-frontend -- --outDir target/phase9e-dist
```

Ese directorio no existía al comenzar 9E; conservarlo y elegir otra carpeta para
una nueva ejecución que deba preservar todos los resultados. No se necesita
npm install/ci para validar localmente si ya existen dependencias. CI sí usa
npm ci sobre checkout limpio para respetar package-lock.json.

## GitHub Actions

`.github/workflows/validation.yml` se dispara en push, pull_request y manualmente.
Permisos globales contents:read, checkout sin persistir credenciales, límites de
tiempo, cancelación de runs obsoletos y matriz con fail-fast:false.

| Job | Validación real |
|---|---|
| Java (3 entradas) | JDK Temurin 17; mvn -f services/<servicio>/pom.xml verify |
| React | Node 24, npm ci, Vitest y tsc/Vite build |
| Node | Runner incorporado para app, loading y operations |

El checkout completo permite que Dev1LiveWireCompatibilityTest compile fuentes
hermanas reales incluso cuando el job Maven valida solo dashboard. No se usa
skipTests, no se asume agregación implícita, no hay Docker/services, publicación
de imágenes, entornos de deployment ni secretos productivos. El token automático
de Actions tiene solo los permisos declarados y no se persiste en Git.

Las integraciones externas están excluidas por tag; Playwright/browser y outage
no se ejecutan. No añadir una variable heredada de base para habilitarlas: usar
los perfiles/guardas explícitos y entorno desechable autorizado.
No hay descarga/arranque de navegadores en el workflow normal.

Las versiones mayores de las acciones oficiales se verificaron en sus repositorios:
[checkout v6](https://github.com/actions/checkout/tree/v6),
[setup-java v6](https://github.com/actions/setup-java/tree/v6) y
[setup-node v7](https://github.com/actions/setup-node/tree/v7).
Se usan tags mayores, no SHA inmutables: revisar/pinear según política del equipo
antes de adoptar el workflow como control de publicación. El workflow se valida
localmente como YAML/política y por los comandos equivalentes; no se ha ejecutado
en GitHub porque no hubo commit/push. No se afirma un run remoto aprobado.
