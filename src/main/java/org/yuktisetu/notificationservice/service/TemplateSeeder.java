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

    /**
     * Every key a producer can name, on every channel that producer asks for.
     *
     * The pairing matters more than it looks. DispatchIngestionService loads
     * templates for exactly the channels on the dispatch and throws if it finds
     * none -- so a key seeded for EMAIL only, sent with channels {EMAIL, IN_APP},
     * silently produces in-app rows rendered from the email's HTML. Every entry
     * in Dispatches must therefore appear here once per channel it requests.
     *
     * Email bodies are HTML files so they can be opened in a browser while being
     * edited. In-app bodies are one-line text files: the bell renders a sentence,
     * not a document, and pushing HTML into it would put markup on the student's
     * screen.
     */
    private static final List<Seed> SEEDS = List.of(
            new Seed("invite-role-email", NotificationChannel.EMAIL, "en",
                    "[[${inviteeName}]], you've been invited to YuktiSetu",
                    "email/template/invite-role-email.html"),

            // --- Student-facing -------------------------------------------
            new Seed("account-activated", NotificationChannel.EMAIL, "en",
                    "Your YuktiSetu account is active",
                    "email/template/account-activated.html"),
            new Seed("account-activated", NotificationChannel.IN_APP, "en",
                    "Account activated",
                    "inapp/template/account-activated.txt"),

            new Seed("drive-published", NotificationChannel.EMAIL, "en",
                    "[[${companyName}]] is hiring: [[${postingTitle}]]",
                    "email/template/drive-published.html"),
            new Seed("drive-published", NotificationChannel.IN_APP, "en",
                    "New drive: [[${postingTitle}]] at [[${companyName}]]",
                    "inapp/template/drive-published.txt"),

            new Seed("drive-updated", NotificationChannel.EMAIL, "en",
                    "[[${companyName}]] drive updated: [[${postingTitle}]]",
                    "email/template/drive-updated.html"),
            new Seed("drive-updated", NotificationChannel.IN_APP, "en",
                    "Drive updated: [[${postingTitle}]] at [[${companyName}]]",
                    "inapp/template/drive-updated.txt"),

            new Seed("drive-reopened", NotificationChannel.EMAIL, "en",
                    "Applications reopened: [[${postingTitle}]] at [[${companyName}]]",
                    "email/template/drive-reopened.html"),
            new Seed("drive-reopened", NotificationChannel.IN_APP, "en",
                    "Applications reopened: [[${postingTitle}]]",
                    "inapp/template/drive-reopened.txt"),

            new Seed("application-submitted", NotificationChannel.EMAIL, "en",
                    "Application received: [[${postingTitle}]] at [[${companyName}]]",
                    "email/template/application-submitted.html"),
            new Seed("application-submitted", NotificationChannel.IN_APP, "en",
                    "Application received: [[${postingTitle}]]",
                    "inapp/template/application-submitted.txt"),

            new Seed("application-withdrawn", NotificationChannel.EMAIL, "en",
                    "You withdrew from [[${postingTitle}]] at [[${companyName}]]",
                    "email/template/application-withdrawn.html"),
            new Seed("application-withdrawn", NotificationChannel.IN_APP, "en",
                    "Withdrawn from [[${postingTitle}]]",
                    "inapp/template/application-withdrawn.txt"),

            new Seed("application-disqualified", NotificationChannel.EMAIL, "en",
                    "Update on your application to [[${companyName}]]",
                    "email/template/application-disqualified.html"),
            new Seed("application-disqualified", NotificationChannel.IN_APP, "en",
                    "Removed from [[${postingTitle}]]",
                    "inapp/template/application-disqualified.txt"),

            // Coding handle checks. EMAIL as well: the first asks the student
            // to act before a deadline, and the other two are outcomes they
            // would dispute.
            new Seed("coding-handle-check-needed", NotificationChannel.EMAIL, "en",
                    "Action needed: check your [[${platformName}]] username",
                    "email/template/coding-handle-check-needed.html"),
            new Seed("coding-handle-check-needed", NotificationChannel.IN_APP, "en",
                    "Check your [[${platformName}]] username",
                    "inapp/template/coding-handle-check-needed.txt"),

            new Seed("coding-handle-still-failing", NotificationChannel.EMAIL, "en",
                    "[[${platformName}]] still could not be read",
                    "email/template/coding-handle-still-failing.html"),
            new Seed("coding-handle-still-failing", NotificationChannel.IN_APP, "en",
                    "[[${platformName}]] still unreadable",
                    "inapp/template/coding-handle-still-failing.txt"),

            new Seed("coding-capture-flagged", NotificationChannel.EMAIL, "en",
                    "Your application goes to TnP review",
                    "email/template/coding-capture-flagged.html"),
            new Seed("coding-capture-flagged", NotificationChannel.IN_APP, "en",
                    "Application sent to TnP review",
                    "inapp/template/coding-capture-flagged.txt"),

            new Seed("shortlist-result", NotificationChannel.EMAIL, "en",
                    "Shortlist result: [[${postingTitle}]] at [[${companyName}]]",
                    "email/template/shortlist-result.html"),
            new Seed("shortlist-result", NotificationChannel.IN_APP, "en",
                    "Shortlist published: [[${postingTitle}]]",
                    "inapp/template/shortlist-result.txt"),

            // --- Staff-facing ---------------------------------------------
            // Two of these three are IN_APP only, and that is the anti-spam
            // policy expressed in the seed list: staff get a bell entry for an
            // outcome, and an email only when a drive is silently broken.
            new Seed("eligibility-run-complete", NotificationChannel.IN_APP, "en",
                    "[[${postingTitle}]]: [[${eligible}]] students eligible",
                    "inapp/template/eligibility-run-complete.txt"),

            new Seed("eligibility-run-failed", NotificationChannel.EMAIL, "en",
                    "Action needed: eligibility failed for [[${postingTitle}]]",
                    "email/template/eligibility-run-failed.html"),
            new Seed("eligibility-run-failed", NotificationChannel.IN_APP, "en",
                    "Eligibility FAILED: [[${postingTitle}]]",
                    "inapp/template/eligibility-run-failed.txt"),

            new Seed("applicant-pool-sealed", NotificationChannel.IN_APP, "en",
                    "[[${postingTitle}]] closed with [[${applicantCount}]] applicants",
                    "inapp/template/applicant-pool-sealed.txt"),

            // EMAIL as well as IN_APP, unlike the other staff notices. This one
            // HOLDS a drive pending a human decision, and a drive waiting silently
            // for an approval nobody saw looks exactly like a drive that broke.
            new Seed("coding-data-review-required", NotificationChannel.EMAIL, "en",
                    "Shortlisting held: [[${platformName}]] unavailable for [[${postingTitle}]]",
                    "email/template/coding-data-review-required.html"),
            new Seed("coding-data-review-required", NotificationChannel.IN_APP, "en",
                    "Shortlisting HELD: [[${postingTitle}]] needs your review",
                    "inapp/template/coding-data-review-required.txt"));

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
