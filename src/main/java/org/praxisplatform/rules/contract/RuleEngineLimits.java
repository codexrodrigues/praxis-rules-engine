package org.praxisplatform.rules.contract;

import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;

/**
 * Deterministic structural limits published by the RuleSet engine contract.
 *
 * <p>Hosts may reject smaller payloads before calling the engine, but they must not assume the
 * engine accepts values above these limits. These constants are part of engine contract 1.4.</p>
 */
public final class RuleEngineLimits {
    /** Maximum reason codes accepted across one evaluation result. */
    public static final int MAX_AGGREGATED_REASON_CODES = 1_024;

    /** Maximum typed transformation proposals accepted across one evaluation result. */
    public static final int MAX_AGGREGATED_TRANSFORMATIONS = 256;

    /** Maximum UTF-8 bytes accepted by the complete public result envelope. */
    public static final int MAX_PUBLIC_RESULT_BYTES = JsonLogicLimits.DEFAULT.maxExpressionBytes();

    private RuleEngineLimits() {
    }
}
