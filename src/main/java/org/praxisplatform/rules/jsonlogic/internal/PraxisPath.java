package org.praxisplatform.rules.jsonlogic.internal;

import java.util.ArrayList;
import java.util.List;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode;

/** Parser for the closed Praxis path subset. */
public final class PraxisPath {
    private PraxisPath() { }

    /**
     * Parses a variable path without permitting dynamic navigation syntax.
     * @param raw path using dot notation or numeric bracket indexes
     * @return immutable-order path segments
     * @throws PraxisJsonLogicException when the path is outside the closed Praxis subset
     */
    public static List<String> parse(String raw) {
        if (raw == null) throw invalid("null");
        if (raw.isEmpty()) return List.of();
        if (raw.contains("..") || raw.startsWith(".") || raw.endsWith(".")) throw invalid(raw);
        int i = raw.startsWith("$.") ? 2 : 0;
        if (raw.equals("$") || raw.startsWith("$") && i == 0) throw invalid(raw);
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (ch == '.') {
                flush(token, out, raw);
                i++;
            } else if (ch == '[') {
                flush(token, out, raw);
                int end = raw.indexOf(']', i);
                if (end < 0) throw invalid(raw);
                String body = raw.substring(i + 1, end);
                if (body.matches("\\d+")) out.add(boundedNumericSegment(body, raw));
                else if (body.matches("\"[^\"\\\\]+\"")) out.add(body.substring(1, body.length() - 1));
                else throw invalid(raw);
                i = end + 1;
                if (i < raw.length() && raw.charAt(i) != '.' && raw.charAt(i) != '[') throw invalid(raw);
            } else {
                if (Character.isWhitespace(ch) || ch == '*' || ch == '?' || ch == '$') throw invalid(raw);
                token.append(ch);
                i++;
            }
        }
        flush(token, out, raw);
        if (out.isEmpty()) throw invalid(raw);
        return List.copyOf(out);
    }

    private static void flush(StringBuilder token, List<String> out, String raw) {
        if (token.length() == 0) {
            if (out.isEmpty() || raw.endsWith(".")) throw invalid(raw);
            return;
        }
        out.add(token.toString());
        token.setLength(0);
    }

    private static String boundedNumericSegment(String segment, String raw) {
        try {
            Integer.parseInt(segment);
            return segment;
        } catch (NumberFormatException exception) {
            throw invalid(raw);
        }
    }

    private static PraxisJsonLogicException invalid(String path) {
        return new PraxisJsonLogicException(JsonLogicIssueCode.RULE_PATH_INVALID,
            "Invalid Praxis JSON Logic path: \"" + path + "\".", "$", "var");
    }
}
