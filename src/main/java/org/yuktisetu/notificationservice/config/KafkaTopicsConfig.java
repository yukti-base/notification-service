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
}
