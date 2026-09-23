package org.yuktisetu.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * notification-service owns in-app, email and Google Group delivery plus the
 * per-recipient delivery log, for every service on the fleet except
 * admin-service -- which deliberately keeps its own admin-to-admin mail and its
 * bulk-student-invite flow on core's EmailService, so admins never queue behind
 * a service busy serving students.
 *
 * Two doors, one room: a Kafka listener on the dispatch command topic for
 * fan-out blasts, and POST /dispatch for single low-stakes sends. Both hand the
 * identical payload to DispatchIngestionService, so there is exactly one
 * implementation of what "accept a dispatch" means.
 *
 * Note how short the entity scan is: this service maps only its own context
 * plus the outbox. No drive, identity, profile or institute entities exist on
 * its classpath at all -- which is what makes it the one service already ready
 * to own its database.
 */
@SpringBootApplication
// Scoped to the contexts this service owns or reaches, instead of the old
// blanket org.yuktisetu.db. The blanket scan mapped every entity on the
// classpath, so one bad column broke startup in four services at once.
// These lists shrink on their own as cross-context dependencies are cut.
@EntityScan(basePackages = {"org.yuktisetu.notification.db"})
@EnableJpaRepositories(basePackages = {"org.yuktisetu.notification.repository"})
@ComponentScan({"org.yuktisetu.notificationservice", "org.yuktisetu.core"})
@EnableScheduling
@EnableAsync
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
