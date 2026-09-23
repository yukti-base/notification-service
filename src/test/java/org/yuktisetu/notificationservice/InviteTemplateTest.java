package org.yuktisetu.notificationservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yuktisetu.notification.db.NotificationTemplate;
import org.yuktisetu.notification.model.NotificationChannel;
import org.yuktisetu.notificationservice.service.TemplateRenderer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped invite template, rendered exactly as the drainer renders it.
 *
 * What went wrong before: the row in notification_templates was a one-line
 * placeholder, not the branded template that had been sitting in the repository
 * all along. Nothing caught it, because nothing ever asserted what an invite
 * actually looks like when it leaves the service.
 *
 * The renderer runs in Thymeleaf TEXT mode, where th:* attributes are inert and
 * only [[...]] / [(...)] inline expressions evaluate. A template authored with
 * th:href would therefore render as literal markup with no link in it and no
 * error anywhere -- which is precisely the kind of silent breakage worth a test.
 */
class InviteTemplateTest {

    private static final String KEY = "invite-role-email";
    private static final String RESOURCE = "email/template/" + KEY + ".html";
    private static final String LINK = "http://localhost:3000/accept-invite?token=abc123&x=1";

    private final TemplateRenderer renderer = new TemplateRenderer();

    private static String shippedBody() throws Exception {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private TemplateRenderer.Rendered render(String name) throws Exception {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateKey(KEY);
        t.setChannel(NotificationChannel.EMAIL);
        t.setSubjectTemplate("[[${inviteeName}]], you've been invited to YuktiSetu");
        t.setBodyTemplate(shippedBody());
        return renderer.render(t, Map.of(),
                Map.of("inviteeName", name, "inviteLink", LINK), "fallback");
    }

    @Test
    @DisplayName("the shipped template is the full branded email, not a placeholder")
    void templateIsTheRealThing() throws Exception {
        String body = shippedBody();
        assertTrue(body.length() > 3000,
                "the stub that shipped to real users was 108 characters; got " + body.length());
        assertTrue(body.contains("<!DOCTYPE html>"));
        assertTrue(body.contains("Central Placement Management System"));
        assertTrue(body.contains("Accept Invitation"), "the call-to-action button must be present");
    }

    /** th:href renders as nothing in TEXT mode -- an invite with no working link. */
    @Test
    @DisplayName("the template carries no Thymeleaf attributes, which TEXT mode would ignore")
    void noAttributeSyntaxSurvives() throws Exception {
        String body = shippedBody();
        assertFalse(body.contains("th:href"), "th:href is inert in TEXT mode; the link would vanish");
        assertFalse(body.contains("th:text"));
        assertFalse(body.contains("xmlns:th"));
    }

    @Test
    @DisplayName("rendering substitutes the name and the link everywhere they appear")
    void rendersNameAndLink() throws Exception {
        TemplateRenderer.Rendered out = render("Asha Patil");

        assertTrue(out.body().contains("Asha Patil"));
        assertFalse(out.body().contains("${"), "an unsubstituted expression leaked into the email");
        assertFalse(out.body().contains("[["), "an unevaluated inline expression leaked into the email");

        // Once in the button href, once in the fallback href, once as visible text.
        assertEquals(3, countOccurrences(out.body(), "accept-invite?token=abc123"),
                "the link must appear in the button, the fallback href and the visible text");
        assertEquals("Asha Patil, you've been invited to YuktiSetu", out.subject());
    }

    /**
     * The name is typed by an admin and lands inside the HTML body. Escaped
     * output is what stops it carrying markup into everyone's inbox.
     */
    @Test
    @DisplayName("a name containing markup is escaped, not injected")
    void nameIsEscaped() throws Exception {
        TemplateRenderer.Rendered out = render("<script>alert(1)</script>");

        assertFalse(out.body().contains("<script>alert(1)</script>"), "raw markup reached the body");
        assertTrue(out.body().contains("&lt;script&gt;"), "expected the escaped form");
    }

    @Test
    @DisplayName("the ampersand in the invite link survives as a usable URL")
    void ampersandInLinkIsHtmlEscapedNotBroken() throws Exception {
        String body = render("Asha").body();
        // &amp; is correct inside an href; the browser decodes it back to &.
        assertTrue(body.contains("accept-invite?token=abc123&amp;x=1"),
                "expected the ampersand HTML-escaped inside the attribute");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) { n++; i += needle.length(); }
        return n;
    }
}
