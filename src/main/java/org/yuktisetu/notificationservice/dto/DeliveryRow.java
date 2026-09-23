package org.yuktisetu.notificationservice.dto;

/**
 * One recipient on one channel, pending send. notificationId is null for a pure
 * email or group send that has no in-app counterpart -- the column is nullable
 * for exactly that case.
 */
public record DeliveryRow(
        Long notificationId,
        Long recipientUserId,
        String recipientAddress,
        String channel,
        String sourceEventId,
        String templateKey
) {}
