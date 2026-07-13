package org.praxisplatform.rules.contract;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable runtime-neutral envelope published by the Praxis rule control plane.
 *
 * @param snapshotContractVersion snapshot envelope contract version
 * @param snapshotKey immutable snapshot identity
 * @param tenantId governed tenant scope
 * @param environment governed environment scope
 * @param ownerServiceKey domain host expected to consume this snapshot
 * @param publicationRevision monotonic revision for this scoped RuleSet head
 * @param publishedAtUtc publication instant in UTC
 * @param supersedesSnapshotKey prior published snapshot, or {@code null} for the first revision
 * @param requiredHostContractVersion exact host contract required to activate the snapshot
 * @param validFromUtc first valid evaluation instant in UTC
 * @param validUntilUtc optional exclusive validity limit in UTC
 * @param sources governed definition provenance
 * @param approvals safe approval evidence
 * @param ruleSet immutable executable RuleSet content
 */
public record PublishedRuleSnapshot(
        String snapshotContractVersion,
        String snapshotKey,
        String tenantId,
        String environment,
        String ownerServiceKey,
        int publicationRevision,
        String publishedAtUtc,
        String supersedesSnapshotKey,
        String requiredHostContractVersion,
        String validFromUtc,
        String validUntilUtc,
        List<RuleSnapshotSource> sources,
        List<RuleSnapshotApproval> approvals,
        RuleSetDefinition ruleSet) {

    /** Current immutable snapshot envelope contract. */
    public static final String SNAPSHOT_CONTRACT_VERSION = "1.0";

    /** Copies collections and rejects incomplete or internally inconsistent envelopes. */
    public PublishedRuleSnapshot {
        snapshotContractVersion = required(snapshotContractVersion, "snapshotContractVersion");
        snapshotKey = required(snapshotKey, "snapshotKey");
        tenantId = required(tenantId, "tenantId");
        environment = required(environment, "environment");
        ownerServiceKey = required(ownerServiceKey, "ownerServiceKey");
        if (publicationRevision < 1) {
            throw new IllegalArgumentException("publicationRevision must be positive");
        }
        publishedAtUtc = utc(publishedAtUtc, "publishedAtUtc");
        supersedesSnapshotKey = optional(supersedesSnapshotKey);
        if (snapshotKey.equals(supersedesSnapshotKey)) {
            throw new IllegalArgumentException("snapshot cannot supersede itself");
        }
        requiredHostContractVersion = required(requiredHostContractVersion, "requiredHostContractVersion");
        validFromUtc = utc(validFromUtc, "validFromUtc");
        validUntilUtc = validUntilUtc == null ? null : utc(validUntilUtc, "validUntilUtc");
        if (validUntilUtc != null && !Instant.parse(validUntilUtc).isAfter(Instant.parse(validFromUtc))) {
            throw new IllegalArgumentException("validUntilUtc must be after validFromUtc");
        }
        sources = sortedDistinctSources(sources);
        approvals = sortedDistinctApprovals(approvals);
        ruleSet = Objects.requireNonNull(ruleSet, "ruleSet is required");
    }

    private static List<RuleSnapshotSource> sortedDistinctSources(List<RuleSnapshotSource> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        List<RuleSnapshotSource> normalized = values.stream()
                .map(value -> Objects.requireNonNull(value, "sources entry is required"))
                .sorted(Comparator.comparing(RuleSnapshotSource::definitionKey)
                        .thenComparingInt(RuleSnapshotSource::version)
                        .thenComparing(RuleSnapshotSource::definitionId))
                .toList();
        if (new HashSet<>(normalized.stream().map(RuleSnapshotSource::definitionId).toList()).size()
                != normalized.size()) {
            throw new IllegalArgumentException("sources must not contain duplicate definitionId values");
        }
        return List.copyOf(normalized);
    }

    private static List<RuleSnapshotApproval> sortedDistinctApprovals(List<RuleSnapshotApproval> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("approvals must not be empty");
        }
        List<RuleSnapshotApproval> normalized = values.stream()
                .map(value -> Objects.requireNonNull(value, "approvals entry is required"))
                .sorted(Comparator.comparing(RuleSnapshotApproval::approvalKey))
                .toList();
        if (new HashSet<>(normalized.stream().map(RuleSnapshotApproval::approvalKey).toList()).size()
                != normalized.size()) {
            throw new IllegalArgumentException("approvals must not contain duplicate approvalKey values");
        }
        return List.copyOf(normalized);
    }

    private static String utc(String value, String field) {
        String normalized = required(value, field);
        Instant instant = Instant.parse(normalized);
        if (!normalized.endsWith("Z")) {
            throw new IllegalArgumentException(field + " must use UTC");
        }
        return instant.toString();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
