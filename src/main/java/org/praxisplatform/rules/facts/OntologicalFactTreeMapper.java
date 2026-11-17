package org.praxisplatform.rules.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder.DateSerializationMode;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;

/**
 * Stateless, thread-safe mapper that materializes the 5 canonical Praxis ontological fact roots
 * ({@code draft}, {@code current}, {@code <concept>}, {@code neighborhood}, {@code context}) into
 * a detached, {@link JsonLogicLimits}-validated {@link ObjectNode}.
 *
 * <p>Java beans/records supplied for {@code draft}, {@code current}, or 1-hop {@code <concept>}
 * nodes are projected via {@link PraxisFactTreeBuilder#toScalarObjectNode(Object, DateSerializationMode)}
 * so JPA persistence associations are never traversed or lazy-loaded. {@link Map} and
 * {@link ObjectNode} inputs are converted natively while honoring the configured
 * {@link DateSerializationMode}.</p>
 */
public final class OntologicalFactTreeMapper {
    private static final Set<String> RESERVED_NON_CONCEPT_ROOTS =
            Set.of(
                    PraxisFactTreeBuilder.ROOT_DRAFT,
                    PraxisFactTreeBuilder.ROOT_CURRENT,
                    PraxisFactTreeBuilder.ROOT_NEIGHBORHOOD,
                    PraxisFactTreeBuilder.ROOT_CONTEXT,
                    "facts");
    private static final PraxisJsonLogicEngine VALIDATOR = new PraxisJsonLogicEngine();

    private final DateSerializationMode defaultDateMode;
    private final JsonLogicLimits limits;

    /** Creates a stateless mapper using {@link DateSerializationMode#ISO_8601} and {@link JsonLogicLimits#DEFAULT}. */
    public OntologicalFactTreeMapper() {
        this(DateSerializationMode.ISO_8601, JsonLogicLimits.DEFAULT);
    }

    /**
     * Creates a stateless mapper with an explicit {@link DateSerializationMode} and {@link JsonLogicLimits#DEFAULT}.
     *
     * @param defaultDateMode civil date serialization mode
     */
    public OntologicalFactTreeMapper(DateSerializationMode defaultDateMode) {
        this(defaultDateMode, JsonLogicLimits.DEFAULT);
    }

