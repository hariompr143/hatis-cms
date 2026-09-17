package com.hatis.platform.cms.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.hatis.platform.shared.error.PlatformExceptions;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates a content body against a content type's schema.
 *
 * <p>This runs on the server for every write. The editor UI validates too, but that
 * is a convenience: an API client must not be able to store content that the
 * delivery API's consumers were promised would not exist.
 *
 * <p>Supported schema subset — deliberately the part customers actually use:
 * <pre>
 * {
 *   "type": "object",
 *   "required": ["title"],
 *   "properties": {
 *     "title":  { "type": "string",   "maxLength": 200 },
 *     "body":   { "type": "richtext", "maxLength": 100000 },
 *     "hero":   { "type": "media" },
 *     "tags":   { "type": "array",    "items": { "type": "string" } },
 *     "rating": { "type": "number",   "minimum": 0, "maximum": 5 }
 *   }
 * }
 * </pre>
 *
 * <p>Rich text is additionally sanitised: HTML that survives into storage is the
 * stored-XSS vector that matters most in a CMS, so it is neutralised at write time
 * rather than at render time.
 */
public final class ContentBodyValidator {

    private ContentBodyValidator() {
    }

    /**
     * Validates and normalises the body.
     *
     * @return the normalised body (rich text sanitised, unknown fields removed)
     */
    public static Map<String, Object> validate(JsonNode body, JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw new PlatformExceptions.BusinessRuleViolation("The content type has no usable schema");
        }
        if (body == null || !body.isObject()) {
            throw new PlatformExceptions.Validation("The content body must be a JSON object", Map.of());
        }

        JsonNode properties = schema.path("properties");
        Map<String, Object> normalised = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        for (String required : iterable(schema.path("required"))) {
            if (!body.hasNonNull(required)) {
                errors.put(required, "is required");
            }
        }

        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            JsonNode definition = properties.path(name);
            if (definition.isMissingNode() || definition.isNull()) {
                // Unknown fields are dropped rather than rejected: a client on a
                // newer schema must not be able to write fields this version
                // does not know how to serve.
                continue;
            }
            try {
                normalised.put(name, coerce(name, value, definition));
            } catch (PlatformExceptions.Validation e) {
                errors.put(name, e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new PlatformExceptions.Validation("Content body validation failed",
                    Map.of("fieldErrors", errors));
        }
        return normalised;
    }

    private static Object coerce(String name, JsonNode value, JsonNode definition) {
        if (value.isNull()) {
            return null;
        }
        String type = definition.path("type").asText("string");
        switch (type) {
            case "string" -> {
                if (!value.isTextual()) {
                    throw invalid(name, "must be a string");
                }
                return limit(name, value.asText(), definition);
            }
            case "richtext" -> {
                if (!value.isTextual()) {
                    throw invalid(name, "must be a string");
                }
                return limit(name, RichTextSanitizer.sanitize(value.asText()), definition);
            }
            case "number", "integer" -> {
                if (!value.isNumber()) {
                    throw invalid(name, "must be a number");
                }
                double number = value.asDouble();
                if (definition.has("minimum") && number < definition.path("minimum").asDouble()) {
                    throw invalid(name, "must be at least " + definition.path("minimum").asDouble());
                }
                if (definition.has("maximum") && number > definition.path("maximum").asDouble()) {
                    throw invalid(name, "must be at most " + definition.path("maximum").asDouble());
                }
                return "integer".equals(type) ? (Object) value.asLong() : number;
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    throw invalid(name, "must be a boolean");
                }
                return value.asBoolean();
            }
            case "media" -> {
                // A media reference is an asset id, never inline bytes. Storing
                // bytes here would put customer content outside the DAM's
                // scanning and lifecycle controls.
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw invalid(name, "must be an asset identifier");
                }
                return value.asText();
            }
            case "array" -> {
                if (!value.isArray()) {
                    throw invalid(name, "must be an array");
                }
                JsonNode items = definition.path("items");
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (JsonNode element : value) {
                    list.add(items.isMissingNode() ? element.asText() : coerce(name, element, items));
                }
                return list;
            }
            case "object" -> {
                if (!value.isObject()) {
                    throw invalid(name, "must be an object");
                }
                return validate(value, definition);
            }
            default -> throw invalid(name, "uses unsupported type '" + type + "'");
        }
    }

    private static String limit(String name, String value, JsonNode definition) {
        int max = definition.path("maxLength").asInt(0);
        if (max > 0 && value.length() > max) {
            throw invalid(name, "must be at most " + max + " characters");
        }
        return value;
    }

    private static PlatformExceptions.Validation invalid(String field, String message) {
        return new PlatformExceptions.Validation(field + " " + message, Map.of("field", field));
    }

    private static Iterable<String> iterable(JsonNode array) {
        if (array == null || !array.isArray()) {
            return java.util.List.of();
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
