package org.praxisplatform.rules.contract;

/** Pure executor families supported by the RuleSet runtime. */
public enum RuleExecutorType {
    /** Canonical Praxis JSON Logic expression. */
    JSON_LOGIC,
    /** Trusted, namespaced Java implementation supplied by the host. */
    JAVA
}
