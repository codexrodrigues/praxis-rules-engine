package org.praxisplatform.rules.jsonlogic.model;

/**
 * Deterministic resource limits applied to expressions, intermediate collections and public results.
 *
 * @param maxDepth maximum nested JSON depth
 * @param maxNodes maximum number of visited JSON nodes
 * @param maxExpressionBytes maximum UTF-8 size of an expression or public result
 * @param maxArrayItems maximum number of items in an array
 * @param maxStringLength maximum number of UTF-16 characters in a string
 * @param maxOperations maximum number of evaluated operator nodes
 * @param maxRegexLength maximum regex source length
 * @param maxRegexComplexity maximum number of bounded quantifier markers
 */
public record JsonLogicLimits(
    int maxDepth,
    int maxNodes,
    int maxExpressionBytes,
    int maxArrayItems,
    int maxStringLength,
    int maxOperations,
    int maxRegexLength,
    int maxRegexComplexity
) {
    public static final JsonLogicLimits DEFAULT = new JsonLogicLimits(64, 10_000, 256_000, 10_000, 64_000, 50_000, 512, 64);

    /** Rejects non-positive limits before untrusted expressions reach the runtime. */
    public JsonLogicLimits {
        if (maxDepth < 1 || maxNodes < 1 || maxExpressionBytes < 1 || maxArrayItems < 1
            || maxStringLength < 1 || maxOperations < 1 || maxRegexLength < 1 || maxRegexComplexity < 1) {
            throw new IllegalArgumentException("JSON Logic limits must be positive.");
        }
    }
}
