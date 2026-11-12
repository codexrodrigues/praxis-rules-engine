package org.praxisplatform.rules.violation;

/**
 * Host-declared public description of one atomic rule error. Never include private diagnostics.
 *
 * @param code stable machine-readable code
 *
 * @param targetField optional correction path in the public request
 *
 * @param messagePtBr curated public business message
 *
 * @param lineage optional public source lineage
 *
 * @param category optional host classification, independent of an HTTP framework
 */
public record RuleErrorDescriptor(String code, String targetField, String messagePtBr,
                                  String lineage, String category) {
    /** Requires a public code/message and normalizes an absent correction path. */
    public RuleErrorDescriptor {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        if (messagePtBr == null || messagePtBr.isBlank()) throw new IllegalArgumentException("messagePtBr is required");
        targetField = targetField == null || targetField.isBlank() ? null : targetField;
    }
}
