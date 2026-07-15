package org.praxisplatform.rules.contract;

import java.util.Locale;

/**
 * Immutable evidence that a Java rule artifact was verified by a trusted host pipeline.
 *
 * <p>The engine does not load artifacts or verify signatures. The host or build pipeline performs
 * that I/O-bound verification and supplies this safe attestation when constructing its registry.</p>
 *
 * @param artifactSha256 SHA-256 of the exact executable artifact
 * @param signatureIdentity namespaced identity of the verified signer
 * @param trustPolicyKey namespaced, versioned policy used to admit the signer and artifact
 * @param verificationEvidenceSha256 SHA-256 of the immutable verification evidence or bundle
 */
public record RuleExtensionTrust(
        String artifactSha256,
        String signatureIdentity,
        String trustPolicyKey,
        String verificationEvidenceSha256) {

    /** Normalizes hashes and rejects ambiguous trust evidence. */
    public RuleExtensionTrust {
        artifactSha256 = sha256(artifactSha256, "artifactSha256");
        signatureIdentity = namespaced(signatureIdentity, "signatureIdentity");
        trustPolicyKey = namespaced(trustPolicyKey, "trustPolicyKey");
        verificationEvidenceSha256 = sha256(
                verificationEvidenceSha256, "verificationEvidenceSha256");
    }

    private static String sha256(String value, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return normalized;
    }

    private static String namespaced(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.contains(":")) {
            throw new IllegalArgumentException(field + " must be namespaced");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
