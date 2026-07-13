package org.praxisplatform.rules.contract;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Safe approval evidence retained by a published snapshot.
 *
 * @param approvalKey stable approval identity
 * @param role governed approval role
 * @param actorRef safe actor reference, never a credential or raw principal payload
 * @param decidedAtUtc approval instant in UTC
 * @param evidenceHash SHA-256 of the approved source evidence
 */
public record RuleSnapshotApproval(
        String approvalKey,
        String role,
        String actorRef,
        String decidedAtUtc,
        String evidenceHash) {

    /** Validates safe, deterministic approval evidence. */
    public RuleSnapshotApproval {
        approvalKey = required(approvalKey, "approvalKey");
        role = required(role, "role");
        actorRef = required(actorRef, "actorRef");
        decidedAtUtc = utc(decidedAtUtc, "decidedAtUtc");
        evidenceHash = required(evidenceHash, "evidenceHash").toUpperCase(Locale.ROOT);
        if (!evidenceHash.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException("evidenceHash must be a SHA-256 hex digest");
        }
    }

    private static String utc(String value, String field) {
        String normalized = required(value, field);
        Instant instant = Instant.parse(normalized);
        if (!normalized.endsWith("Z")) {
            throw new IllegalArgumentException(field + " must use UTC");
        }
        return instant.toString();
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
