package org.yuktisetu.notificationservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Where a dispatch goes when ingestion keeps failing (decided 2026-11-01: a
 * Kafka-side failure must never lose a notice).
 *
 * The message is written back into the shared outbox -- the row it came from,
 * matched by event id, or a new one if housekeeping already purged it -- as
 * PENDING five minutes from now. The admin- and drive-service relays then publish
 * it again. It lives in the database in the meantime, so it survives a restart of
 * every service; ingestion is idempotent on the event id, so a second delivery of
 * something that half-succeeded writes nothing twice.
 *
 * If this write itself fails (the database is down too), the exception goes back
 * to the error handler, which retries the record rather than skipping it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchRequeueRecoverer implements ConsumerRecordRecoverer {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        String value = String.valueOf(record.value());
        String eventId;
        try {
            JsonNode node = objectMapper.readTree(value);
            eventId = node.path("eventId").asString(null);
        } catch (Exception e) {
            eventId = null;
        }
        if (eventId == null) {
            log.error("Dropping dispatch at {}-{}@{} after retries: no event id to re-queue it by. Cause: {}",
                    record.topic(), record.partition(), record.offset(), failure.getMessage());
            return;
        }
        String reason = "Re-queued by notification-service after ingestion kept failing: " + failure.getMessage();
        jdbc.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, topic, event_type, partition_key,
                                           payload, status, attempts, available_at, last_error, created_at, updated_at)
                VALUES (?, 'NotificationDispatch', ?, ?, 'NotificationDispatch', ?, ?::jsonb, 'PENDING', 0,
                        now() + interval '5 minutes', ?, now(), now())
                ON CONFLICT (event_id) DO UPDATE
                   SET status = 'PENDING', attempts = 0, available_at = now() + interval '5 minutes',
                       published_at = NULL, last_error = EXCLUDED.last_error, updated_at = now()
                """, eventId, record.key() == null ? null : String.valueOf(record.key()), record.topic(),
                record.key() == null ? null : String.valueOf(record.key()), value, reason);
        log.error("Dispatch eventId={} could not be ingested after retries; re-queued in the outbox for 5 minutes from now: {}",
                eventId, failure.getMessage());
    }
}
