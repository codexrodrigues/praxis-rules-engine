package org.praxisplatform.rules.contract;

/** Closed operations that a domain host may apply to its typed write model. */
public enum TransformationOperation {
    /** Set or replace one schema-governed value. */
    SET,
    /** Remove one optional schema-governed value. */
    REMOVE
}
