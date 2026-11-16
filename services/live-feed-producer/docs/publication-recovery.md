# Publication identity recovery (D1.3)

`POST /api/matches/{matchId}/events` keeps its request format and its successful
202 response: the original MatchEvent, without an envelope. Configuration
validation from D1.2 remains in the service constructor.

Validation fails before publication with 400. Preparation/serialization failures
before Kafka send return 500 with `code: EVENT_PREPARATION_FAILED`. Failures after
entering the Kafka send path return 503 with `code: PUBLICATION_UNCONFIRMED`.
Both error bodies retain `error` and add `event`, the complete original event
including server-generated `event_id` and `timestamp` when creation succeeded.
Internal exception messages and causes are never included.

A generic synchronous exception, asynchronous exception or Kafka timeout does
not reliably prove broker rejection. All send-path failures are conservatively
reported as unconfirmed; no definitive-rejection classification is inferred.
If the future never completes, this change does not impose a new timeout. An HTTP
timeout, disconnected client or lost response can still prevent identity recovery.
Clients needing recovery in those cases must provide ID and timestamp initially.

To retry, use `event.match_id` in the existing URL and copy the other six fields
from `event` into the existing request DTO. Preserve every field, including nulls,
minute and reference. For example, if the error contains identity `event-123` and
timestamp `2026-11-16T20:00:00.123456789Z`, resend:

```http
POST /api/matches/mvp-match-001/events
Content-Type: application/json

{"event_id":"event-123","event_type":"GOAL","team_id":"home","minute":10,"timestamp":"2026-11-16T20:00:00.123456789Z","reference_event_id":null}
```

Do not send the error envelope as the request. Repeated requests may produce
duplicate Kafka records. Kafka key remains match_id, event JSON is unchanged,
and downstream event identity deduplication remains necessary. There is no new
storage or exactly-once guarantee. A 500 preparation failure requires fixing its
cause before retrying the same event.

Local reference inspection: dashboard-backend ProducerGateway.publish consumes
the successful Map response and preserves producer HTTP error statuses, but
replaces error bodies with its own message. The additive error fields therefore
require coordination with DEV 2 to propagate identity through the dashboard.
No gateway or consumer was modified. Direct producer clients can recover the
identity from the new body immediately.
