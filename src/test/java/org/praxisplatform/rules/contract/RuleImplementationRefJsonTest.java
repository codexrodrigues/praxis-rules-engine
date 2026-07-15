package org.praxisplatform.rules.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RuleImplementationRefJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void attestedImplementationRoundTripsWithCanonicalHashes() throws Exception {
        RuleImplementationRef original = new RuleImplementationRef(
                "customer:benefit-eligibility",
                "1.0.0",
                new RuleExtensionTrust(
                        "a".repeat(64),
                        "sigstore:tenant-a-release",
                        "policy:customer-extension-v1",
                        "b".repeat(64)));

        RuleImplementationRef restored = JSON.readValue(
                JSON.writeValueAsString(original), RuleImplementationRef.class);

        assertEquals(original, restored);
        assertEquals("A".repeat(64), restored.extensionTrust().artifactSha256());
        assertEquals("B".repeat(64), restored.extensionTrust().verificationEvidenceSha256());
    }

    @Test
    void previousBuiltInShapeRemainsReadableWithoutTrustEvidence() throws Exception {
        RuleImplementationRef restored = JSON.readValue("""
                {
                  "implementationKey": "benefits:amount",
                  "implementationVersion": "1.0.0"
                }
                """, RuleImplementationRef.class);

        assertNull(restored.extensionTrust());
    }
}
