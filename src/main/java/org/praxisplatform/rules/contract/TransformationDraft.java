package org.praxisplatform.rules.contract;

import java.net.URI;
import java.util.Objects;

/**
 * Host-executor draft that the engine validates and enriches with binding provenance.
 *
 * @param proposalKey stable lowercase proposal identity
 * @param targetPath dotted property path in an available RuleSet root
 * @param schemaRef absolute governed-schema URI with field fragment
 * @param operation requested closed write-model operation
 * @param before value observed by the executor, including explicit absence
 * @param after proposed value, including explicit absence for removal
 * @param reasonCode stable machine-readable business reason
 */
public record TransformationDraft(
        String proposalKey,
        String targetPath,
        String schemaRef,
        TransformationOperation operation,
        TransformationValue before,
        TransformationValue after,
        String reasonCode) {

    private static final String KEY_PATTERN = "[a-z][a-z0-9._-]{0,159}";
    private static final String PATH_PATTERN = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*";
    private static final String REASON_PATTERN = "[A-Z][A-Z0-9_]{0,159}";

    /** Validates stable identity, closed path syntax, and operation/value consistency. */
    public TransformationDraft {
        proposalKey = required(proposalKey, "proposalKey", 160);
        if (!proposalKey.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("proposalKey must be a stable lowercase key");
        }
        targetPath = required(targetPath, "targetPath", 255);
        if (!targetPath.matches(PATH_PATTERN)) {
            throw new IllegalArgumentException("targetPath must use the closed dotted-property syntax");
        }
        schemaRef = required(schemaRef, "schemaRef", 500);
        if (!absoluteSchemaRef(schemaRef)) {
            throw new IllegalArgumentException("schemaRef must be an absolute URI with a fragment");
        }
        operation = Objects.requireNonNull(operation, "operation is required");
        before = Objects.requireNonNull(before, "before is required");
        after = Objects.requireNonNull(after, "after is required");
        reasonCode = required(reasonCode, "reasonCode", 160);
        if (!reasonCode.matches(REASON_PATTERN)) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
        }
        if (operation == TransformationOperation.SET && !after.present()) {
            throw new IllegalArgumentException("SET transformation requires a present after value");
        }
        if (operation == TransformationOperation.REMOVE && (!before.present() || after.present())) {
            throw new IllegalArgumentException("REMOVE transformation requires present before and absent after values");
        }
        if (before.digest().equals(after.digest())) {
            throw new IllegalArgumentException("Transformation must change the target value");
        }
    }

    private static String required(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field + " is required");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maximumLength + " characters");
        }
        return normalized;
    }

    private static boolean absoluteSchemaRef(String value) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute() && uri.getFragment() != null && !uri.getFragment().isBlank();
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
