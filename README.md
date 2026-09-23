# notification-service

Owns in-app, email and Google Group delivery plus the per-recipient delivery log,
for every service on the fleet **except `admin-service`** — which deliberately
keeps its own admin-to-admin mail and the bulk-student-invite flow on `core`'s
`EmailService`, so admins never queue behind a service busy serving students.

Port **8085**, context path **`/api/notification`**.

## Two doors, one room

Both carry the identical `NotificationDispatchEvent` payload and converge on
`DispatchIngestionService`, so there is exactly one definition of "accept a
dispatch".

| Path | Use for | Why |
|---|---|---|
| Kafka `yuktisetu.notification.dispatch.v1` | fan-out blasts (drive published, pool sealed, results) | the producer writes an outbox row in the same transaction as the business change, so the notification survives this service being down |
| `POST /dispatch` | single low-stakes sends (auth-service's role invite) | no broker needed; returns `202` once the rows are durable |

## The one rule callers must follow

**One dispatch carries a list of recipients. Never one dispatch per recipient.**

Producers chunk large audiences at ~500 per message, so a 2000-student blast is
four messages — not one oversized message, and not two thousand tiny ones. The
`NotificationClient` in each calling service has no single-recipient overload on
purpose: that is how a fifty-person invite accidentally becomes fifty HTTP calls.

Ingestion rejects any dispatch over 1000 recipients rather than accepting it and
timing out halfway through a drive blast.

## Why the writes bypass Hibernate

Every `notification-dal` entity inherits `NotificationAuditableEntity`, whose id
is `GenerationType.IDENTITY`. Hibernate **silently disables JDBC insert batching
for IDENTITY-generated entities** — it has to run each insert to learn the key —
so `saveAll(500 deliveries)` is 500 sequential round trips.

`NotificationBatchWriter` therefore builds multi-row `INSERT`s through
`JdbcTemplate`. Measured against Neon from a laptop: **10 rows ≈ 1.0 s, 1000 rows
≈ 1.86 s** — 100× the rows for 1.5× the time. Per-row inserts would have taken
minutes.

The query budget per dispatch is fixed and does not grow with recipient count:

1. one `SELECT` for the templates this dispatch needs
2. one multi-row `INSERT` per 500 in-app rows (plus one `SELECT` **only** if the
   dispatch is a redelivery)
3. one multi-row `INSERT` per 500 delivery rows

There is no query inside any loop.

## Idempotency

`notifications` and `notification_deliveries` each carry a unique constraint on
`(source_event_id, recipient…)`. The inserts use `ON CONFLICT DO NOTHING`, so a
redelivered Kafka message or a retried POST costs one statement and writes
nothing instead of mailing everyone twice.

REST callers **must** supply a stable `Idempotency-Key` generated once per
logical dispatch, not per attempt. auth-service derives its key from the new
user's id (`role-invite-<userId>`) for exactly this reason.

## Sending

`DeliveryDrainer` claims queued rows with `FOR UPDATE SKIP LOCKED` (the query
lives in `notification-dal`), groups them by channel, and sends each email chunk
with **one** `mailSender.send(MimeMessage[])` — one SMTP conversation per chunk,
not one per student. Gmail rate-limits the latter into failure.

Failures set `RETRY` with `2^attempts` minute backoff, capped at 60 minutes, and
become `FAILED` after `max-attempts`. `FAILED` is a real state: it is the row an
admin is shown when a student says they were never told.

A retried send is safe because the unique constraint stops anyone who did receive
the first attempt getting a second copy.

## Local config

Both sweeps are **off by default**, like drive-service's sealing sweep — a
developer's laptop pointed at the shared database must not start mailing real
students.

```properties
yuktisetu.notification.drain.enabled=false
yuktisetu.kafka.enabled=false
yuktisetu.notification.service-token=local-dev-service-token
```

Sibling services authenticate with `X-Service-Token`. Dispatch is not a user
action, so it is not gated on a student's or admin's JWT — forwarding an admin
token would mean any role able to publish a drive is also able to mail arbitrary
recipients. The bell endpoints (`GET /notifications`, `/unread-count`,
`/{id}/read`, `/read-all`) use the ordinary user JWT and read the recipient from
the principal, never from a path parameter.

This service owns the five `notification_*` tables outright — nothing else has
`notification-dal` on its classpath — so it runs `ddl-auto=update` for them and
does not wait on admin-service.

## Not built yet

No Kafka **producer** (this service only consumes), no DLQ consumer, no
`BroadcastGroup` resolution beyond sending to a literal alias address, and no
tests.
