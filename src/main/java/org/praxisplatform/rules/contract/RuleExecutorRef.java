package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Pure executor reference carried by a binding.
 *
 * @param type executor family
 * @param implementationKey namespaced Java implementation key, for Java executors
 * @param implementationVersion exact executor or dialect version
 * @param expression canonical JSON Logic expression for declarative executors; JSON {@code null}
 *     is normalized to absence for Java executors
 */
public record RuleExecutorRef(
        RuleExecutorType type,
        String implementationKey,
        String implementationVersion,
        JsonNode expression) {

    /** Enforces exactly one executor representation and snapshots mutable JSON input. */
    public RuleExecutorRef {
        type = Objects.requireNonNull(type, "type is required");
        if (implementationVersion == null || implementationVersion.isBlank()) {
            throw new IllegalArgumentException("implementationVersion must not be blank");
        }
        implementationVersion = implementationVersion.trim();
        if (type == RuleExecutorType.JSON_LOGIC) {
            if (expression == null || expression.isNull()) {
                throw new IllegalArgumentException("JSON_LOGIC executor requires an expression");
            }
            if (implementationKey != null) {
                throw new IllegalArgumentException("JSON_LOGIC executor cannot declare implementationKey");
            }
            expression = expression.deepCopy();
        } else {
            if (implementationKey == null || implementationKey.isBlank() || !implementationKey.contains(":")) {
                throw new IllegalArgumentException("JAVA executor requires a namespaced implementationKey");
            }
            if (expression != null && !expression.isNull()) {
                throw new IllegalArgumentException("JAVA executor cannot declare an expression");
            }
            implementationKey = implementationKey.trim();
            expression = null;
        }
    }

    /**
     * Returns a defensive copy of the declarative expression.
     * @return copied expression or {@code null}
     */
    @Override
    public JsonNode expression() {
        return expression == null ? null : expression.deepCopy();
    }

    /**
     * Creates a declarative executor reference.
     * @param expression canonical JSON Logic expression
     * @return immutable executor reference
     */
    public static RuleExecutorRef jsonLogic(JsonNode expression) {
        return new RuleExecutorRef(
                RuleExecutorType.JSON_LOGIC,
                null,
                RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
                expression);
    }

    /**
     * Creates a trusted Java executor reference.
     * @param implementationKey namespaced registry key
     * @param implementationVersion exact implementation version
     * @return immutable executor reference
     */
    public static RuleExecutorRef java(String implementationKey, String implementationVersion) {
        return new RuleExecutorRef(
                RuleExecutorType.JAVA,
                implementationKey,
                implementationVersion,
                null);
    }
}
