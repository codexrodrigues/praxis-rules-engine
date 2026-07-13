package org.praxisplatform.rules.contract;

/** Host-enforced policy associated with inconclusive or technical outcomes. */
public enum RuleFailPolicy {
    /** Protected operations must not continue, while preserving the real outcome code. */
    FAIL_CLOSED,
    /** Return the inconclusive result to a consultative caller. */
    RETURN_INCONCLUSIVE,
    /** Permit only a separately approved and observable baseline fallback. */
    APPROVED_BASELINE_FALLBACK
}
