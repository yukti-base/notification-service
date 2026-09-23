package org.yuktisetu.notificationservice.dto;

import java.time.Instant;

/**
 * A flat, already-rendered in-app row, ready to be bound into a multi-row
 * INSERT. Deliberately not the JPA entity: these never become managed objects,
 * so Hibernate never dirty-checks 2000 of them.
 */
public record NotificationRow(
        Long recipientUserId,
        String type,
        String severity,
        String title,
        String body,
        String actionUrl,
        String sourceEventId,
        String sourceAggregateType,
        String sourceAggregateId,
        String metadataJson,
        Instant expiresAt
) {}
