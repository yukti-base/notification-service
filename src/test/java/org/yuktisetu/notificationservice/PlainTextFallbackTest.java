package org.yuktisetu.notificationservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yuktisetu.notification.db.NotificationTemplate;
import org.yuktisetu.notification.model.NotificationChannel;
import org.yuktisetu.notificationservice.service.TemplateRenderer;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plain-text half of the multipart/alternative message.
 *
 * It is derived from the rendered HTML rather than authored separately, so the
 * two parts cannot drift apart when the TnP rewords the template in the
 * database. That makes these assertions the only thing standing between a
 * template edit and a text part that silently loses the invite link -- which
 * would be worse than sending no text part at all.
 *
 * PlainTextFallback is package-private by design (nothing outside the drainer
 * should be reducing HTML to text), so it is reached reflectively here rather
 * than widened just to be testable.
 */
class PlainTextFallbackTest {

    private static String toText(String html) throws Exception {
        Class<?> c = Class.forName("org.yuktisetu.notificationservice.service.PlainTextFallback");
        Method m = c.getDeclaredMethod("from", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, html);
    }

    @Test
    @DisplayName("a link keeps both its label and its URL")
    void linksKeepLabelAndUrl() throws Exception {
        assertEquals("Accept Invitation (http://x/invite?t=1)",
                toText("<a href=\"http://x/invite?t=1\">Accept Invitation</a>"));
    }

    /** The fallback link's text is already the URL; "url (url)" reads as a bug. */
    @Test
    @DisplayName("a link whose text is already the URL is not duplicated")
    void selfLabelledLinkIsNotDuplicated() throws Exception {
        assertEquals("http://x/invite", toText("<a href=\"http://x/invite\">http://x/invite</a>"));
    }

    @Test
    @DisplayName("block elements become line breaks rather than running together")
    void blocksBecomeLines() throws Exception {
        assertEquals("Hi Asha,\nYou have been invited.",
                toText("<p>Hi Asha,</p><p>You have been invited.</p>"));
    }

    @Test
    @DisplayName("style blocks are dropped, not flattened into the text")
    void styleBlocksAreDropped() throws Exception {
        String text = toText("<style>.a{color:red}</style><p>Hello</p>");
        assertEquals("Hello", text);
        assertFalse(text.contains("color"));
    }

    @Test
    @DisplayName("escaped entities are turned back into readable characters")
    void entitiesAreDecoded() throws Exception {
        assertEquals("O'Brien & Co <tag>", toText("<p>O&#39;Brien &amp; Co &lt;tag&gt;</p>"));
    }

    /** The assertion that matters: the invite is useless without its link. */
    @Test
    @DisplayName("the real invite template yields readable text that still contains the link")
    void realTemplateProducesUsableText() throws Exception {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateKey("invite-role-email");
        t.setChannel(NotificationChannel.EMAIL);
        t.setSubjectTemplate("s");
        try (var in = new ClassPathResource("email/template/invite-role-email.html").getInputStream()) {
            t.setBodyTemplate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        String html = new TemplateRenderer().render(t, Map.of(),
                Map.of("inviteeName", "Asha Patil", "inviteLink", "http://localhost:3000/accept-invite?token=abc"),
                "s").body();

        String text = toText(html);

        assertTrue(text.contains("Asha Patil"), text);
        assertTrue(text.contains("http://localhost:3000/accept-invite?token=abc"),
                "the invite link must survive into the text part:\n" + text);
        assertTrue(text.contains("YuktiSetu"));
        assertFalse(text.contains("<"), "no markup may remain:\n" + text);
        assertFalse(text.contains("font-size"), "CSS leaked into the text part:\n" + text);
        assertFalse(text.contains("\n\n\n"), "collapsed blank runs expected");
        assertTrue(text.length() > 150 && text.length() < 1200, "unreasonable length: " + text.length());
    }
}
