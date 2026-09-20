package com.hatis.platform.cms.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Content validation and HTML sanitising.
 *
 * <p>These are the controls that stop malformed or hostile content reaching the
 * delivery API, so they are tested directly rather than through a controller: the
 * behaviour must not depend on which transport a client used.
 */
class ContentBodyValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["title"],
              "properties": {
                "title":  { "type": "string",  "maxLength": 10 },
                "body":   { "type": "richtext", "maxLength": 500 },
                "hero":   { "type": "media" },
                "rating": { "type": "number",  "minimum": 0, "maximum": 5 },
                "tags":   { "type": "array",   "items": { "type": "string" } },
                "hidden": { "type": "boolean" }
              }
            }
            """;

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a body satisfying the schema is accepted and normalised")
    void acceptsValidBody() {
        Map<String, Object> result = ContentBodyValidator.validate(
                json("""
                        {"title":"Hello","rating":3,"tags":["a","b"],"hidden":true}
                        """),
                json(SCHEMA));

        assertThat(result).containsEntry("title", "Hello")
                .containsEntry("rating", 3.0)
                .containsEntry("hidden", true);
        assertThat(result.get("tags")).isEqualTo(List.of("a", "b"));
    }

    @Test
    @DisplayName("a missing required field is rejected with the field named")
    void rejectsMissingRequiredField() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(json("{\"rating\":1}"), json(SCHEMA)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("a value over maxLength is rejected")
    void rejectsOversizedString() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(
                json("{\"title\":\"far too long for the limit\"}"), json(SCHEMA)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("at most 10 characters");
    }

    @Test
    @DisplayName("a number outside its range is rejected")
    void rejectsOutOfRangeNumber() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(
                json("{\"title\":\"ok\",\"rating\":9}"), json(SCHEMA)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("rating");
    }

    @Test
    @DisplayName("a wrong JSON type is rejected rather than coerced")
    void rejectsWrongType() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(
                json("{\"title\":\"ok\",\"hidden\":\"yes\"}"), json(SCHEMA)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("must be a boolean");
    }

    @Test
    @DisplayName("fields absent from the schema are dropped, not stored")
    void dropsUnknownFields() {
        Map<String, Object> result = ContentBodyValidator.validate(
                json("{\"title\":\"ok\",\"injected\":\"<script>alert(1)</script>\"}"), json(SCHEMA));

        assertThat(result).containsKey("title").doesNotContainKey("injected");
    }

    @Test
    @DisplayName("script tags and event handlers are stripped from rich text")
    void stripsScriptsFromRichText() {
        Map<String, Object> result = ContentBodyValidator.validate(
                json("""
                        {"title":"ok","body":"<p>Hi</p><script>alert('xss')</script><img src=x onerror=alert(1)>"}
                        """),
                json(SCHEMA));

        String body = (String) result.get("body");
        assertThat(body).contains("<p>Hi</p>")
                .doesNotContain("<script")
                .doesNotContain("onerror")
                .doesNotContain("alert");
    }

    @Test
    @DisplayName("javascript: URLs are removed from links")
    void stripsJavascriptUrls() {
        Map<String, Object> result = ContentBodyValidator.validate(
                json("""
                        {"title":"ok","body":"<a href=\\"javascript:alert(1)\\">click</a>"}
                        """),
                json(SCHEMA));

        assertThat((String) result.get("body")).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("a media reference must be an identifier, never inline markup")
    void rejectsInlineMedia() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(
                json("{\"title\":\"ok\",\"hero\":\"<img src=//evil>\"}"), json(SCHEMA)))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    @Test
    @DisplayName("a non-object body is rejected")
    void rejectsNonObjectBody() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(json("[1,2,3]"), json(SCHEMA)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    @DisplayName("a schema that cannot be interpreted is a server-side rule failure")
    void rejectsUnusableSchema() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(json("{\"title\":\"ok\"}"), json("[]")))
                .isInstanceOf(PlatformExceptions.BusinessRuleViolation.class);
    }

    @Test
    @DisplayName("all validation errors are reported together, not one at a time")
    void reportsEveryError() {
        assertThatThrownBy(() -> ContentBodyValidator.validate(
                json("{\"rating\":\"high\",\"hidden\":7}"), json(SCHEMA)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .satisfies(error -> assertThat(error.getMessage()).contains("validation failed"));
    }
}
