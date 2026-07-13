package org.praxisplatform.rules.snapshot;

/** Stable snapshot compilation failure categories exposed to control planes and hosts. */
public enum RuleSnapshotIssueCode {
    /** Snapshot envelope version is unsupported by this engine. */
    SNAPSHOT_CONTRACT_INCOMPATIBLE,
    /** Consumer host contract does not exactly match the published requirement. */
    SNAPSHOT_HOST_INCOMPATIBLE,
    /** Governed source provenance is duplicate or internally inconsistent. */
    SNAPSHOT_PROVENANCE_INVALID
}
