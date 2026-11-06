package org.praxisplatform.rules.facts;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

/** Formats in the closed fact-schema profile, validated without domain policy. */
final class RuleFactFormats {
    static final Set<String> SUPPORTED = Set.of("date", "date-time", "praxis-local-date-time",
            "praxis-local-time", "praxis-duration", "praxis-zone-id", "praxis-decimal");

    private RuleFactFormats() { }

    static void validate(String format, String text, String path) {
        try {
            switch (format) {
                case "date" -> {
                    require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
                    LocalDate.parse(text);
                }
                case "date-time" -> {
                    require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})"));
                    OffsetDateTime.parse(text);
                }
                case "praxis-local-date-time" -> {
                    require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?"));
                    LocalDateTime.parse(text);
                }
                case "praxis-local-time" -> {
                    require(text.matches("[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?"));
                    LocalTime.parse(text);
                }
                case "praxis-duration" -> Duration.parse(text);
                case "praxis-zone-id" -> ZoneId.of(text);
                case "praxis-decimal" -> require(text.length() <= 128 && new BigDecimal(text).toString().equals(text));
                default -> { }
            }
        } catch (RuntimeException invalid) {
            RuleFactSchema.mismatch(path);
        }
    }

    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(); }
}
