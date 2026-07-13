package org.praxisplatform.rules.contract;

import java.util.Locale;

/**
 * Runtime and dialect baseline required by an immutable RuleSet definition.
 *
 * @param engineContractVersion RuleSet contract version
 * @param jsonLogicDialectVersion normative Praxis JSON Logic dialect version
 * @param jsonLogicCorpusSha256 SHA-256 of the normative conformance corpus
 */
public record RuleRuntimeCompatibility(
        String engineContractVersion,
        String jsonLogicDialectVersion,
        String jsonLogicCorpusSha256) {

    /** Current RuleSet contract version implemented by this engine. */
    public static final String ENGINE_CONTRACT_VERSION = "1.0";
    /** Current normative JSON Logic dialect version. */
    public static final String JSON_LOGIC_DIALECT_VERSION = "1.0";
    /** SHA-256 of the packaged and Angular-owned normative corpus. */
    public static final String JSON_LOGIC_CORPUS_SHA256 =
            "1B3DAD7A5030B5CC4CFB75E8A96DFB2576FC56A0C6B878BD6A2C6F60FA377181";

    /** Validates explicit compatibility coordinates. */
    public RuleRuntimeCompatibility {
        engineContractVersion = required(engineContractVersion, "engineContractVersion");
        jsonLogicDialectVersion = required(jsonLogicDialectVersion, "jsonLogicDialectVersion");
        jsonLogicCorpusSha256 = required(jsonLogicCorpusSha256, "jsonLogicCorpusSha256")
                .toUpperCase(Locale.ROOT);
        if (!jsonLogicCorpusSha256.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException("jsonLogicCorpusSha256 must be a SHA-256 hex digest");
        }
    }

    /**
     * Returns the exact compatibility baseline implemented by this release line.
     * @return current engine, dialect, and corpus baseline
     */
    public static RuleRuntimeCompatibility current() {
        return new RuleRuntimeCompatibility(
                ENGINE_CONTRACT_VERSION,
                JSON_LOGIC_DIALECT_VERSION,
                JSON_LOGIC_CORPUS_SHA256);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
