package org.praxisplatform.rules.contract;

/** Runtime-neutral value kinds supported by typed transformation proposals. */
public enum TransformationValueType {
    /** UTF-8 text value. */
    STRING,
    /** Integral numeric value. */
    INTEGER,
    /** Integral or decimal numeric value. */
    NUMBER,
    /** Boolean value. */
    BOOLEAN,
    /** ISO-8601 local date text. */
    DATE,
    /** ISO-8601 date-time text with an explicit offset. */
    DATE_TIME,
    /** Canonical UUID text. */
    UUID,
    /** JSON object value. */
    OBJECT,
    /** JSON array value. */
    ARRAY,
    /** Explicit JSON null, distinct from absence. */
    NULL
}
