package org.praxisplatform.rules.facts;

/** Sanitized structural admission failure, carrying a code and schema/expression path. */
public final class RuleFactContractException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Structural stage that rejected a candidate or provider value. */
    public enum Code {
        /** Schema is outside the closed structural profile. */
        SCHEMA_INVALID,
        /** Schema exceeded its deterministic budget. */
        SCHEMA_LIMIT_EXCEEDED,
        /** An expression references an undeclared path. */
        PATH_UNDECLARED,
        /** An operator cannot consume the declared value type. */
        EXPRESSION_TYPE_INVALID,
        /** Structural analysis exceeded its deterministic work budget. */
        ADMISSION_LIMIT_EXCEEDED,
        /** Java implementation has no compatible declared facts contract. */
        EXECUTOR_CONTRACT_INVALID,
        /** Provider value does not conform to its admitted schema. */
        FACTS_SCHEMA_INVALID,
        /** Invocation identity does not match the registered contract. */
        OPERATION_INCOMPATIBLE
    }

    /** Stable structural diagnostic category. */
    private final Code code;
    /** Coordinate excluding provider values. */
    private final String path;

    /**
     * Creates a sanitized diagnostic without embedding provider values.
     * @param code structural failure code
     * @param path schema/expression coordinate
     */
    public RuleFactContractException(Code code, String path) {
        super(code.name() + " at " + path);
        this.code = code;
        this.path = path;
    }

    /** @return stable structural diagnostic code */
    public Code code() { return code; }

    /** @return schema or expression path, excluding provider values */
    public String path() { return path; }
}
