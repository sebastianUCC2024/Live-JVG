# Procedencia de los mensajes

`dev1-score-updates.json` conserva los cinco cuerpos `EVIDENCE rabbit=` de
`services/match-state-service/docs/FASE_7_EVIDENCIAS.md`, commit
`7b606b9240ef45e292a5fc04d701e57ae85addce`. Solo se envolvieron en un array JSON;
no se cambiaron campos, IDs ni timestamps. El publicador correspondiente es el
código de `a096343`, integrado en `origin/dev` mediante `43aab3e`.

Son ejemplos observados documentados por DEV 1, no un contrato compartido aprobado
ni una prueba de brokers ejecutada por DEV 2. La prueba carga estos recursos
locales, sin compilar, iniciar ni depender del módulo de DEV 1. Si cambia su salida,
revisar expresamente estas muestras y los resultados esperados.