    /**
     * Creates a stateless mapper with explicit {@link DateSerializationMode} and {@link JsonLogicLimits}.
     *
     * @param defaultDateMode civil date serialization mode
     * @param limits deterministic structural limits applied to the built tree
     */
    public OntologicalFactTreeMapper(
            DateSerializationMode defaultDateMode, JsonLogicLimits limits) {
        this.defaultDateMode = Objects.requireNonNull(defaultDateMode, "defaultDateMode");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Creates a mapper configured with {@link DateSerializationMode#ISO_8601}.
     *
     * @return stateless ISO-8601 mapper
     */
    public static OntologicalFactTreeMapper iso8601() {
        return new OntologicalFactTreeMapper(DateSerializationMode.ISO_8601);
    }

    /**
     * Creates a mapper configured with {@link DateSerializationMode#EPOCH_DAY}.
     *
     * @return stateless epoch-day mapper
     */
    public static OntologicalFactTreeMapper epochDay() {
        return new OntologicalFactTreeMapper(DateSerializationMode.EPOCH_DAY);
    }

    /**
     * Creates a mapper configured with {@link DateSerializationMode#ISO_AND_EPOCH_DAY}.
     *
     * @return stateless dual date mapper
     */
    public static OntologicalFactTreeMapper isoAndEpochDay() {
        return new OntologicalFactTreeMapper(DateSerializationMode.ISO_AND_EPOCH_DAY);
    }

    /**
     * 1-hop domain concept payload paired with optional draft-FK presence and fallback defaults.
     *
     * @param entity preloaded concept entity, DTO, record, or map (may be {@code null})
     * @param idPresentInDraft optional flag indicating whether the draft carried a non-null foreign key;
     *     when non-null, emits {@code idPresentInDraft} on the concept node
     * @param nullDefaults default field values emitted alongside {@code "present": false} when {@code entity == null}
     */
    public record ConceptBinding(
            Object entity, Boolean idPresentInDraft, Map<String, Object> nullDefaults) {

        /** Defensively copies {@code nullDefaults} while preserving explicit {@code null} default values. */
        public ConceptBinding {
            nullDefaults = copyNullableValueMap(nullDefaults);
        }

        /**
         * Creates a concept binding that explicitly emits {@code idPresentInDraft}.
         *
         * @param entity preloaded concept entity or map (nullable)
         * @param idPresentInDraft whether the draft specified the concept FK
         * @param nullDefaults fallback defaults when {@code entity == null}
         */
        public ConceptBinding(
                Object entity, boolean idPresentInDraft, Map<String, Object> nullDefaults) {
            this(entity, Boolean.valueOf(idPresentInDraft), nullDefaults);
        }

        /**
         * Creates a concept binding without an explicit {@code idPresentInDraft} attribute.
         *
         * @param entity preloaded concept entity or map (nullable)
         * @param nullDefaults fallback defaults when {@code entity == null}
         */
        public ConceptBinding(Object entity, Map<String, Object> nullDefaults) {
            this(entity, null, nullDefaults);
        }

        /**
         * Creates a concept binding from a resolved entity or map with empty fallback defaults.
         *
         * @param entity preloaded concept entity or map (nullable)
         * @return immutable concept binding
         */
        public static ConceptBinding of(Object entity) {
            return new ConceptBinding(entity, null, Map.of());
        }

        /**
         * Creates a concept binding with fallback defaults when {@code entity == null}.
         *
         * @param entity preloaded concept entity or map (nullable)
         * @param nullDefaults fallback defaults when {@code entity == null}
         * @return immutable concept binding
         */
        public static ConceptBinding of(Object entity, Map<String, Object> nullDefaults) {
            return new ConceptBinding(entity, null, nullDefaults);
        }

        /**
         * Creates a concept binding tracking {@code idPresentInDraft} and fallback defaults.
         *
         * @param entity preloaded concept entity or map (nullable)
         * @param idPresentInDraft whether the draft specified the concept FK
         * @param nullDefaults fallback defaults when {@code entity == null}
         * @return immutable concept binding
         */
        public static ConceptBinding of(
                Object entity, boolean idPresentInDraft, Map<String, Object> nullDefaults) {
            return new ConceptBinding(entity, Boolean.valueOf(idPresentInDraft), nullDefaults);
        }

        /**
         * Creates an absent concept binding ({@code present: false}) populated with {@code nullDefaults}.
         *
         * @param nullDefaults fallback defaults
         * @return absent concept binding
         */
        public static ConceptBinding absent(Map<String, Object> nullDefaults) {
            return new ConceptBinding(null, null, nullDefaults);
        }

        /**
         * Creates an absent concept binding ({@code present: false}) tracking {@code idPresentInDraft}.
         *
         * @param idPresentInDraft whether the draft specified the concept FK
         * @param nullDefaults fallback defaults
         * @return absent concept binding
         */
        public static ConceptBinding absent(
                boolean idPresentInDraft, Map<String, Object> nullDefaults) {
            return new ConceptBinding(null, Boolean.valueOf(idPresentInDraft), nullDefaults);
        }
    }

    /**
     * Returns the configured {@link DateSerializationMode}.
     *
     * @return default date serialization mode
     */
    public DateSerializationMode defaultDateMode() {
        return defaultDateMode;
    }

    /**
     * Returns the configured {@link JsonLogicLimits}.
     *
     * @return structural limits enforced on built fact trees
     */
    public JsonLogicLimits limits() {
        return limits;
    }

    /**
     * Starts a new fluent mapping session using this mapper's {@link DateSerializationMode} and
     * {@link JsonLogicLimits}.
     *
     * @return fluent ontological builder
     */
    public Builder newSession() {
        return builder(defaultDateMode, limits);
    }

    /**
     * Maps the 5 canonical ontological roots using this mapper's configured {@link DateSerializationMode}
     * and {@link JsonLogicLimits}.
     *
     * @param draftEntity candidate state ({@code draft}), or {@code null} (for example on delete)
     * @param currentEntity prior persisted state ({@code current}), or {@code null} on create
     * @param concepts 1-hop domain concept bindings keyed by concept root name (nullable)
     * @param neighborhoodRecord N-hop sibling/aggregate DTO, record, or map (nullable; omitted if {@code null})
     * @param contextMap execution context properties (nullable; omitted if {@code null})
     * @return detached, {@link JsonLogicLimits}-validated {@link ObjectNode}
     */
    public ObjectNode mapFiveRoots(
            Object draftEntity,
            Object currentEntity,
            Map<String, ConceptBinding> concepts,
            Object neighborhoodRecord,
            Map<String, Object> contextMap) {
        return buildFiveRoots(
                draftEntity,
                currentEntity,
                concepts,
                neighborhoodRecord,
                contextMap,
                defaultDateMode,
                limits);
    }

    /**
     * Starts a fluent mapping builder using {@link DateSerializationMode#ISO_8601} and {@link JsonLogicLimits#DEFAULT}.
     *
     * @return fluent ontological builder
     */
    public static Builder builder() {
        return new Builder(DateSerializationMode.ISO_8601, JsonLogicLimits.DEFAULT);
    }

    /**
     * Starts a fluent mapping builder with an explicit {@link DateSerializationMode}.
     *
     * @param dateMode civil date serialization mode
     * @return fluent ontological builder
     */
    public static Builder builder(DateSerializationMode dateMode) {
        return new Builder(dateMode, JsonLogicLimits.DEFAULT);
    }

    /**
     * Starts a fluent mapping builder with explicit {@link DateSerializationMode} and {@link JsonLogicLimits}.
     *
     * @param dateMode civil date serialization mode
     * @param limits structural limits enforced on {@link Builder#build()}
     * @return fluent ontological builder
     */
    public static Builder builder(DateSerializationMode dateMode, JsonLogicLimits limits) {
        return new Builder(dateMode, limits);
    }

    /**
     * Builds a detached, {@link JsonLogicLimits}-validated 5-root ontological fact tree using
     * {@link DateSerializationMode#ISO_8601}.
     *
     * @param draftEntity candidate state ({@code draft}), or {@code null}
     * @param currentEntity prior persisted state ({@code current}), or {@code null}
     * @param concepts 1-hop domain concept bindings keyed by concept root name (nullable)
     * @param neighborhoodRecord N-hop aggregate/sibling DTO, record, or map (nullable)
     * @param contextMap execution context properties (nullable)
     * @return detached, validated {@link ObjectNode}
     */
    public static ObjectNode buildFiveRoots(
            Object draftEntity,
            Object currentEntity,
            Map<String, ConceptBinding> concepts,
            Object neighborhoodRecord,
            Map<String, Object> contextMap) {
        return buildFiveRoots(
                draftEntity,
                currentEntity,
                concepts,
                neighborhoodRecord,
                contextMap,
                DateSerializationMode.ISO_8601,
                JsonLogicLimits.DEFAULT);
    }

    /**
     * Builds a detached, {@link JsonLogicLimits}-validated 5-root ontological fact tree using the
     * given {@link DateSerializationMode}.
     *
     * @param draftEntity candidate state ({@code draft}), or {@code null}
     * @param currentEntity prior persisted state ({@code current}), or {@code null}
     * @param concepts 1-hop domain concept bindings keyed by concept root name (nullable)
     * @param neighborhoodRecord N-hop aggregate/sibling DTO, record, or map (nullable)
     * @param contextMap execution context properties (nullable)
     * @param dateMode civil date serialization mode
     * @return detached, validated {@link ObjectNode}
     */
    public static ObjectNode buildFiveRoots(
            Object draftEntity,
            Object currentEntity,
            Map<String, ConceptBinding> concepts,
            Object neighborhoodRecord,
            Map<String, Object> contextMap,
            DateSerializationMode dateMode) {
        return buildFiveRoots(
                draftEntity,
                currentEntity,
                concepts,
                neighborhoodRecord,
                contextMap,
                dateMode,
                JsonLogicLimits.DEFAULT);
    }

    /**
     * Builds a detached 5-root ontological fact tree validated against explicit {@link JsonLogicLimits}.
     *
     * @param draftEntity candidate state ({@code draft}), or {@code null}
     * @param currentEntity prior persisted state ({@code current}), or {@code null}
     * @param concepts 1-hop domain concept bindings keyed by concept root name (nullable)
     * @param neighborhoodRecord N-hop aggregate/sibling DTO, record, or map (nullable)
     * @param contextMap execution context properties (nullable)
     * @param dateMode civil date serialization mode
     * @param limits deterministic structural limits
     * @return detached, validated {@link ObjectNode}
     */
    public static ObjectNode buildFiveRoots(
            Object draftEntity,
            Object currentEntity,
            Map<String, ConceptBinding> concepts,
            Object neighborhoodRecord,
            Map<String, Object> contextMap,
            DateSerializationMode dateMode,
            JsonLogicLimits limits) {
        Builder session = new Builder(dateMode, limits)
                .draft(draftEntity)
                .current(currentEntity)
                .concepts(concepts);
        if (neighborhoodRecord != null) {
            session.neighborhood(neighborhoodRecord);
        }
        if (contextMap != null) {
            session.context(contextMap);
        }
        return session.build();
    }

    /**
     * Single-use fluent builder for assembling an ontological fact tree safely.
     */
    public static final class Builder {
        private final PraxisFactTreeBuilder delegate;
        private final DateSerializationMode dateMode;
        private final JsonLogicLimits limits;

        private Builder(DateSerializationMode dateMode, JsonLogicLimits limits) {
            this.dateMode = Objects.requireNonNull(dateMode, "dateMode");
            this.limits = Objects.requireNonNull(limits, "limits");
            this.delegate = PraxisFactTreeBuilder.create(this.dateMode);
        }

        /**
         * Populates {@code draft}, projecting POJOs/records as scalar nodes to prevent association traversal.
         *
         * @param draftEntity draft entity, DTO, record, map, {@link ObjectNode}, or {@code null}
         * @return this builder
         */
        public Builder draft(Object draftEntity) {
            delegate.putDraft(projectEntitySafe(draftEntity, dateMode));
            return this;
        }

        /**
         * Populates {@code draft} fluently via a nested {@link PraxisFactTreeBuilder}.
         *
         * @param consumer callback populating {@code draft}
         * @return this builder
         */
        public Builder draft(Consumer<PraxisFactTreeBuilder> consumer) {
            delegate.putDraft(consumer);
            return this;
        }

        /**
         * Populates {@code current}, projecting POJOs/records as scalar nodes to prevent association traversal.
         *
         * @param currentEntity prior entity, DTO, record, map, {@link ObjectNode}, or {@code null}
         * @return this builder
         */
        public Builder current(Object currentEntity) {
            delegate.putCurrent(projectEntitySafe(currentEntity, dateMode));
            return this;
        }

        /**
         * Populates {@code current} fluently via a nested {@link PraxisFactTreeBuilder}.
         *
         * @param consumer callback populating {@code current}
         * @return this builder
         */
        public Builder current(Consumer<PraxisFactTreeBuilder> consumer) {
            delegate.putCurrent(consumer);
            return this;
        }

        /**
         * Populates one 1-hop domain concept root from a {@link ConceptBinding}.
         *
         * @param conceptKey domain concept root name
         * @param binding concept binding descriptor
         * @return this builder
         */
        public Builder concept(String conceptKey, ConceptBinding binding) {
            String key = validateConceptKey(conceptKey);
            Objects.requireNonNull(binding, "binding");
            if (binding.idPresentInDraft() != null) {
                delegate.putConceptEntity(
                        key, binding.entity(), binding.idPresentInDraft(), binding.nullDefaults());
            } else {
                delegate.putConceptEntity(key, binding.entity(), binding.nullDefaults());
            }
            return this;
        }

        /**
         * Populates one 1-hop domain concept root without {@code idPresentInDraft}.
         *
         * @param conceptKey domain concept root name
         * @param entity preloaded entity/record/map, or {@code null}
         * @param nullDefaults fallback defaults emitted when {@code entity == null}
         * @return this builder
         */
        public Builder concept(String conceptKey, Object entity, Map<String, Object> nullDefaults) {
            String key = validateConceptKey(conceptKey);
            delegate.putConceptEntity(key, entity, nullDefaults);
            return this;
        }

        /**
         * Populates one 1-hop domain concept root tracking {@code idPresentInDraft}.
         *
         * @param conceptKey domain concept root name
         * @param entity preloaded entity/record/map, or {@code null}
         * @param idPresentInDraft whether the draft specified the concept FK
         * @param nullDefaults fallback defaults emitted when {@code entity == null}
         * @return this builder
         */
        public Builder concept(
                String conceptKey,
                Object entity,
                boolean idPresentInDraft,
                Map<String, Object> nullDefaults) {
            String key = validateConceptKey(conceptKey);
            delegate.putConceptEntity(key, entity, idPresentInDraft, nullDefaults);
            return this;
        }

        /**
         * Populates one 1-hop domain concept root fluently via a nested {@link PraxisFactTreeBuilder}.
         *
         * @param conceptKey domain concept root name
         * @param consumer callback populating the concept node
         * @return this builder
         */
        public Builder concept(String conceptKey, Consumer<PraxisFactTreeBuilder> consumer) {
            String key = validateConceptKey(conceptKey);
            delegate.putConcept(key, consumer);
            return this;
        }

        /**
         * Populates multiple 1-hop domain concepts in deterministic key order.
         *
         * @param concepts map of concept root names to {@link ConceptBinding}s (nullable)
         * @return this builder
         */
        public Builder concepts(Map<String, ConceptBinding> concepts) {
            if (concepts == null || concepts.isEmpty()) {
                return this;
            }
            TreeMap<String, ConceptBinding> sorted = new TreeMap<>();
            for (Map.Entry<String, ConceptBinding> entry : concepts.entrySet()) {
                sorted.put(validateConceptKey(entry.getKey()), Objects.requireNonNull(entry.getValue(), "conceptBinding"));
            }
            sorted.forEach(this::concept);
            return this;
        }

        /**
         * Populates {@code neighborhood} from a host aggregate record, DTO, map, {@link ObjectNode}, or {@code null}.
         *
         * @param neighborhoodRecord N-hop neighborhood facts
         * @return this builder
         */
        public Builder neighborhood(Object neighborhoodRecord) {
            delegate.putNeighborhood(neighborhoodRecord);
            return this;
        }

        /**
         * Populates {@code neighborhood} fluently via a nested {@link PraxisFactTreeBuilder}.
         *
         * @param consumer callback populating {@code neighborhood}
         * @return this builder
         */
        public Builder neighborhood(Consumer<PraxisFactTreeBuilder> consumer) {
            delegate.putNeighborhood(consumer);
            return this;
        }

        /**
         * Populates {@code context} from a context map, record, {@link ObjectNode}, or {@code null}.
         *
         * @param context execution context facts
         * @return this builder
         */
        public Builder context(Object context) {
            delegate.putContext(context);
            return this;
        }

        /**
         * Populates {@code context} fluently via a nested {@link PraxisFactTreeBuilder}.
         *
         * @param consumer callback populating {@code context}
         * @return this builder
         */
        public Builder context(Consumer<PraxisFactTreeBuilder> consumer) {
            delegate.putContext(consumer);
            return this;
        }

        /**
         * Materializes a detached {@link ObjectNode} validated against {@link JsonLogicLimits}.
         *
         * @return detached, deterministic ontological fact tree
         */
        public ObjectNode build() {
            ObjectNode tree = delegate.build();
            VALIDATOR.validateResultValue(tree, limits);
            return tree;
        }
    }

    private static Object projectEntitySafe(Object entity, DateSerializationMode dateMode) {
        if (entity == null || entity instanceof JsonNode || entity instanceof Map<?, ?>) {
            return entity;
        }
        return PraxisFactTreeBuilder.toScalarObjectNode(entity, dateMode);
    }

    private static String validateConceptKey(String conceptKey) {
        if (conceptKey == null || conceptKey.isBlank()) {
            throw new IllegalArgumentException("Concept key must not be blank");
        }
        String trimmed = conceptKey.trim();
        if (RESERVED_NON_CONCEPT_ROOTS.contains(trimmed)) {
            throw new IllegalArgumentException(
                    "Concept key cannot reuse reserved ontological root: " + trimmed);
        }
        return trimmed;
    }

    private static Map<String, Object> copyNullableValueMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Concept default key must not be blank");
            }
            copy.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
