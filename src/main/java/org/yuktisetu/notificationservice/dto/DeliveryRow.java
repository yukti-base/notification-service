package org.yuktisetu.notificationservice.dto;

/**
 * One recipient on one channel, pending send. notificationId is null for a pure
 * email or group send that has no in-app counterpart -- the column is nullable
 * for exactly that case. renderedSubject/renderedBody carry the EMAIL template
 * rendered for this recipient; null for in-app and group-alias rows.
 */
public record DeliveryRow(
        Long notificationId,
        Long recipientUserId,
        String recipientAddress,
        String channel,
        String sourceEventId,
        String templateKey,
        String renderedSubject,
        String renderedBody
) {
    public DeliveryRow(Long notificationId, Long recipientUserId, String recipientAddress,
                       String channel, String sourceEventId, String templateKey) {
        this(notificationId, recipientUserId, recipientAddress, channel, sourceEventId, templateKey, null, null);
    }
}
