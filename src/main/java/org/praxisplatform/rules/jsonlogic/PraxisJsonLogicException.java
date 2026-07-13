package org.praxisplatform.rules.jsonlogic;

import org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode;

/** Stable runtime failure carrying a machine-readable code and expression location. */
public final class PraxisJsonLogicException extends RuntimeException {
    /** Stable category exposed to callers and validation surfaces. */
    private final JsonLogicIssueCode code;
    /** Location of the failing expression node. */
    private final String path;
    /** Operator being processed when the failure occurred, when applicable. */
    private final String operator;

    /**
     * Creates a failure at the expression root without an operator association.
     * @param code stable issue category
     * @param message human-readable diagnostic
     */
    public PraxisJsonLogicException(JsonLogicIssueCode code, String message) {
        this(code, message, "$", null);
    }

    /**
     * Creates a failure with its precise expression location.
     * @param code stable issue category
     * @param message human-readable diagnostic
     * @param path path of the failing expression node
     * @param operator associated operator, or {@code null} when not applicable
     */
    public PraxisJsonLogicException(JsonLogicIssueCode code, String message, String path, String operator) {
        super(message);
        this.code = code;
        this.path = path;
        this.operator = operator;
    }

    /**
     * Returns the stable category for programmatic error handling.
     * @return issue code
     */
    public JsonLogicIssueCode getCode() {
        return code;
    }

    /**
     * Returns the expression location associated with the failure.
     * @return path of the failing expression node
     */
    public String getPath() { return path; }
    /**
     * Returns the operator associated with the failure.
     * @return associated operator, or {@code null} when not applicable
     */
    public String getOperator() { return operator; }
}
