package org.praxisplatform.rules.snapshot;

/** Deterministic snapshot validation or compilation failure. */
public final class RuleSnapshotException extends IllegalArgumentException {
    /** Stable machine-readable failure category. */
    private final RuleSnapshotIssueCode code;

    /**
     * Creates a failure with stable category and safe detail.
     * @param code machine-readable failure category
     * @param message safe diagnostic without snapshot payloads or credentials
     */
    public RuleSnapshotException(RuleSnapshotIssueCode code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code is required");
    }

    /**
     * Returns the stable diagnostic category.
     * @return snapshot compilation failure category
     */
    public RuleSnapshotIssueCode getCode() {
        return code;
    }
}
