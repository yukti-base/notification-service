package org.yuktisetu.notificationservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;
import org.yuktisetu.notification.db.NotificationTemplate;
import org.yuktisetu.notification.model.NotificationChannel;
import org.yuktisetu.notification.repository.NotificationTemplateRepository;
import org.yuktisetu.notificationservice.dto.DeliveryRow;
import org.yuktisetu.notificationservice.dto.NotificationRow;
import org.yuktisetu.notificationservice.service.DispatchIngestionService;
import org.yuktisetu.notificationservice.service.NotificationBatchWriter;
import org.yuktisetu.notificationservice.service.TemplateRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Every email must leave as its HTML template, rendered for that recipient.
 *
 * What went wrong before: ingestion rendered ONE body per recipient, from the
 * in-app template whenever the dispatch had one, and the drainer mailed that.
 * So every drive, receipt and shortlist email went out as the one-line in-app
 * text, and the branded HTML templates were loaded and never used.
 */
class EmailRenderingTest {

    private static String resource(String path) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static NotificationTemplate template(NotificationChannel channel, String subject, String path)
            throws Exception {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateKey("shortlist-result");
        t.setChannel(channel);
        t.setSubjectTemplate(subject);
        t.setBodyTemplate(resource(path));
        return t;
    }

    @Test
    @DisplayName("the email delivery carries the HTML template; the in-app row keeps the text")
    @SuppressWarnings("unchecked")
    void emailUsesTheHtmlTemplate() throws Exception {
        NotificationTemplateRepository repo = mock(NotificationTemplateRepository.class);
        when(repo.findByTemplateKeyAndLocaleAndChannelInAndIsActiveTrueAndIsDeletedFalse(any(), any(), any()))
                .thenReturn(List.of(
                        template(NotificationChannel.EMAIL,
                                "Shortlist result: [[${postingTitle}]] at [[${companyName}]]",
                                "email/template/shortlist-result.html"),
                        template(NotificationChannel.IN_APP, "Shortlist published: [[${postingTitle}]]",
                                "inapp/template/shortlist-result.txt")));
        NotificationBatchWriter writer = mock(NotificationBatchWriter.class);
        when(writer.insertNotifications(any(), any())).thenReturn(Map.of(7L, 70L));
        when(writer.insertDeliveries(any(), any())).thenAnswer(a -> ((List<?>) a.getArgument(1)).size());

        DispatchIngestionService service =
                new DispatchIngestionService(repo, writer, new TemplateRenderer(), JsonMapper.builder().build());

        service.ingest("evt-1", new NotificationDispatchEvent(
                "GENERIC", "INFO", "shortlist-result", null, "http://localhost:3001/drives/4",
                Map.of("companyName", "AT&T", "postingTitle", "Backend <Engineer>",
                        "outcome", "You have been shortlisted.", "scoreNote", "",
                        "actionUrl", "http://localhost:3001/drives/4?a=1&b=2"),
                List.of(new NotificationDispatchEvent.Recipient(7L, "asha@yuktisetu.test", "Asha Rao", Map.of())),
                List.of(), Set.of("EMAIL", "IN_APP")));

        ArgumentCaptor<List<DeliveryRow>> deliveries = ArgumentCaptor.forClass(List.class);
        verify(writer).insertDeliveries(eq("evt-1"), deliveries.capture());
        DeliveryRow email = deliveries.getValue().stream()
                .filter(d -> d.channel().equals("EMAIL")).findFirst().orElseThrow();
        DeliveryRow inApp = deliveries.getValue().stream()
                .filter(d -> d.channel().equals("IN_APP")).findFirst().orElseThrow();

        assertNotNull(email.renderedBody(), "the email row must carry its own rendered HTML");
        assertTrue(email.renderedBody().contains("<!DOCTYPE html>"), "the HTML template, not the in-app text");
        assertTrue(email.renderedBody().contains("Asha Rao"), "studentName is filled from displayName");
        assertTrue(email.renderedBody().contains("AT&amp;T"), "values are HTML-escaped exactly once in the body");
        assertFalse(email.renderedBody().contains("&amp;amp;"), "never escaped twice");
        assertTrue(email.renderedBody().contains("Backend &lt;Engineer&gt;"));
        assertFalse(email.renderedBody().contains("[[${"), "every expression evaluated");
        assertEquals("Shortlist result: Backend <Engineer> at AT&T", email.renderedSubject(),
                "the subject is a plain-text header, so it is NOT escaped");
        assertNull(inApp.renderedBody(), "in-app deliveries carry no email body");

        ArgumentCaptor<List<NotificationRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(writer).insertNotifications(eq("evt-1"), rows.capture());
        assertFalse(rows.getValue().get(0).body().contains("<html"), "the bell keeps the short in-app text");
        assertTrue(rows.getValue().get(0).body().contains("AT&T"), "in-app text carries no HTML entities");
        assertEquals("Shortlist published: Backend <Engineer>", rows.getValue().get(0).title());
    }

    @Test
    @DisplayName("every shipped email template renders to complete HTML with no expression left")
    void everyShippedTemplateRenders() throws Exception {
        TemplateRenderer renderer = new TemplateRenderer();
        var dir = new ClassPathResource("email/template").getFile();
        String[] files = dir.list((d, n) -> n.endsWith(".html"));
        assertNotNull(files);
        assertTrue(files.length >= 13, "expected every shipped email template, found " + files.length);
        for (String f : files) {
            NotificationTemplate t = template(NotificationChannel.EMAIL, "s", "email/template/" + f);
            String body = renderer.renderEmail(t, Map.of(), Map.of("studentName", "Asha"), "s").body();
            assertTrue(body.contains("<!DOCTYPE html>"), f);
            assertFalse(body.contains("[[${"), f + " left an expression unevaluated");
        }
    }
}
