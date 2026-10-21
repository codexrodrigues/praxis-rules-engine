package org.praxisplatform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import org.praxisplatform.rules.contract.RuleEngineLimits;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;

/** Native JSON admission and capped canonical byte counting at the RuleSet boundary.
 * One instance tracks output transfer only; facts validation has an independent budget. */
public final class RuleJsonBudget {
    private int transferredBytes;

    RuleJsonBudget() {
    }

    /**
     * Admits bounded native finite JSON without invoking serializers or expanding numbers.
     * @param value JSON value crossing a pure engine contract boundary
     * @throws IllegalArgumentException for unsupported JSON or structural/byte overflow
     */
    public static void validateValue(JsonNode value) {
        validateFacts(value);
    }

    static void validateFacts(JsonNode facts) {
        requireJsonValue(facts);
        new Counter(RuleEngineLimits.MAX_PUBLIC_RESULT_BYTES).value(facts);
    }

    void reserve(Map<String, JsonNode> outputs) {
        if (outputs.isEmpty()) {
            return; // The contract charges zero, rather than two bytes, for an empty context.
        }
        // Shape admission has fixed precedence over size, regardless of object/map insertion
        // order. This traverses already structurally bounded outputs without copying them.
        outputs.values().forEach(RuleJsonBudget::requireJsonValue);
        Counter counter = new Counter(RuleEngineLimits.MAX_PUBLIC_RESULT_BYTES - transferredBytes);
        counter.add(2); // braces
        boolean first = true;
        for (var entry : outputs.entrySet()) {
            if (!first) {
                counter.add(1);
            }
            first = false;
            counter.string(entry.getKey());
            counter.add(1); // colon
            counter.value(entry.getValue());
        }
        // Subtraction in Counter.add prevents overflow before any cumulative addition.
        transferredBytes += counter.bytes;
    }

    static void requireJsonValue(JsonNode node) {
        requireJsonValue(node, 0, new int[1]);
    }

    private static void requireJsonValue(JsonNode node, int depth, int[] nodes) {
        // Admission precedes Jackson serialization. Bound traversal too, so invalid host
        // shapes cannot exhaust the stack or expand before the existing structural guard.
        if (depth > JsonLogicLimits.DEFAULT.maxDepth() || ++nodes[0] > JsonLogicLimits.DEFAULT.maxNodes()) {
            throw new LimitExceeded();
        }
        if (node == null || node.isMissingNode() || node.isBinary() || node.isPojo()) {
            throw new InvalidValue();
        }
        if (node.isObject() || node.isArray()) {
            if (node.isArray() && node.size() > JsonLogicLimits.DEFAULT.maxArrayItems()) {
                throw new LimitExceeded();
            }
            node.forEach(child -> requireJsonValue(child, depth + 1, nodes));
        } else if (node.isTextual() && node.textValue().length() > JsonLogicLimits.DEFAULT.maxStringLength()) {
            throw new LimitExceeded();
        } else if (node.isNumber()) {
            finiteNumber(node);
        } else if (!node.isNull() && !node.isTextual() && !node.isBoolean()) {
            throw new InvalidValue();
        }
    }

    private static BigDecimal finiteNumber(JsonNode node) {
        try {
            return node.decimalValue();
        } catch (RuntimeException exception) {
            throw new InvalidValue();
        }
    }

    static final class InvalidValue extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InvalidValue() {
            super("RuleSet values must contain only native JSON values and finite numbers");
        }
    }

    static final class LimitExceeded extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Counter {
        private final int maximum;
        private int bytes;

        Counter(int maximum) {
            this.maximum = maximum;
        }

        void add(long amount) {
            if (amount > maximum - bytes) {
                throw new LimitExceeded();
            }
            bytes += (int) amount;
        }

        void value(JsonNode node) {
            if (node == null || node.isNull()) {
                add(4);
            } else if (node.isObject()) {
                add(2);
                boolean first = true;
                // Sorting changes canonical order, never length; do not allocate a key list.
                for (var entry : node.properties()) {
                    if (!first) {
                        add(1);
                    }
                    first = false;
                    string(entry.getKey());
                    add(1);
                    value(entry.getValue());
                }
            } else if (node.isArray()) {
                add(2);
                for (int index = 0; index < node.size(); index++) {
                    if (index > 0) {
                        add(1);
                    }
                    value(node.get(index));
                }
            } else if (node.isNumber()) {
                // Same normalization as PraxisCanonicalJson, without expanding extreme scales.
                BigDecimal number = finiteNumber(node);
                if (number.signum() == 0) {
                    add(1);
                    return;
                }
                // For non-positive scales, stripping may underflow int scale, while
                // precision - scale already gives the exact canonical plain length.
                if (number.scale() > 0) {
                    number = number.stripTrailingZeros();
                }
                long precision = number.precision();
                long scale = number.scale();
                long digits = scale <= 0 ? precision - scale : Math.max(precision, scale + 1) + 1;
                add(digits + (number.signum() < 0 ? 1 : 0));
            } else if (node.isTextual()) {
                string(node.textValue());
            } else if (node.isBoolean()) {
                add(node.booleanValue() ? 4 : 5);
            } else {
                // Non-JSON Jackson node types are outside the transport contract.
                throw new InvalidValue();
            }
        }

        void string(String text) {
            add(2); // quotes
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (value == '"' || value == '\\' || value == '\b' || value == '\f'
                        || value == '\n' || value == '\r' || value == '\t') {
                    add(2);
                } else if (value < 0x20) {
                    add(6);
                } else if (value < 0x80) {
                    add(1);
                } else if (value < 0x800) {
                    add(2);
                } else if (Character.isHighSurrogate(value) && index + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(index + 1))) {
                    add(4);
                    index++;
                } else if (Character.isSurrogate(value)) {
                    // String.getBytes(UTF_8), used by canonical JSON, replaces unpaired UTF-16
                    // surrogates with one ASCII question mark.
                    add(1);
                } else {
                    add(3);
                }
            }
        }
    }
}
