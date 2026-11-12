package org.praxisplatform.rules.facts;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host-owned executable fact contract for one operation and provider revision.
 * @param hostContractVersion installed host contract coordinate
 * @param operationKey exact registered server invocation key
 * @param providerRef exact registered provider and revision identity
 * @param schema compiled structural contract
 * @param javaExecutors declared Java input contracts, including explicit empty declarations
 */
public record RuleFactContract(String hostContractVersion, String operationKey, String providerRef,
        RuleFactSchema schema, List<JavaInputs> javaExecutors) {
    /** Copies trusted declarations and rejects ambiguous implementation identities. */
    public RuleFactContract {
        hostContractVersion = required(hostContractVersion);
        operationKey = required(operationKey);
        providerRef = required(providerRef);
        schema = Objects.requireNonNull(schema, "schema");
        javaExecutors = List.copyOf(javaExecutors);
        if (javaExecutors.stream().map(x -> x.implementationKey() + "\n" + x.implementationVersion()).distinct().count()
                != javaExecutors.size()) throw new IllegalArgumentException("Duplicate executor input contract");
    }

    /**
     * Explicit pure Java input requirements supplied by the executable provider owner.
     * @param implementationKey namespaced implementation key
     * @param implementationVersion exact implementation version
     * @param paths fact paths and accepted structural types, including null when nullable
     */
    public record JavaInputs(String implementationKey, String implementationVersion, Map<String, Set<String>> paths) {
        /** Defensively copies nested type sets. */
        public JavaInputs {
            implementationKey = required(implementationKey);
            implementationVersion = required(implementationVersion);
            if (!implementationKey.contains(":")) throw new IllegalArgumentException("Namespaced implementation key required");
            var copy = new java.util.LinkedHashMap<String, Set<String>>();
            paths.forEach((key, types) -> {
                required(key);
                if (types.isEmpty() || !Set.of("null", "object", "array", "number", "integer", "string", "boolean").containsAll(types))
                    throw new IllegalArgumentException("Explicit structural types required");
                copy.put(key, Set.copyOf(types));
            });
            paths = Map.copyOf(copy);
        }
    }

    /**
     * Checks server-resolved invocation coordinates before capturing or evaluating facts.
     * @param hostVersion actual host coordinate
     * @param operation actual registered operation
     * @param provider actual registered provider revision
     */
    public void requireInvocation(String hostVersion, String operation, String provider) {
        if (!hostContractVersion.equals(hostVersion) || !operationKey.equals(operation) || !providerRef.equals(provider))
            throw new RuleFactContractException(RuleFactContractException.Code.OPERATION_INCOMPATIBLE, "$invocation");
    }

    /**
     * Validates a canonically compiled effective plan and all declared product templates.
     * Both control-plane planning and host admission use this pure analysis.
     * @param plan canonical plan compiled with the trusted coordinate registry
     */
    public void validate(org.praxisplatform.rules.plan.RuleDecisionPlan plan) {
        for (var binding : plan.orderedBindings()) validate(binding.executor(), binding.requiredFactPaths());
        for (var slot : plan.definition().slots()) {
            if (slot.parameterTemplate() != null) validate(slot.parameterTemplate().executor(), slot.parameterTemplate().requiredFactPaths());
        }
    }

    private void validate(org.praxisplatform.rules.contract.RuleExecutorRef executor, List<String> required) {
        required.forEach(schema::requirePath);
        if (executor.type() == org.praxisplatform.rules.contract.RuleExecutorType.JSON_LOGIC) {
            schema.validateExpression(executor.expression());
            return;
        }
        var inputs = javaExecutors.stream().filter(x -> x.implementationKey().equals(executor.implementationKey())
                && x.implementationVersion().equals(executor.implementationVersion())).findFirst().orElse(null);
        if (inputs == null || !required.containsAll(inputs.paths().keySet())) throw executorFailure();
        inputs.paths().forEach((path, types) -> {
            String declared = schema.typeAt(path);
            if (!types.contains(declared) && !(declared.equals("integer") && types.contains("number"))) throw executorFailure();
            if (schema.nullableAt(path) && !types.contains("null")) throw executorFailure();
        });
    }

    private static RuleFactContractException executorFailure() {
        return new RuleFactContractException(RuleFactContractException.Code.EXECUTOR_CONTRACT_INVALID, "$executor");
    }

    /** @return canonical identity including schema and ordered Java input declarations */
    public String digest() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode().put("hostContractVersion", hostContractVersion)
                .put("operationKey", operationKey).put("providerRef", providerRef).put("schemaDigest", schema.digest());
        var executors = root.putArray("javaExecutors");
        javaExecutors.stream().sorted(java.util.Comparator.comparing(JavaInputs::implementationKey)
                .thenComparing(JavaInputs::implementationVersion)).forEach(inputs -> {
            var entry = executors.addObject().put("key", inputs.implementationKey()).put("version", inputs.implementationVersion());
            var paths = entry.putObject("paths");
            inputs.paths().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(path -> path.getValue().stream().sorted().forEach(paths.putArray(path.getKey())::add));
        });
        return org.praxisplatform.rules.digest.PraxisCanonicalJson.sha256(root);
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Exact non-blank coordinate required");
        return value;
    }
}
