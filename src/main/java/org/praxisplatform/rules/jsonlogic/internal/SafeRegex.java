package org.praxisplatform.rules.jsonlogic.internal;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;

/** Common bounded regex subset: no groups, alternation, backreferences, or unbounded quantifiers. */
public final class SafeRegex {
    private SafeRegex() { }

    /**
     * Validates and compiles an expression from the bounded cross-runtime regex subset.
     * @param expression regular expression supplied by a rule
     * @param limits active length and complexity limits
     * @return compiled Java pattern
     * @throws PraxisJsonLogicException when the expression is unsafe, invalid, or over budget
     */
    public static Pattern compile(String expression, JsonLogicLimits limits) {
        if (expression.length() > limits.maxRegexLength()) throw invalid("exceeds the maximum length");
        if (expression.matches(".*[()*+|].*") || expression.matches(".*\\\\[1-9].*")) throw invalid("uses groups, alternation, backreferences, or unbounded quantifiers");
        String withoutBounds = expression.replaceAll("\\{\\d+(,\\d+)?}", "");
        if (withoutBounds.indexOf('{') >= 0 || withoutBounds.indexOf('}') >= 0) throw invalid("uses an invalid bounded quantifier");
        var bounds = Pattern.compile("\\{(\\d+)(?:,(\\d+))?}").matcher(expression);
        while (bounds.find()) {
            int maximum = Integer.parseInt(bounds.group(2) == null ? bounds.group(1) : bounds.group(2));
            if (maximum > 256) throw invalid("uses a bounded quantifier above 256");
        }
        long complexity = expression.chars().filter(c -> c == '?' || c == '{').count();
        if (complexity > limits.maxRegexComplexity()) throw invalid("exceeds the complexity limit");
        try { return Pattern.compile(expression); }
        catch (PatternSyntaxException ex) { throw invalid("is syntactically invalid"); }
    }

    private static PraxisJsonLogicException invalid(String reason) {
        return new PraxisJsonLogicException(JsonLogicIssueCode.RULE_REGEX_INVALID,
            "Regular expression " + reason + ".", "$", "matches");
    }
}
