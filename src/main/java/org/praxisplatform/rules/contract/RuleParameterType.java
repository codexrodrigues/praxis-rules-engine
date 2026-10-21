package org.praxisplatform.rules.contract;

/** Closed JSON scalar parameter types; numbers are compared by value. */
public enum RuleParameterType {
    /** JSON string. */
    STRING,
    /** Finite JSON number. */
    NUMBER,
    /** Finite JSON number with an integral mathematical value. */
    INTEGER,
    /** JSON boolean. */
    BOOLEAN
}
