package org.yuktisetu.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.notification.db.NotificationTemplate;
import org.yuktisetu.notification.model.NotificationChannel;
import org.yuktisetu.notification.repository.NotificationTemplateRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Puts the shipped email templates into the database if they are not there.
 *
 * Templates live in notification_templates so the TnP Cell can reword a message
 * after the student team has graduated. That is the right call, but it left a
 * gap: nothing ever put the templates there in the first place. The rows were
 * inserted by hand, which meant a fresh database had no template at all and the
 * invite dispatch failed outright, while the row that did exist was a one-line
 * placeholder someone typed to get the flow working:
 *
 *   <p>Hello [(${inviteeName})],</p><p>Accept your invite: <a href="...">...</a></p>
 *
 * That stub is what has actually been mailed to every invited user -- unstyled,
 * unbranded, three lines long, and exactly the shape a spam filter treats as a
 * bare link forward.
 *
 * SEEDS ONLY WHAT IS MISSING. It never updates or overwrites an existing row,
 * because the whole point of storing templates in the database is that the TnP
 * can edit them; a seeder that reasserted the shipped copy on every boot would
 * silently discard their wording the next time the service restarted.
 *
 * The consequence to be aware of: correcting a template that already exists is a
 * deliberate one-off UPDATE, not something a redeploy does for you.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateSeeder {

    /**
     * @param resource classpath location of the body, authored as a normal HTML
     *                 file so it can be previewed in a browser while editing
     */
    private record Seed(String templateKey, NotificationChannel channel, String locale,
                        String subject, String resource) {}

    private static final List<Seed> SEEDS = List.of(
            new Seed("invite-role-email", NotificationChannel.EMAIL, "en",
                    "[[${inviteeName}]], you've been invited to YuktiSetu",
                    "email/template/invite-role-email.html"));

    private final NotificationTemplateRepository templateRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        for (Seed seed : SEEDS) {
            try {
                boolean exists = templateRepository
                        .findByTemplateKeyAndChannelAndLocaleAndIsActiveTrueAndIsDeletedFalse(
                                seed.templateKey(), seed.channel(), seed.locale())
                        .isPresent();
                if (exists) {
                    log.debug("Template {}/{} already present, leaving it alone", seed.templateKey(), seed.channel());
                    continue;
                }

                NotificationTemplate template = new NotificationTemplate();
                template.setTemplateKey(seed.templateKey());
                template.setChannel(seed.channel());
                template.setLocale(seed.locale());
                template.setSubjectTemplate(seed.subject());
                template.setBodyTemplate(read(seed.resource()));
                templateRepository.save(template);

                log.info("Seeded notification template {}/{} from {}",
                        seed.templateKey(), seed.channel(), seed.resource());

            } catch (Exception e) {
                // A missing template surfaces loudly at send time as a failed
                // delivery with a reason; it must not stop the service booting
                // and take the rest of notification-service down with it.
                log.error("Could not seed template {}/{}: {}",
                        seed.templateKey(), seed.channel(), e.getMessage(), e);
            }
        }
    }

    private String read(String resource) throws IOException {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
