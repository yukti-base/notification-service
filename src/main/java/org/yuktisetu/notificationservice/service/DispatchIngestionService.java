package org.yuktisetu.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.exception.BadRequestException;
import org.yuktisetu.notification.db.NotificationTemplate;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;
import org.yuktisetu.notification.model.NotificationChannel;
import org.yuktisetu.notification.model.NotificationSeverity;
import org.yuktisetu.notification.model.NotificationType;
import org.yuktisetu.notificationservice.dto.DeliveryRow;
import org.yuktisetu.notificationservice.dto.DispatchAcceptedResponse;
import org.yuktisetu.notificationservice.dto.NotificationRow;
import org.yuktisetu.notification.repository.NotificationTemplateRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single definition of "accept a dispatch", shared by the Kafka listener and
 * POST /dispatch. Both doors, one room.
 *
 * The query budget is fixed and does not grow with recipient count:
 *   1. one SELECT for the templates this dispatch needs
 *   2. one multi-row INSERT for the in-app rows (+1 SELECT only if redelivered)
 *   3. one multi-row INSERT for the delivery rows
 * Everything between those is in-memory work over HashMaps. There is no query
 * inside any loop in this class, and nothing here sends mail -- accepting a
 * dispatch is durable-write-then-return, and DeliveryDrainer does the sending.
 *
 * Recipient identity arrives denormalised on the event (userId, email,
 * displayName). This service never resolves a student row to find an address,
 * which is what lets it hold no user-dal dependency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchIngestionService {

    private static final int MAX_RECIPIENTS_PER_DISPATCH = 1000;
    private static final String DEFAULT_LOCALE = "en";

    private final NotificationTemplateRepository templateRepository;
    private final NotificationBatchWriter batchWriter;
    private final TemplateRenderer templateRenderer;
    private final ObjectMapper objectMapper;

    @Transactional
    public DispatchAcceptedResponse ingest(String dispatchId, NotificationDispatchEvent event) {
        try {
            validate(dispatchId, event);

            Set<NotificationChannel> channels = parseChannels(event.channels());
            Map<NotificationChannel, NotificationTemplate> templates = loadTemplates(event.templateKey(), channels);

            List<NotificationDispatchEvent.Recipient> recipients = dedupeRecipients(event.recipients());

            // Persons: one content row each, plus one delivery row per channel.
            List<NotificationRow> notificationRows = new ArrayList<>(recipients.size());
            boolean wantsInApp = channels.contains(NotificationChannel.IN_APP);
            boolean wantsEmail = channels.contains(NotificationChannel.EMAIL);

            NotificationTemplate inAppTemplate = templates.get(NotificationChannel.IN_APP);
            NotificationTemplate emailTemplate = templates.get(NotificationChannel.EMAIL);
            // Content is rendered from whichever template the dispatch actually
            // carries -- in-app if present, else the email one, so an email-only
            // send still stores readable content rather than a raw template.
            NotificationTemplate contentTemplate = inAppTemplate != null ? inAppTemplate : emailTemplate;

            String metadataJson = writeMetadata(event);

            for (NotificationDispatchEvent.Recipient r : recipients) {
                TemplateRenderer.Rendered rendered = templateRenderer.render(
                        contentTemplate, event.commonVariables(), r.variables(), event.subject());

                notificationRows.add(new NotificationRow(
                        r.userId(),
                        safeType(event.notificationType()).name(),
                        safeSeverity(event.severity()).name(),
                        truncate(rendered.subject(), 200),
                        rendered.body(),
                        event.actionUrl(),
                        dispatchId,
                        null,
                        null,
                        metadataJson,
                        null));
            }

            Map<Long, Long> notificationIdsByRecipient =
                    batchWriter.insertNotifications(dispatchId, notificationRows);

            List<DeliveryRow> deliveryRows =
                    new ArrayList<>(recipients.size() * channels.size() + event.broadcastGroupAliases().size());

            for (NotificationDispatchEvent.Recipient r : recipients) {
                Long notificationId = notificationIdsByRecipient.get(r.userId());

                if (wantsInApp) {
                    deliveryRows.add(new DeliveryRow(
                            notificationId, r.userId(), r.email(),
                            NotificationChannel.IN_APP.name(), dispatchId, event.templateKey()));
                }
                if (wantsEmail) {
                    deliveryRows.add(new DeliveryRow(
                            notificationId, r.userId(), r.email(),
                            NotificationChannel.EMAIL.name(), dispatchId, event.templateKey()));
                }
            }

            // Group aliases carry no user and no in-app row -- the TnP's existing
            // Google Group receives the mail like any other address, which is why
            // no Workspace admin API access is ever needed.
            for (String alias : event.broadcastGroupAliases()) {
                deliveryRows.add(new DeliveryRow(
                        null, null, alias,
                        NotificationChannel.GOOGLE_GROUP.name(), dispatchId, event.templateKey()));
            }

            int queued = batchWriter.insertDeliveries(dispatchId, deliveryRows);
            boolean duplicate = queued < deliveryRows.size();

            log.info("Dispatch accepted: id={} type={} recipients={} notifications={} deliveries={}/{} duplicate={}",
                    dispatchId, event.notificationType(), recipients.size(),
                    notificationIdsByRecipient.size(), queued, deliveryRows.size(), duplicate);

            return new DispatchAcceptedResponse(
                    dispatchId, recipients.size(), notificationIdsByRecipient.size(), queued, duplicate);

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Dispatch ingestion failed: id={} type={}: {}",
                    dispatchId, event == null ? null : event.notificationType(), e.getMessage(), e);
            throw new IllegalStateException("Failed to accept dispatch " + dispatchId, e);
        }
    }

    private void validate(String dispatchId, NotificationDispatchEvent event) {
        if (dispatchId == null || dispatchId.isBlank()) {
            throw new BadRequestException("A dispatch id is required -- it is the idempotency key.");
        }
        if (event == null) {
            throw new BadRequestException("Dispatch payload is required.");
        }
        if (event.templateKey() == null || event.templateKey().isBlank()) {
            throw new BadRequestException("templateKey is required.");
        }
        boolean noRecipients = event.recipients() == null || event.recipients().isEmpty();
        boolean noGroups = event.broadcastGroupAliases() == null || event.broadcastGroupAliases().isEmpty();
        if (noRecipients && noGroups) {
            throw new BadRequestException("A dispatch needs at least one recipient or one broadcast group alias.");
        }
        if (!noRecipients && event.recipients().size() > MAX_RECIPIENTS_PER_DISPATCH) {
            // Producers are required to chunk (~500/message). Rejecting loudly is
            // better than accepting a 20000-recipient payload and timing out
            // halfway through a drive blast.
            throw new BadRequestException(
                    "Too many recipients in one dispatch: " + event.recipients().size()
                            + ". Chunk the audience at " + MAX_RECIPIENTS_PER_DISPATCH + " or fewer per dispatch.");
        }
    }

    /**
     * One query for every template this dispatch needs, into an O(1) map --
     * never one lookup per recipient.
     */
    private Map<NotificationChannel, NotificationTemplate> loadTemplates(String templateKey,
                                                                        Set<NotificationChannel> channels) {
        List<NotificationTemplate> found =
                templateRepository.findByTemplateKeyAndLocaleAndChannelInAndIsActiveTrueAndIsDeletedFalse(
                        templateKey, DEFAULT_LOCALE, channels);

        Map<NotificationChannel, NotificationTemplate> byChannel = new EnumMap<>(NotificationChannel.class);
        for (NotificationTemplate t : found) {
            byChannel.put(t.getChannel(), t);
        }

        if (byChannel.isEmpty()) {
            throw new BadRequestException(
                    "No active template found for key '" + templateKey + "' on channels " + channels);
        }
        return byChannel;
    }

    /**
     * A redelivered Kafka message and a re-sent REST call are both handled by the
     * unique constraints, but a single payload listing the same student twice is
     * not -- it would violate the constraint mid-insert and roll back the batch.
     * HashSet on userId, O(1).
     */
    private List<NotificationDispatchEvent.Recipient> dedupeRecipients(
            List<NotificationDispatchEvent.Recipient> recipients) {

        if (recipients == null || recipients.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new HashSet<>(recipients.size());
        List<NotificationDispatchEvent.Recipient> unique = new ArrayList<>(recipients.size());

        for (NotificationDispatchEvent.Recipient r : recipients) {
            if (r.userId() == null || r.email() == null || r.email().isBlank()) {
                log.warn("Skipping recipient with no userId or no email address in dispatch");
                continue;
            }
            if (seen.add(r.userId())) {
                unique.add(r);
            }
        }
        if (unique.size() < recipients.size()) {
            log.info("Dropped {} duplicate or unaddressable recipients from dispatch",
                    recipients.size() - unique.size());
        }
        return unique;
    }

    private Set<NotificationChannel> parseChannels(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BadRequestException("At least one channel is required.");
        }
        Set<NotificationChannel> channels = new HashSet<>();
        for (String c : raw) {
            try {
                channels.add(NotificationChannel.valueOf(c));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown notification channel: " + c);
            }
        }
        return channels;
    }

    private String writeMetadata(NotificationDispatchEvent event) {
        try {
            if (event.commonVariables() == null || event.commonVariables().isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(event.commonVariables());
        } catch (Exception e) {
            log.warn("Could not serialise commonVariables to metadata jsonb, storing null: {}", e.getMessage());
            return null;
        }
    }

    private NotificationType safeType(String raw) {
        try {
            return NotificationType.valueOf(raw);
        } catch (Exception e) {
            log.warn("Unknown notificationType '{}', storing GENERIC", raw);
            return NotificationType.GENERIC;
        }
    }

    private NotificationSeverity safeSeverity(String raw) {
        try {
            return NotificationSeverity.valueOf(raw);
        } catch (Exception e) {
            return NotificationSeverity.INFO;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
