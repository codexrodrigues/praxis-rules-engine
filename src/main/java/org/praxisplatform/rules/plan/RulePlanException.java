package org.praxisplatform.rules.plan;

/** Deterministic plan compilation failure with a stable machine-readable code. */
public final class RulePlanException extends RuntimeException {
    /** Stable compiler issue code. */
    private final RulePlanIssueCode code;
    /** Binding associated with the issue, when applicable. */
    private final String bindingKey;

    /**
     * Creates a plan failure.
     *
     * @param code stable failure category
     * @param message safe diagnostic
     * @param bindingKey affected binding, when applicable
     */
    public RulePlanException(RulePlanIssueCode code, String message, String bindingKey) {
        super(message);
        this.code = code;
        this.bindingKey = bindingKey;
    }

    /**
     * Returns the stable failure category.
     * @return compiler issue code
     */
    public RulePlanIssueCode getCode() {
        return code;
    }

    /**
     * Returns the affected binding identity, or {@code null}.
     * @return affected binding key or {@code null}
     */
    public String getBindingKey() {
        return bindingKey;
    }
}
