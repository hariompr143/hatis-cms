package com.hatis.platform.cms.application;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Neutralises HTML before it is stored.
 *
 * <p>A CMS is a stored-XSS amplifier: whatever an editor saves is later rendered to
 * every visitor of the customer's site. Sanitising at write time means no
 * downstream renderer — including a customer's own application consuming the
 * delivery API — has to remember to do it.
 *
 * <p>The safelist allows ordinary editorial formatting and explicitly removes
 * scripts, event handlers, {@code javascript:} URLs, iframes, forms and styles.
 */
public final class RichTextSanitizer {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("figure", "figcaption", "section", "span")
            .addAttributes(":all", "data-align")
            .removeTags("script", "style", "iframe", "object", "embed", "form", "input", "button", "link", "meta")
            .removeAttributes(":all", "style", "onload", "onerror", "onclick", "onfocus", "onmouseover")
            .preserveRelativeLinks(false);

    static {
        // Only http(s), mailto and relative links. javascript: and data: are the
        // vectors that matter most in pasted content.
        SAFELIST.addProtocols("a", "href", "http", "https", "mailto");
        SAFELIST.addProtocols("img", "src", "http", "https");
    }

    private RichTextSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, "", SAFELIST, outputSettings);
    }

    /** Flattens rich text to plain text for the search index. */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).text();
    }
}
