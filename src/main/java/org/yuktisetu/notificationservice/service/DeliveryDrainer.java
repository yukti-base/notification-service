package org.yuktisetu.notificationservice.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.notification.db.Notification;
import org.yuktisetu.notification.db.NotificationDelivery;
import org.yuktisetu.notification.db.NotificationTemplate;
import org.yuktisetu.notification.model.DeliveryProvider;
import org.yuktisetu.notification.model.NotificationChannel;
import org.yuktisetu.notification.repository.NotificationDeliveryRepository;
import org.yuktisetu.notification.repository.NotificationTemplateRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Drains queued deliveries and actually sends them.
 *
 * Batching rule, same as everywhere else on this service: one
 * mailSender.send(MimeMessage[]) per chunk opens one SMTP conversation for the
 * whole chunk, where a loop of send(message) opens one per student. On a
 * 500-recipient chunk against Gmail that is the difference between one
 * connection and five hundred, and Gmail rate-limits the latter into failure.
 *
 * Claiming uses FOR UPDATE SKIP LOCKED (the query already lives in
 * notification-dal), so two instances can drain concurrently without either
 * blocking on the other's rows or double-sending.
 *
 * Status writes go through NotificationBatchWriter rather than save() per row,
 * so a drained chunk costs two UPDATE statements: one for the sent ids, one for
 * the failed ones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryDrainer {

    private static final String DEFAULT_LOCALE = "en";

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationBatchWriter batchWriter;
    private final TemplateRenderer templateRenderer;
    private final JavaMailSender mailSender;

    @Value("${yuktisetu.notification.drain.batch-size:200}")
    private int batchSize;

    @Value("${yuktisetu.notification.drain.max-attempts:8}")
    private int maxAttempts;

    @Value("${spring.mail.from:no-reply@yuktisetu.local}")
    private String fromAddress;

    /**
     * REQUIRES_NEW so one poisoned batch cannot roll back a caller's work, and
     * so the claim's row locks are released at the end of this batch rather than
     * held for the life of the sweep.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int drainOnce() {
        try {
            log.debug("Drain pass starting: claiming up to {} deliveries (FOR UPDATE SKIP LOCKED)", batchSize);
            List<NotificationDelivery> claimed = deliveryRepository.claimRetryBatch(batchSize);
            if (claimed.isEmpty()) {
                log.debug("Drain pass found nothing due: no PENDING/RETRY row has next_attempt_at <= now()");
                return 0;
            }
            if (log.isDebugEnabled()) {
                claimed.forEach(d -> log.debug(
                        "  claimed deliveryId={} channel={} status={} attempts={} template={} to={} sourceEvent={}",
                        d.getId(), d.getChannel(), d.getStatus(), d.getAttempts(),
                        d.getTemplateKey(), d.getRecipientAddress(), d.getSourceEventId()));
            }

            // Group by channel once -- the send strategy differs per channel and
            // we want one pass over the batch, not one pass per channel.
            Map<NotificationChannel, List<NotificationDelivery>> byChannel =
                    new EnumMap<>(NotificationChannel.class);
            for (NotificationDelivery d : claimed) {
                byChannel.computeIfAbsent(d.getChannel(), k -> new ArrayList<>()).add(d);
            }

            log.debug("Claimed {} deliveries across {} channel(s): {}", claimed.size(), byChannel.size(),
                    byChannel.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue().size()).toList());

            int sent = 0;
            for (Map.Entry<NotificationChannel, List<NotificationDelivery>> entry : byChannel.entrySet()) {
                sent += switch (entry.getKey()) {
                    case IN_APP -> completeInApp(entry.getValue());
                    case EMAIL, GOOGLE_GROUP -> sendEmailBatch(entry.getValue());
                };
            }

            log.info("Drain pass complete: claimed={} sent={}", claimed.size(), sent);
            return sent;

        } catch (Exception e) {
            log.error("Drain pass failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * In-app needs no transport: the notifications row written at ingestion IS
     * the delivery. The row exists so the bell has the same audit trail as email
     * -- "was this student told" has one answer table, not two.
     */
    private int completeInApp(List<NotificationDelivery> deliveries) {
        List<Long> ids = new ArrayList<>(deliveries.size());
        List<Long> orphaned = new ArrayList<>();

        for (NotificationDelivery d : deliveries) {
            if (d.getNotification() == null) {
                orphaned.add(d.getId());
            } else {
                ids.add(d.getId());
            }
        }

        log.debug("IN_APP: completing {} delivery(ies), {} orphaned with no notification row",
                ids.size(), orphaned.size());

        batchWriter.markSent(ids, DeliveryProvider.IN_APP.name(), null);
        batchWriter.markFailed(orphaned, maxAttempts, "NO_NOTIFICATION_ROW",
                "IN_APP delivery has no notification row to display");
        return ids.size();
    }

    private int sendEmailBatch(List<NotificationDelivery> deliveries) {
        List<MimeMessage> messages = new ArrayList<>(deliveries.size());
        List<Long> buildable = new ArrayList<>(deliveries.size());
        List<Long> unbuildable = new ArrayList<>();
        String failureReason = null;

        for (NotificationDelivery d : deliveries) {
            try {
                messages.add(buildMessage(d));
                buildable.add(d.getId());
                log.debug("  built message for deliveryId={} to={}", d.getId(), d.getRecipientAddress());
            } catch (Exception e) {
                // One student's broken template or bad address must not stop the
                // other 499 from being told about the drive.
                log.error("Could not build message for deliveryId={} address={}: {}",
                        d.getId(), d.getRecipientAddress(), e.getMessage());
                unbuildable.add(d.getId());
                failureReason = e.getMessage();
            }
        }

        batchWriter.markFailed(unbuildable, maxAttempts, "BUILD_FAILED", failureReason);

        if (messages.isEmpty()) {
            log.debug("No sendable messages in this chunk -- all {} failed to build", unbuildable.size());
            return 0;
        }

        try {
            // The whole point: one call, one SMTP conversation, N messages.
            log.debug("Opening one SMTP conversation for {} message(s), from={}", messages.size(), fromAddress);
            long startedAt = System.currentTimeMillis();
            mailSender.send(messages.toArray(new MimeMessage[0]));
            log.debug("SMTP send returned in {} ms; marking {} delivery(ies) SENT",
                    System.currentTimeMillis() - startedAt, buildable.size());
            batchWriter.markSent(buildable, DeliveryProvider.SMTP.name(), null);
            return buildable.size();

        } catch (Exception e) {
            // The send is all-or-nothing from our side -- we cannot tell which of
            // the batch landed, so the whole chunk retries. The unique constraint
            // on (source_event_id, recipient, channel) is what stops a retry
            // becoming a second email for anyone who did receive it.
            log.error("Batch email send failed for {} messages: {}", messages.size(), e.getMessage(), e);
            batchWriter.markFailed(buildable, maxAttempts, "SMTP_SEND_FAILED", e.getMessage());
            return 0;
        }
    }

    private MimeMessage buildMessage(NotificationDelivery delivery) throws Exception {
        String subject;
        String body;

        Notification notification = delivery.getNotification();
        if (notification != null) {
            // Rendered once at ingestion; reuse it rather than re-rendering per
            // retry, so a template edited after the blast cannot silently change
            // what a student is told on the second attempt.
            subject = notification.getTitle();
            body = notification.getBody();
            log.debug("  deliveryId={} using body rendered at ingestion (notificationId={})",
                    delivery.getId(), notification.getId());
        } else {
            // Group alias: no per-recipient variables exist, so render from the
            // template with the dispatch's common variables only.
            NotificationTemplate template = templateRepository
                    .findByTemplateKeyAndChannelAndLocaleAndIsActiveTrueAndIsDeletedFalse(
                            delivery.getTemplateKey(), NotificationChannel.EMAIL, DEFAULT_LOCALE)
                    .orElseThrow(() -> new IllegalStateException(
                            "No EMAIL template '" + delivery.getTemplateKey() + "' for group alias send"));
            TemplateRenderer.Rendered rendered =
                    templateRenderer.render(template, Map.of(), Map.of(), delivery.getTemplateKey());
            subject = rendered.subject();
            body = rendered.body();
            log.debug("  deliveryId={} rendered group-alias body from template '{}'",
                    delivery.getId(), delivery.getTemplateKey());
        }

        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(
                mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress);
        helper.setTo(delivery.getRecipientAddress());
        helper.setSubject(subject == null ? "" : subject);

        // multipart/alternative, not HTML alone. An HTML-only body is one of the
        // cheapest signals a spam filter has -- real senders emit both parts, and
        // markup with no text twin reads as something a script assembled. The two
        // arguments here are (plainText, htmlText); the single-argument form we
        // used before sent HTML only.
        String html = body == null ? "" : body;
        helper.setText(PlainTextFallback.from(html), html);
        return mime;
    }
}
