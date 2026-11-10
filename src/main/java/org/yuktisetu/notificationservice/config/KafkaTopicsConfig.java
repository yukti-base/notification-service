package org.yuktisetu.notificationservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.yuktisetu.messaging.events.DriveTopics;

/**
 * notification-service produces nothing. It consumes exactly one topic -- the
 * dispatch command topic -- which is what keeps it ignorant of drive semantics:
 * it never learns what a posting or an applicant pool is, only "send this to
 * these people".
 *
 * Declared here as well as on the producer side so the topic exists whichever
 * service starts first.
 */
@Configuration
@ConditionalOnProperty(name = "yuktisetu.kafka.enabled", havingValue = "true")
public class KafkaTopicsConfig {

    @Bean
    public NewTopic notificationDispatchTopic() {
        // Partitioned so several instances can drain the fan-out concurrently;
        // producers key by aggregate so one drive's messages stay ordered.
        return TopicBuilder.name(DriveTopics.NOTIFICATION_DISPATCH)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationDispatchDlqTopic() {
        return TopicBuilder.name(DriveTopics.dlqFor(DriveTopics.NOTIFICATION_DISPATCH))
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Retry a failing dispatch with backoff (1 s doubling to 30 s, for about two
     * minutes), then hand it to DispatchRequeueRecoverer, which parks it in the
     * outbox to be re-sent five minutes later. Nothing is skipped.
     */
    @Bean
    public org.springframework.kafka.listener.CommonErrorHandler kafkaErrorHandler(
            org.yuktisetu.notificationservice.listener.DispatchRequeueRecoverer recoverer) {
        org.springframework.util.backoff.ExponentialBackOff backOff =
                new org.springframework.util.backoff.ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(120_000L);
        return new org.springframework.kafka.listener.DefaultErrorHandler(recoverer, backOff);
    }
}
