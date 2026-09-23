package org.yuktisetu.notificationservice.dto;

import java.time.Instant;

/** One row in the bell. */
public record NotificationResponse(
        Long id,
        String type,
        String severity,
        String title,
        String body,
        String actionUrl,
        boolean read,
        Instant readAt,
        Instant createdAt
) {}
