package org.yuktisetu.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.yuktisetu.notification.db.NotificationTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders DB-stored templates. Templates live in notification_templates rather
 * than on the classpath so the TnP Cell can reword a message after the student
 * team has graduated -- the same "what happens after us" reasoning that produced
 * the Advanced Fields system.
 *
 * The resolver is cacheable: Thymeleaf keys its cache on the template text, so a
 * 2000-recipient blast parses the template once and then only re-evaluates the
 * expressions per recipient. Without the cache it would re-parse 2000 times.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateRenderer {

    private final SpringTemplateEngine stringTemplateEngine = buildEngine();

    /**
     * SpringTemplateEngine, not the plain TemplateEngine. The plain one resolves
     * expressions with OGNL, which Boot does not put on the classpath -- so a
     * hand-built engine compiles fine, starts fine, and then throws
     * NoClassDefFoundError: ognl/PropertyAccessor on the first render. The Spring
     * variant uses SpEL, which is already there.
     *
     * Its own instance rather than the autoconfigured bean: Boot's engine is
     * wired to a classpath resolver for the HTML templates in src/main/resources,
     * and adding a string resolver to it would change how those render for every
     * other user of that bean.
     */
    private static SpringTemplateEngine buildEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCacheable(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /**
     * @param common variables shared by the whole batch, merged once per recipient
     *               rather than rebuilt -- a 2000-recipient blast merges 2000 small
     *               maps, not 2000 copies of the common map plus a merge.
     */
    public Rendered render(NotificationTemplate template,
                           Map<String, Object> common,
                           Map<String, Object> perRecipient,
                           String fallbackSubject) {
        try {
            Context ctx = new Context();
            if (common != null && !common.isEmpty()) {
                ctx.setVariables(common);
            }
            if (perRecipient != null && !perRecipient.isEmpty()) {
                ctx.setVariables(perRecipient);
            }

            String subjectTemplate = template.getSubjectTemplate() != null
                    ? template.getSubjectTemplate()
                    : fallbackSubject;

            String subject = subjectTemplate == null ? "" : stringTemplateEngine.process(subjectTemplate, ctx);
            String body = stringTemplateEngine.process(template.getBodyTemplate(), ctx);
            return new Rendered(subject, body);

        } catch (Exception e) {
            // A broken template must not take the whole blast down -- the other
            // 1999 students still need telling. The caller records this recipient
            // as FAILED with the reason.
            log.error("Template render failed for templateKey={} channel={}: {}",
                    template.getTemplateKey(), template.getChannel(), e.getMessage(), e);
            throw new IllegalStateException("Template render failed for " + template.getTemplateKey(), e);
        }
    }

    /** Merges without mutating the shared common map. */
    public static Map<String, Object> merge(Map<String, Object> common, Map<String, Object> perRecipient) {
        if (perRecipient == null || perRecipient.isEmpty()) {
            return common == null ? Map.of() : common;
        }
        Map<String, Object> merged = new HashMap<>(common == null ? Map.of() : common);
        merged.putAll(perRecipient);
        return merged;
    }

    /**
     * The EMAIL rendering. Thymeleaf HTML-escapes every [[...]] value even in TEXT
     * mode, which is exactly right for the HTML body ("AT&T" becomes AT&amp;T in
     * the markup) and wrong for the subject, a plain-text mail header -- so the
     * subject alone is unescaped. The engine stays in TEXT mode because the
     * templates inline values inside attributes (href="[[${actionUrl}]]"), which
     * HTML mode does not process.
     */
    public Rendered renderEmail(NotificationTemplate template,
                                Map<String, Object> common,
                                Map<String, Object> perRecipient,
                                String fallbackSubject) {
        Rendered r = render(template, common, perRecipient, fallbackSubject);
        return new Rendered(HtmlUtils.htmlUnescape(r.subject()), r.body());
    }

    /**
     * The in-app rendering: title and body are plain text that the portals display
     * as text, so neither may carry HTML entities -- a bell entry must read
     * "AT&T", not "AT&amp;T".
     */
    public Rendered renderText(NotificationTemplate template,
                               Map<String, Object> common,
                               Map<String, Object> perRecipient,
                               String fallbackSubject) {
        Rendered r = render(template, common, perRecipient, fallbackSubject);
        return new Rendered(HtmlUtils.htmlUnescape(r.subject()), HtmlUtils.htmlUnescape(r.body()));
    }

    public record Rendered(String subject, String body) {}
}
