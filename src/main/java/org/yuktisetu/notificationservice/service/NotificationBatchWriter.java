package org.yuktisetu.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.yuktisetu.notificationservice.dto.DeliveryRow;
import org.yuktisetu.notificationservice.dto.NotificationRow;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a whole dispatch in a fixed number of statements, independent of how
 * many recipients it carries.
 *
 * Why this is not saveAll(). Every notification-dal entity inherits
 * NotificationAuditableEntity, whose id is GenerationType.IDENTITY. Hibernate
 * silently disables JDBC insert batching for IDENTITY-generated entities --
 * it has to run the insert to learn the key -- so saveAll(500 deliveries) is
 * 500 sequential round trips to Neon, not one. On a 2000-student drive blast
 * that is 2000+ round trips across four chunks, at the exact moment the system
 * is under peak load. Multi-row INSERT via JdbcTemplate keeps it at one
 * statement per chunk.
 *
 * The second reason is idempotency. Both tables carry a unique constraint on
 * (source_event_id, recipient) precisely because Kafka delivery is at-least-once.
 * ON CONFLICT DO NOTHING lets the database enforce that during the insert, so a
 * redelivered message costs one statement and writes nothing, instead of a
 * pre-flight SELECT per recipient followed by a insert that might still race.
 *
 * Chunked at 500 rows per statement to stay far below Postgres' 65535 bind
 * parameter ceiling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationBatchWriter {

    private static final int ROWS_PER_STATEMENT = 500;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Inserts the in-app rows and returns recipientUserId -> notificationId for
     * the whole batch, including rows that already existed from an earlier
     * delivery of the same dispatch.
     *
     * Normal path is one statement. A redelivered dispatch costs one extra
     * SELECT, which is the cheap half of the trade.
     */
    public Map<Long, Long> insertNotifications(String sourceEventId, List<NotificationRow> rows) {
        Map<Long, Long> idsByRecipient = new HashMap<>();
        if (rows.isEmpty()) {
            return idsByRecipient;
        }

        try {
            for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
                List<NotificationRow> chunk = rows.subList(start, Math.min(start + ROWS_PER_STATEMENT, rows.size()));
                insertNotificationChunk(chunk, idsByRecipient);
            }

            // Only pay for this when the constraint actually suppressed something,
            // i.e. this dispatch has been seen before.
            if (idsByRecipient.size() < rows.size()) {
                log.info("Dispatch {} partially deduplicated: {} of {} in-app rows already existed",
                        sourceEventId, rows.size() - idsByRecipient.size(), rows.size());
                loadExistingNotificationIds(sourceEventId, idsByRecipient);
            }
            return idsByRecipient;

        } catch (Exception e) {
            log.error("Failed to insert notification rows for sourceEventId={} count={}: {}",
                    sourceEventId, rows.size(), e.getMessage(), e);
            throw e;
        }
    }

    private void insertNotificationChunk(List<NotificationRow> chunk, Map<Long, Long> idsByRecipient) {
        StringBuilder sql = new StringBuilder("""
                INSERT INTO notifications (
                    recipient_user_id, type, severity, title, body, action_url,
                    is_read, source_event_id, source_aggregate_type, source_aggregate_id,
                    metadata, expires_at, created_at, updated_at, is_deleted
                ) VALUES
                """);

        List<Object> args = new ArrayList<>(chunk.size() * 12);
        for (int i = 0; i < chunk.size(); i++) {
            // metadata is jsonb: Postgres will not implicitly cast a bound
            // varchar into it, hence the explicit ?::jsonb.
            sql.append(i == 0 ? " " : ", ")
               .append("(?, ?, ?, ?, ?, ?, false, ?, ?, ?, ?::jsonb, ?, now(), now(), false)");

            NotificationRow r = chunk.get(i);
            args.add(r.recipientUserId());
            args.add(r.type());
            args.add(r.severity());
            args.add(r.title());
            args.add(r.body());
            args.add(r.actionUrl());
            args.add(r.sourceEventId());
            args.add(r.sourceAggregateType());
            args.add(r.sourceAggregateId());
            args.add(r.metadataJson());
            args.add(r.expiresAt());
        }

        sql.append("""

                ON CONFLICT ON CONSTRAINT uk_notification_event_recipient DO NOTHING
                RETURNING id, recipient_user_id
                """);

        jdbcTemplate.query(sql.toString(), rs -> {
            idsByRecipient.put(rs.getLong("recipient_user_id"), rs.getLong("id"));
        }, args.toArray());
    }

    private void loadExistingNotificationIds(String sourceEventId, Map<Long, Long> idsByRecipient) {
        jdbcTemplate.query(
                "SELECT id, recipient_user_id FROM notifications WHERE source_event_id = ?",
                rs -> {
                    idsByRecipient.putIfAbsent(rs.getLong("recipient_user_id"), rs.getLong("id"));
                },
                sourceEventId);
    }

    /**
     * Inserts the per-recipient-per-channel delivery rows. No RETURNING: nothing
     * downstream needs the generated ids, the drainer re-reads them by status.
     *
     * @return rows actually written, after the unique constraint has suppressed
     *         any that this dispatch already produced.
     */
    public int insertDeliveries(String sourceEventId, List<DeliveryRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }

        try {
            int written = 0;
            for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
                List<DeliveryRow> chunk = rows.subList(start, Math.min(start + ROWS_PER_STATEMENT, rows.size()));
                written += insertDeliveryChunk(chunk);
            }

            if (written < rows.size()) {
                log.info("Dispatch {} deduplicated {} of {} delivery rows -- redelivered message, no second send",
                        sourceEventId, rows.size() - written, rows.size());
            }
            return written;

        } catch (Exception e) {
            log.error("Failed to insert delivery rows for sourceEventId={} count={}: {}",
                    sourceEventId, rows.size(), e.getMessage(), e);
            throw e;
        }
    }

    private int insertDeliveryChunk(List<DeliveryRow> chunk) {
        StringBuilder sql = new StringBuilder("""
                INSERT INTO notification_deliveries (
                    notification_id, recipient_user_id, recipient_address, channel,
                    status, attempts, next_attempt_at, source_event_id, template_key,
                    created_at, updated_at, is_deleted
                ) VALUES
                """);

        List<Object> args = new ArrayList<>(chunk.size() * 9);
        for (int i = 0; i < chunk.size(); i++) {
            sql.append(i == 0 ? " " : ", ")
               .append("(?, ?, ?, ?, 'PENDING', 0, now(), ?, ?, now(), now(), false)");

            DeliveryRow r = chunk.get(i);
            args.add(r.notificationId());
            args.add(r.recipientUserId());
            args.add(r.recipientAddress());
            args.add(r.channel());
            args.add(r.sourceEventId());
            args.add(r.templateKey());
        }

        sql.append("""

                ON CONFLICT ON CONSTRAINT uk_delivery_event_recipient_channel DO NOTHING
                """);

        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /**
     * Marks a drained batch in one statement per outcome rather than one save()
     * per row -- same reasoning as the inserts, except updates would batch under
     * Hibernate and still cost a dirty-check pass over every managed entity.
     */
    public void markSent(List<Long> deliveryIds, String provider, String providerMessageId) {
        if (deliveryIds.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    UPDATE notification_deliveries
                       SET status = 'SENT', attempts = attempts + 1, last_attempt_at = now(),
                           next_attempt_at = NULL, provider = ?, provider_message_id = ?,
                           error_code = NULL, error_message = NULL, updated_at = now()
                     WHERE id = ANY (?)
                    """,
                    ps -> {
                        ps.setString(1, provider);
                        ps.setString(2, providerMessageId);
                        ps.setArray(3, ps.getConnection().createArrayOf("bigint", deliveryIds.toArray()));
                    });
        } catch (Exception e) {
            log.error("Failed to mark {} deliveries SENT: {}", deliveryIds.size(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Backoff is 2^attempts minutes, capped, and a row that has exhausted its
     * attempts goes to FAILED so it stops being claimed. FAILED is a real state
     * here: it is the row an admin is shown when a student says they were never
     * told.
     */
    public void markFailed(List<Long> deliveryIds, int maxAttempts, String errorCode, String errorMessage) {
        if (deliveryIds.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    UPDATE notification_deliveries
                       SET attempts = attempts + 1,
                           last_attempt_at = now(),
                           status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'RETRY' END,
                           next_attempt_at = CASE WHEN attempts + 1 >= ? THEN NULL
                                                  ELSE now() + make_interval(mins => LEAST(power(2, attempts + 1)::int, 60))
                                             END,
                           error_code = ?, error_message = ?, updated_at = now()
                     WHERE id = ANY (?)
                    """,
                    ps -> {
                        ps.setInt(1, maxAttempts);
                        ps.setInt(2, maxAttempts);
                        ps.setString(3, errorCode);
                        if (errorMessage == null) {
                            ps.setNull(4, Types.VARCHAR);
                        } else {
                            ps.setString(4, errorMessage.length() > 4000 ? errorMessage.substring(0, 4000) : errorMessage);
                        }
                        ps.setArray(5, ps.getConnection().createArrayOf("bigint", deliveryIds.toArray()));
                    });
        } catch (Exception e) {
            log.error("Failed to mark {} deliveries FAILED/RETRY: {}", deliveryIds.size(), e.getMessage(), e);
            throw e;
        }
    }
}
