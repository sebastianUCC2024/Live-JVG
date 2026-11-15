# Fase 7 — Evidencias conservadas

Extracto de la ejecución real del 14 de noviembre de 2026 (America/Bogota). Los registros siguientes proceden de `evidence.txt`; se conservan completos, sin sustituir identificadores ni mensajes. No son logs de tests simulados.

SHA-256 del archivo fuente `evidence.txt`: `D271A2EC7824C4B7456D0F0809A0BEAB8E3EBC3ADFE0741EE84336F0DB733B70`.

## 1. Publicación, consumo y mensajes reales

```text
EVIDENCE queue=amq.gen-zyS-6qMr_fpy59h85V5BBA exchange=live_updates type=topic
2026-11-14T19:12:09.625-05:00  INFO 8704 --- [match-state-service] [ucer-producer-1] c.l.l.i.kafka.KafkaMatchEventPublisher   : Kafka publication acknowledged; eventId=00a6fa93-3f61-4c5b-8cb5-59c25cf3b021, topic=match_events, partition=0, offset=0
2026-11-14T19:12:12.200-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.i.r.RabbitScoreUpdatePublisher     : RabbitMQ publication acknowledged; eventId=00a6fa93-3f61-4c5b-8cb5-59c25cf3b021, exchange=live_updates, routingKey=score.match-001
2026-11-14T19:12:12.209-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.e.kafka.KafkaMatchEventListener    : Kafka event handling completed; eventId=00a6fa93-3f61-4c5b-8cb5-59c25cf3b021, partition=0, offset=0
EVIDENCE kafka={"event_id":"00a6fa93-3f61-4c5b-8cb5-59c25cf3b021","match_id":"match-001","event_type":"GOAL","team_id":"team-home","minute":10,"timestamp":"2026-11-15T00:12:08.848980400Z","reference_event_id":null} key=match-001 partition=0 offset=0 committed=1
EVIDENCE rabbit={"match_id":"match-001","home_score":1,"away_score":0,"event_id":"00a6fa93-3f61-4c5b-8cb5-59c25cf3b021","event_type":"GOAL","updated_at":"2026-11-15T00:12:12.079218900Z","var_status":"CLEAR","reference_event_id":null} routing=score.match-001
EVIDENCE step=1 duplicate=false score=1-0 var=CLEAR
2026-11-14T19:12:12.368-05:00  INFO 8704 --- [match-state-service] [ucer-producer-1] c.l.l.i.kafka.KafkaMatchEventPublisher   : Kafka publication acknowledged; eventId=00a6fa93-3f61-4c5b-8cb5-59c25cf3b021, topic=match_events, partition=0, offset=1
2026-11-14T19:12:12.385-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.e.kafka.KafkaMatchEventListener    : Kafka event handling completed; eventId=00a6fa93-3f61-4c5b-8cb5-59c25cf3b021, partition=0, offset=1
EVIDENCE kafka={"event_id":"00a6fa93-3f61-4c5b-8cb5-59c25cf3b021","match_id":"match-001","event_type":"GOAL","team_id":"team-home","minute":10,"timestamp":"2026-11-15T00:12:08.848980400Z","reference_event_id":null} key=match-001 partition=0 offset=1 committed=2
EVIDENCE step=2 duplicate=true score=1-0 var=CLEAR
2026-11-14T19:12:12.518-05:00  INFO 8704 --- [match-state-service] [ucer-producer-1] c.l.l.i.kafka.KafkaMatchEventPublisher   : Kafka publication acknowledged; eventId=7f320bfd-72ac-4f74-a588-d04e33b7f6d5, topic=match_events, partition=0, offset=2
2026-11-14T19:12:12.529-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.i.r.RabbitScoreUpdatePublisher     : RabbitMQ publication acknowledged; eventId=7f320bfd-72ac-4f74-a588-d04e33b7f6d5, exchange=live_updates, routingKey=score.match-001
2026-11-14T19:12:12.530-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.e.kafka.KafkaMatchEventListener    : Kafka event handling completed; eventId=7f320bfd-72ac-4f74-a588-d04e33b7f6d5, partition=0, offset=2
EVIDENCE kafka={"event_id":"7f320bfd-72ac-4f74-a588-d04e33b7f6d5","match_id":"match-001","event_type":"GOAL","team_id":"team-away","minute":20,"timestamp":"2026-11-15T00:12:08.848980400Z","reference_event_id":null} key=match-001 partition=0 offset=2 committed=3
EVIDENCE rabbit={"match_id":"match-001","home_score":1,"away_score":1,"event_id":"7f320bfd-72ac-4f74-a588-d04e33b7f6d5","event_type":"GOAL","updated_at":"2026-11-15T00:12:12.523727300Z","var_status":"CLEAR","reference_event_id":null} routing=score.match-001
EVIDENCE step=3 duplicate=false score=1-1 var=CLEAR
2026-11-14T19:12:12.674-05:00  INFO 8704 --- [match-state-service] [ucer-producer-1] c.l.l.i.kafka.KafkaMatchEventPublisher   : Kafka publication acknowledged; eventId=5a6397b3-f299-4c89-94b0-db1d167cbc74, topic=match_events, partition=0, offset=3
2026-11-14T19:12:12.682-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.i.r.RabbitScoreUpdatePublisher     : RabbitMQ publication acknowledged; eventId=5a6397b3-f299-4c89-94b0-db1d167cbc74, exchange=live_updates, routingKey=score.match-001
2026-11-14T19:12:12.682-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.e.kafka.KafkaMatchEventListener    : Kafka event handling completed; eventId=5a6397b3-f299-4c89-94b0-db1d167cbc74, partition=0, offset=3
EVIDENCE kafka={"event_id":"5a6397b3-f299-4c89-94b0-db1d167cbc74","match_id":"match-001","event_type":"VAR_CHECK","team_id":"team-away","minute":21,"timestamp":"2026-11-15T00:12:08.848980400Z","reference_event_id":"7f320bfd-72ac-4f74-a588-d04e33b7f6d5"} key=match-001 partition=0 offset=3 committed=4
EVIDENCE rabbit={"match_id":"match-001","home_score":1,"away_score":1,"event_id":"5a6397b3-f299-4c89-94b0-db1d167cbc74","event_type":"VAR_CHECK","updated_at":"2026-11-15T00:12:12.676960200Z","var_status":"IN_PROGRESS","reference_event_id":"7f320bfd-72ac-4f74-a588-d04e33b7f6d5"} routing=score.match-001
EVIDENCE step=4 duplicate=false score=1-1 var=IN_PROGRESS
2026-11-14T19:12:12.813-05:00  INFO 8704 --- [match-state-service] [ucer-producer-1] c.l.l.i.kafka.KafkaMatchEventPublisher   : Kafka publication acknowledged; eventId=1b975025-062a-4a45-9bd4-264b8e983e6c, topic=match_events, partition=0, offset=4
2026-11-14T19:12:12.819-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.i.r.RabbitScoreUpdatePublisher     : RabbitMQ publication acknowledged; eventId=1b975025-062a-4a45-9bd4-264b8e983e6c, exchange=live_updates, routingKey=score.match-001
2026-11-14T19:12:12.820-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.e.kafka.KafkaMatchEventListener    : Kafka event handling completed; eventId=1b975025-062a-4a45-9bd4-264b8e983e6c, partition=0, offset=4
EVIDENCE kafka={"event_id":"1b975025-062a-4a45-9bd4-264b8e983e6c","match_id":"match-001","event_type":"GOAL_ANNULLED","team_id":"team-away","minute":22,"timestamp":"2026-11-15T00:12:08.849977100Z","reference_event_id":"7f320bfd-72ac-4f74-a588-d04e33b7f6d5"} key=match-001 partition=0 offset=4 committed=5
EVIDENCE rabbit={"match_id":"match-001","home_score":1,"away_score":0,"event_id":"1b975025-062a-4a45-9bd4-264b8e983e6c","event_type":"GOAL_ANNULLED","updated_at":"2026-11-15T00:12:12.815787600Z","var_status":"CLEAR","reference_event_id":"7f320bfd-72ac-4f74-a588-d04e33b7f6d5"} routing=score.match-001
EVIDENCE step=5 duplicate=false score=1-0 var=CLEAR
2026-11-14T19:12:12.961-05:00  INFO 8704 --- [match-state-service] [ucer-producer-1] c.l.l.i.kafka.KafkaMatchEventPublisher   : Kafka publication acknowledged; eventId=1b975025-062a-4a45-9bd4-264b8e983e6c, topic=match_events, partition=0, offset=5
2026-11-14T19:12:12.962-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.e.kafka.KafkaMatchEventListener    : Kafka event handling completed; eventId=1b975025-062a-4a45-9bd4-264b8e983e6c, partition=0, offset=5
EVIDENCE kafka={"event_id":"1b975025-062a-4a45-9bd4-264b8e983e6c","match_id":"match-001","event_type":"GOAL_ANNULLED","team_id":"team-away","minute":22,"timestamp":"2026-11-15T00:12:08.849977100Z","reference_event_id":"7f320bfd-72ac-4f74-a588-d04e33b7f6d5"} key=match-001 partition=0 offset=5 committed=6
EVIDENCE step=6 duplicate=true score=1-0 var=CLEAR
2026-11-14T19:12:13.002-05:00  INFO 8704 --- [match-state-service] [ucer-producer-1] c.l.l.i.kafka.KafkaMatchEventPublisher   : Kafka publication acknowledged; eventId=d2ab559d-d2f9-4dda-8494-1938647c0d3d, topic=match_events, partition=0, offset=6
2026-11-14T19:12:13.012-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.i.r.RabbitScoreUpdatePublisher     : RabbitMQ publication acknowledged; eventId=d2ab559d-d2f9-4dda-8494-1938647c0d3d, exchange=live_updates, routingKey=score.match-001
2026-11-14T19:12:13.013-05:00  INFO 8704 --- [match-state-service] [sListener-0-C-1] c.l.m.e.kafka.KafkaMatchEventListener    : Kafka event handling completed; eventId=d2ab559d-d2f9-4dda-8494-1938647c0d3d, partition=0, offset=6
EVIDENCE kafka={"event_id":"d2ab559d-d2f9-4dda-8494-1938647c0d3d","match_id":"match-001","event_type":"MATCH_CONTINUES","team_id":null,"minute":23,"timestamp":"2026-11-15T00:12:08.849977100Z","reference_event_id":null} key=match-001 partition=0 offset=6 committed=7
EVIDENCE rabbit={"match_id":"match-001","home_score":1,"away_score":0,"event_id":"d2ab559d-d2f9-4dda-8494-1938647c0d3d","event_type":"MATCH_CONTINUES","updated_at":"2026-11-15T00:12:13.004654900Z","var_status":"CLEAR","reference_event_id":null} routing=score.match-001
EVIDENCE step=7 duplicate=false score=1-0 var=CLEAR
REAL_PIPELINE_PASS kafka=7 consumed=7 rabbit=5 final=1-0
```

