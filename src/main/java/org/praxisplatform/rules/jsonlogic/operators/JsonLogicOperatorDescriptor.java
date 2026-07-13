package org.praxisplatform.rules.jsonlogic.operators;

/**
 * Stable, serializable metadata for an operator available to the runtime.
 *
 * @param operator canonical persisted operator name
 * @param namespace logical namespace, or {@code null} for native operators
 * @param minArgs minimum accepted argument count
 * @param maxArgs maximum accepted argument count, or {@code null} when unbounded
 * @param returnType documented result family
 * @param purity {@code pure} or {@code contextual}
 * @param deprecated whether new authoring should avoid the operator
 * @param source {@code native}, {@code praxis} or {@code host}
 * @param since dialect version that introduced the operator
 */
public record JsonLogicOperatorDescriptor(
    String operator,
    String namespace,
    int minArgs,
    Integer maxArgs,
    String returnType,
    String purity,
    boolean deprecated,
    String source,
    String since
) { }
