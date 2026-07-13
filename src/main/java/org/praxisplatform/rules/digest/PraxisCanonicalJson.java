package org.praxisplatform.rules.digest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Canonical JSON serialization and SHA-256 digests shared by plans and fact snapshots. */
public final class PraxisCanonicalJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PraxisCanonicalJson() {
    }

    /**
     * Serializes a JSON value with sorted object keys and normalized decimal numbers.
     * @param node JSON value
     * @return canonical JSON representation
     */
    public static String canonicalize(JsonNode node) {
        if (node == null) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder value = new StringBuilder("{");
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) {
                    value.append(',');
                }
                String name = names.get(index);
                value.append(quote(name)).append(':').append(canonicalize(node.get(name)));
            }
            return value.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder value = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    value.append(',');
                }
                value.append(canonicalize(node.get(index)));
            }
            return value.append(']').toString();
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        return node.toString();
    }

    /**
     * Returns the uppercase SHA-256 of one JSON value's canonical representation.
     * @param node JSON value
     * @return uppercase SHA-256 hexadecimal digest
     */
    public static String sha256(JsonNode node) {
        return sha256Utf8(canonicalize(node));
    }

    /**
     * Returns the uppercase SHA-256 of a UTF-8 string.
     * @param value source string
     * @return uppercase SHA-256 hexadecimal digest
     */
    public static String sha256Utf8(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().withUpperCase().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot canonicalize field name", exception);
        }
    }
}
