package org.yuktisetu.notificationservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.yuktisetu.core.exception.BadRequestException;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.EventEnvelope;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;
import org.yuktisetu.notificationservice.service.DispatchIngestionService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The fan-out door. Consumes the dispatch command topic -- the only topic this
 * service knows about, which is why it never has to understand drive semantics.
 *
 * The envelope's eventId becomes the dispatch id and therefore the idempotency
 * key, so Kafka's at-least-once redelivery collapses into a no-op insert against
 * the unique constraints rather than a second email.
 *
 * Acknowledgement is manual and only happens after ingestion has committed. A
 * crash between consume and commit replays the message, which is safe for
 * exactly the reason above.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yuktisetu.kafka.enabled", havingValue = "true")
public class NotificationDispatchListener {

    private final DispatchIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = DriveTopics.NOTIFICATION_DISPATCH,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDispatch(String message, Acknowledgment ack) {
        String eventId = null;
        try {
            EventEnvelope<NotificationDispatchEvent> envelope = objectMapper.readValue(
                    message, new TypeReference<EventEnvelope<NotificationDispatchEvent>>() {});

            eventId = envelope.eventId();
            ingestionService.ingest(eventId, envelope.payload());
            ack.acknowledge();

        } catch (BadRequestException e) {
            // A malformed payload will never become valid by being retried, so
            // acknowledge it rather than blocking the partition forever. The log
            // line is the record that it was dropped.
            log.error("Rejecting unprocessable dispatch eventId={}: {}", eventId, e.getMessage());
            ack.acknowledge();

        } catch (Exception e) {
            // Transient -- database down, template table locked. Do not
            // acknowledge; the broker redelivers and the constraints make that safe.
            log.error("Dispatch ingestion failed for eventId={}, leaving unacknowledged for redelivery: {}",
                    eventId, e.getMessage(), e);
        }
    }
}
