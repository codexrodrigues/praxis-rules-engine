package org.praxisplatform.rules.parametric;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Child-to-matrix precedence without truthy coercion or loss of explicit false/zero.
 */
public final class TriStateOverrideResolver {
    private TriStateOverrideResolver() { }
    /**
     * Runtime-neutral tri-state vocabulary.
     */
    public enum TriState {
        /** Explicit permission. */ PERMITIR,
        /** Explicit prohibition. */ VEDAR,
        /** No decision at this level. */ HERDAR
    }

    /**
     * Parses only the declared flag vocabulary.
     * @param value S/N/I, PERMITIR/VEDAR/HERDAR, or null for inheritance
     * @return strict normalized state
     */
    public static TriState parse(String value) {
        if (value == null) return TriState.HERDAR;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "S", "PERMITIR" -> TriState.PERMITIR;
            case "N", "VEDAR" -> TriState.VEDAR;
            case "I", "HERDAR" -> TriState.HERDAR;
            default -> throw new IllegalArgumentException("Unknown tri-state value");
        };
    }

    /**
     * Resolves a flag from the individual row before consulting the matrix.
     * @param child individual flag
     * @param matrix inherited flag
     * @return empty when neither level decides
     */
    public static Optional<Boolean> resolve(String child, String matrix) {
        return resolve(parse(child), parse(matrix));
    }

    /**
     * Resolves normalized states without creating an implicit default permission.
     * @param child individual state
     * @param matrix inherited state
     * @return effective explicit decision, if any
     */
    public static Optional<Boolean> resolve(TriState child, TriState matrix) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(matrix, "matrix");
        TriState effective = child == TriState.HERDAR ? matrix : child;
        return effective == TriState.HERDAR ? Optional.empty() : Optional.of(effective == TriState.PERMITIR);
    }

    /**
     * Selects an optional numeric override without truthy coercion.
     * @param <N> host numeric type
     * @param child optional individual numeric value
     * @param matrix optional matrix value
     * @return child when present, preserving zero
     */
    public static <N extends Number> Optional<N> resolveNumber(N child, N matrix) {
        return Optional.ofNullable(child == null ? matrix : child);
    }
}
