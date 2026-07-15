package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/**
 * Explicit present/null/absent value used by a typed before/after proposal.
 *
 * @param present whether the target value exists in the write model
 * @param type declared runtime-neutral type, required when present
 * @param value JSON representation, required when present
 */
public record TransformationValue(
        boolean present,
        TransformationValueType type,
        JsonNode value) {

    /** Validates the declared kind and defensively copies JSON values. */
    public TransformationValue {
        if (!present) {
            if (type != null || value != null) {
                throw new IllegalArgumentException("Absent transformation value cannot declare type or value");
            }
        } else {
            Objects.requireNonNull(type, "type is required for a present transformation value");
            Objects.requireNonNull(value, "value is required for a present transformation value");
            validateType(type, value);
            value = value.deepCopy();
        }
    }

    /**
     * Returns a defensive copy of the typed value.
     * @return copied value, or {@code null} when absent
     */
    @Override
    public JsonNode value() {
        return value == null ? null : value.deepCopy();
    }

    /**
     * Creates the explicit absent value, distinct from JSON null.
     * @return absent transformation value
     */
    public static TransformationValue absent() {
        return new TransformationValue(false, null, null);
    }

    /**
     * Creates a present typed value.
     * @param type declared value type
     * @param value JSON value matching the declared type
     * @return validated present transformation value
     */
    public static TransformationValue of(TransformationValueType type, JsonNode value) {
        return new TransformationValue(true, type, value);
    }

    /**
     * Returns a stable redacted digest of presence, declared type, and value.
     * @return uppercase SHA-256 digest
     */
    public String digest() {
        var envelope = JsonNodeFactory.instance.objectNode().put("present", present);
        if (present) {
            envelope.put("type", type.name());
            envelope.set("value", value);
        }
        return PraxisCanonicalJson.sha256(envelope);
    }

    private static void validateType(TransformationValueType type, JsonNode value) {
        boolean valid = switch (type) {
            case STRING -> value.isTextual();
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case DATE -> validDate(value);
            case DATE_TIME -> validDateTime(value);
            case UUID -> validUuid(value);
            case OBJECT -> value.isObject();
            case ARRAY -> value.isArray();
            case NULL -> value.isNull();
        };
        if (!valid) {
            throw new IllegalArgumentException("Transformation value does not match declared type " + type);
        }
    }

    private static boolean validDate(JsonNode value) {
        try {
            LocalDate.parse(value.textValue());
            return value.isTextual();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean validDateTime(JsonNode value) {
        try {
            OffsetDateTime.parse(value.textValue());
            return value.isTextual();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean validUuid(JsonNode value) {
        try {
            java.util.UUID.fromString(value.textValue());
            return value.isTextual();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
