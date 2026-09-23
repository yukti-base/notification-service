package org.yuktisetu.notificationservice.dto;

/**
 * What POST /dispatch returns. Accepted, not sent: the HTTP call ends once the
 * delivery rows are durable, and the drainer does the sending. A caller that
 * needs to know whether a specific student actually received it queries the
 * delivery log by dispatchId rather than waiting on the send.
 */
public record DispatchAcceptedResponse(
        String dispatchId,
        int recipientsAccepted,
        int notificationsCreated,
        int deliveriesQueued,
        boolean duplicate
) {}