## 2. Resumen Surefire anterior

Transcripción de `surefire-summary.json`. Estas pruebas no se repitieron durante la ejecución real ni al elaborar esta documentación.

SHA-256 del resumen fuente: `85B68FB6CB2845DF8B7E77F68C05D0B90AF106CB972B8DE4E92F86B97B0441AF`.

| Suite | Pruebas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| com.live_jgv.live_feed_producer.domain.model.DomainIndependenceTest | 1 | 0 | 0 | 0 |
| com.live_jgv.live_feed_producer.domain.model.MatchEventContractTest | 3 | 0 | 0 | 0 |
| com.live_jgv.live_feed_producer.domain.model.MatchEventTest | 31 | 0 | 0 | 0 |
| com.live_jgv.live_feed_producer.infrastructure.kafka.KafkaMatchEventPublisherTest | 7 | 0 | 0 | 0 |
| com.live_jgv.live_feed_producer.LiveFeedProducerApplicationTests | 1 | 0 | 0 | 0 |
| com.live_jgv.matchstate.application.service.MatchStateServiceTest | 8 | 0 | 0 | 0 |
| com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEventTest | 4 | 0 | 0 | 0 |
| com.live_jgv.matchstate.domain.model.DomainIndependenceTest | 1 | 0 | 0 | 0 |
| com.live_jgv.matchstate.domain.model.MatchEventTest | 5 | 0 | 0 | 0 |
| com.live_jgv.matchstate.domain.model.MatchStateTest | 12 | 0 | 0 | 0 |
| com.live_jgv.matchstate.entrypoint.kafka.KafkaMatchEventListenerTest | 25 | 0 | 0 | 0 |
| com.live_jgv.matchstate.entrypoint.kafka.MessagingPipelineTest | 2 | 0 | 0 | 0 |
| com.live_jgv.matchstate.infrastructure.config.KafkaRetryPolicyTest | 1 | 0 | 0 | 0 |
| com.live_jgv.matchstate.infrastructure.memory.InMemoryMatchStateStoreTest | 4 | 0 | 0 | 0 |
| com.live_jgv.matchstate.infrastructure.rabbit.RabbitScoreUpdatePublisherTest | 8 | 0 | 0 | 0 |
| com.live_jgv.matchstate.MatchStateServiceApplicationTests | 3 | 0 | 0 | 0 |

Totales: productor 43; consumidor 73; conjunto 116. Fallos, errores y omitidas: 0.

## 3. Contexto y alcance de las fuentes

El informe original registra brokers locales aislados, exchange `live_updates` de tipo `topic`, commits del grupo `match-state-service` hasta 7 y eliminación automática de la cola exclusiva al cerrar la conexión. Los originales `informe.md`, `result.json`, `evidence.txt` y el resumen Surefire se inspeccionaron fuera del repositorio. La preparación y los límites de reproducción se documentan en [FASE_7_INTEGRACION.md](FASE_7_INTEGRACION.md).

Los extractos no incluyen credenciales, ejecutores ni dependencias. Las fechas UTC de los JSON corresponden al 14 de noviembre en Bogotá.
