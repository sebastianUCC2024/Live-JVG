# Gateway — prueba real preparada, todavía no ejecutada

ProducerGateway del PR #19 no se reimplementa. Su respuesta segura del PR #18,
500/503, identidad y consulta previa al reenvío ya tienen pruebas locales.
Dashboard verify volvió a pasar 150 pruebas en esta tarea; sus 31 casos gateway
e identidad cubren transporte controlado, no Kafka real. Node pasó 34 casos.

## Escenario futuro de respuesta perdida

La prueba necesita el harness aislado aprobado de DEV 1 y permiso explícito para
publicar, leer Kafka y provocar una pérdida de respuesta en un proxy propio. No
se usa un proxy global, no se corta la red de otros servicios ni se altera el
endpoint público del productor. Sin manifiesto/recursos verificables, no ejecutar.

| Paso | Acción futura coordinada | Evidencia / condición de fallo |
| --- | --- | --- |
| 1 | Acordar partido, event_id y timestamp originales en dataset exclusivo; guardar el body antes de enviar | Body exacto y scope del run; no generar otra identidad al recuperar |
| 2 | DEV 1 configura productor/Kafka y ruta de proxy HTTP controlado para la instancia de prueba de dashboard | URLs/puertos/ownership aprobados; no cambiar el servicio público |
| 3 | Enviar una vez desde el cliente real hacia dashboard; proxy deja llegar POST al productor y suprime solo la respuesta después del envío | Captura de tránsito y registros Kafka del ámbito; cliente queda incierto |
| 4 | Restaurar respuesta del proxy y consultar procesamiento del ID por la API existente de dashboard | processed=true evita otro POST; false no prueba rechazo Kafka |
| 5 | Solo si continúa incierto/no procesado, reintento manual con el body original | event_id, timestamp, referencia, minuto y demás campos iguales; cero retry automático |
| 6 | Revisar Kafka y el resultado aplicado por Match State/dashboard | Puede haber dos registros Kafka con el mismo evento; un único efecto lógico en el ledger/proyección es la garantía requerida |
| 7 | En ejecución distinta autorizada, provocar fallo de preparación o publicación | 500/EVENT_PREPARATION_FAILED o 503/PUBLICATION_UNCONFIRMED, event validado y texto seguro; no fingir success por recibir el body |
| 8 | Recargar navegador y recuperar estado | Body conservado, consulta de ID y marcador real; no UUID nuevo |

DEV 1 debe aportar observación autorizada del ledger/idempotencia del evento y
Kafka; la API processed:boolean por sí sola no demuestra que hubo exactamente un
efecto de negocio. No exigir un solo registro Kafka ni prometer exactly-once.
Si el primer evento ya se procesó al consultar, repetir solo la consulta y probar
la rama de reenvío manual en otro escenario autorizado con procesamiento retrasado,
sin provocar una segunda jugada real sobre un partido compartido.

## Evidencia y límites

Relacionar commit/JAR, run/manifiesto, body original, event_id, posiciones Kafka,
resultado Match State, inbox dashboard, alert_id si corresponde, respuesta HTTP,
estado local pendiente y captura de UI. No registrar secrets, tokens o causas
internas; no enviar un envelope de error como request de publicación.

Las pruebas existentes ProducerGatewayIdentityTest/TimeoutTest/RecoveryTest,
operations.test.cjs y app.test.cjs ya prueban las ramas locales sin productor real.
Una prueba simulada de IOException o timeout no certifica el escenario de esta
tabla. No se cambiaron clases del gateway, sus contratos o código DEV 1.

Orden, restauración de identidades y activación VAR V2 requieren las decisiones
de [Fase 6A](phase6a-decisions-and-git.md). CLEAR no habilita apuestas. La prueba
real completa queda pendiente de DEV 1/infraestructura y aprobación conjunta.
