package org.yuktisetu.notificationservice;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yuktisetu.notificationservice.listener.DispatchRequeueRecoverer;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** A dispatch that keeps failing is parked back in the outbox, never dropped (2026-11-01). */
class DispatchRequeueRecovererTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DispatchRequeueRecoverer recoverer = new DispatchRequeueRecoverer(jdbc, JsonMapper.builder().build());

    @Test
    @DisplayName("it re-queues the message in the outbox under its own event id, five minutes out")
    void requeuesByEventId() {
        String value = "{\"eventId\":\"e-123\",\"eventType\":\"NotificationDispatch\",\"payload\":{}}";
        recoverer.accept(new ConsumerRecord<>("yuktisetu.notification.dispatch.v1", 0, 42L, "7", value),
                new IllegalStateException("database down"));

        verify(jdbc).update(contains("ON CONFLICT (event_id) DO UPDATE"), eq("e-123"), eq("7"),
                eq("yuktisetu.notification.dispatch.v1"), eq("7"), eq(value), contains("database down"));
    }

    @Test
    @DisplayName("a message with no event id cannot be re-queued and is logged instead")
    void noEventIdIsLogged() {
        recoverer.accept(new ConsumerRecord<>("t", 0, 1L, null, "not json"), new IllegalStateException("x"));
        verify(jdbc, never()).update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
    }
}
