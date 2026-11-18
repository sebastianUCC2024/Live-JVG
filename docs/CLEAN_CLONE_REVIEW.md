# Limpieza documental: revisión y resultados

Base: origin/dev `37e276800435524321352f02dec6fe7b4854343a`.
Rama: `docs/valeria-clean-clone`. No se modifican contratos, migraciones ni lógica funcional.

## Cambios

- README raíz y README Dashboard/React: enlaces reparados, schemas implementados, alcance del perfil Maven y requisitos Node corregidos.
- Guía CLEAN_CLONE: instalación, siete módulos, fixtures Notification antes de validadores, React, seguridad OFF/ON y provisión separada.
- Guía histórica de demostración: comandos portables sin depender de cachés o worktrees privados. Se conservan referencias históricas identificadas y todas las evidencias originales.
- Enlaces a resultados antiguos dentro de target se convierten en referencias textuales a salidas locales no distribuidas.
- Exclusiones específicas para cachés, credenciales y artefactos; plantillas seguras siguen permitidas.
- Docker context excluye artefactos host y conserva docs, fuentes, fixtures, scripts y migraciones. Se conserva el target prebuilt existente y su excepción JAR; la certificación limpia usa runtime.
- Dependencias Python directas y transitivas fijadas en requirements-validation.txt, instaladas desde PyPI en un venv nuevo; pip check PASS.
- Comentario obsoleto del POM raíz actualizado. No se cambia su comportamiento ni módulos.

## Verificaciones ejecutadas sin infraestructura

| Comprobación | Resultado |
|---|---|
| test-clean-clone-docs.py | 4/4 PASS: enlaces Markdown locales, scripts documentados, ignores/plantillas y entradas Docker estáticas |
| test-validate-dev1-contracts.py | 4/4 PASS |
| test-published-contracts.py | 16/16 PASS |
| test-e2e-harness.py | 40/40 PASS |
| test-academic-e2e.py | 11/11 PASS |
| test-docker-runtime.py | 5/5 PASS, usa un doble shell de Java, no inicia JVM de aplicación |
| test-integrated-contracts.py | 4/4 PASS |
| validate-dev1-contracts.py | PASS, 20 fixtures de propuestas y estructura del workflow |
| validate-integrated-contracts.py | PASS, 3 fixtures LiveAlert reales exportados por tests y 2 académicos |
| validate-e2e-proposal.py | PASS del manifiesto propuesto OFF; sin ejecutar recursos |
| Puente Notification–React | PASS con fixtures generados en este worktree y TypeScript instalado previamente en el frontend local |
| Notification Maven test | 117/117 PASS, 0 failures, 0 errors, 0 skipped, sin perfil externo |
| pip check | PASS |
| git diff --check | PASS |
| Firmas de secretos y rutas de artefactos en cambios | Sin coincidencias detectadas; no equivale a escaneo completo del historial |

Total de tests Python: 84 aprobados. No se presentan los validadores como tests de entrega real de brokers.
La primera ejecución Notification bajo sandbox tuvo 4 fallos y 50 errores (117 tests), incluyendo imposibilidad de inicializar Mockito. Se conservó el log privado y se repitió la misma suite sin cambiar código, con permisos locales para instrumentación: 117/117 PASS. No se inició infraestructura externa.

## Límites y siguiente revisión

No se ejecutó build Docker ni una instalación npm nueva, ni certificación completa de los siete módulos desde un clon remoto nuevo. El puente React reutilizó dependencias locales; la guía exige npm ci al reproducirlo. La suite Notification usó caché Maven existente, mientras que Python sí se instaló en un venv nuevo.
Los checks Docker son estáticos y no sustituyen la ejecución posterior del build multietapa. Ver [procedimiento](CLEAN_CLONE.md).
Las versiones declaradas se comprobaron con Python 3.9 local; la instalación adicional con Python 3.12 de CI debe verificarse en esa plataforma.
No se ejecutaron Docker, brokers, SQL, migraciones, servicios externos ni E2E. No se retiraron archivos del índice ni se borraron evidencias.
La limpieza no certifica ORDER02, ACL, autoridad ni apuestas. No modifica recursos de demostración.
