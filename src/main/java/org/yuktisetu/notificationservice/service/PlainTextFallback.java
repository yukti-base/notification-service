package org.yuktisetu.notificationservice.service;

import java.util.regex.Pattern;

/**
 * Derives a readable plain-text version of an HTML email body.
 *
 * Why it exists: an HTML-only message is one of the cheapest signals a spam
 * filter has. A genuine bulk sender emits multipart/alternative with both parts;
 * a message carrying markup and nothing else looks like something assembled by a
 * script, which is what our invite mail was. It also means the text actually
 * arrives for anyone reading in a plain-text client or a screen reader that
 * prefers the text part.
 *
 * Derived rather than authored as a second template on purpose. Two templates
 * drift: the TnP edits the HTML in the database, nobody remembers the text
 * twin, and within a month the plain-text part is telling invitees something
 * different from the HTML they can see. One source, mechanically reduced, cannot
 * disagree with itself.
 *
 * Links are rendered as "text (url)" rather than dropped, because the invite
 * link IS the message -- a plain-text part without it would be worse than none.
 */
final class PlainTextFallback {

    private PlainTextFallback() {}

    private static final Pattern SCRIPT_OR_STYLE =
            Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** Captures the href and the link text so both survive the strip. */
    private static final Pattern ANCHOR =
            Pattern.compile("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_END =
            Pattern.compile("</(p|div|tr|table|h[1-6]|li|blockquote)\\s*>|<br\\s*/?>|<hr\\s*/?>",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \t]+\n");

    static String from(String html) {
        if (html == null || html.isBlank()) return "";

        String s = SCRIPT_OR_STYLE.matcher(html).replaceAll("");

        s = ANCHOR.matcher(s).replaceAll(match -> {
            String href = match.group(1).trim();
            String label = TAG.matcher(match.group(2)).replaceAll("").trim();
            // A link whose text is already the URL must not become "url (url)".
            if (label.isEmpty() || label.equalsIgnoreCase(href)) return quoteReplacement(href);
            return quoteReplacement(label + " (" + href + ")");
        });

        s = BLOCK_END.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = unescape(s);

        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = TRAILING_SPACE.matcher(s).replaceAll("\n");
        s = BLANK_RUN.matcher(s).replaceAll("\n\n");

        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            out.append(line.strip().replaceAll("[ \t]{2,}", " ")).append('\n');
        }
        return BLANK_RUN.matcher(out.toString()).replaceAll("\n\n").strip();
    }

    /** The entities this codebase's templates actually emit, plus the numeric forms escaping produces. */
    private static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&copy;", "(c)")
                .replace("&middot;", "-")
                .replace("&mdash;", "--")
                .replace("&ndash;", "-");
    }

    /** $ and \ in a URL would otherwise be read as replacement syntax. */
    private static String quoteReplacement(String s) {
        return java.util.regex.Matcher.quoteReplacement(s);
    }
}
