package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowStepRequest.SkipConditionRequest;
import com.example.flowengine.DTO.FlowStepRequest.SkipConditionRequest.SkipConditionRule;
import com.example.flowengine.utils.PlaceholderUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Evaluates whether a step should be skipped based on its skipCondition
 * against the accumulated response context from previous steps.
 *
 * Condition structure:
 * {
 *   "logic": "AND" | "OR",
 *   "conditions": [
 *     { "path": "order.status", "operator": "equals", "value": "cancelled" },
 *     { "path": "retryCount",   "operator": "greaterThan", "value": "3" }
 *   ]
 * }
 *
 * Supported operators: equals, notEquals, contains, greaterThan, lessThan, exists, in
 * Values support {placeholder} syntax — resolved against previousResponses before comparison.
 */
@Slf4j
@Service
public class SkipConditionEvaluator {

    /**
     * Evaluates a poll condition against the current response body only.
     * Unlike shouldSkip() which looks at all accumulated previous responses,
     * this only looks at the specific response body just received — what you want
     * when polling: "did THIS response contain status=COMPLETED?"
     *
     * @return true if the condition is satisfied (poll can stop), false if still waiting
     */
    public boolean isSatisfied(SkipConditionRequest condition, String currentResponseBody) {
        if (condition == null || condition.getConditions() == null || condition.getConditions().isEmpty()) {
            return true; // no condition = always satisfied; fall back to status code check only
        }

        if (currentResponseBody == null || currentResponseBody.isBlank()) {
            log.debug("[PollCondition] Empty response body — condition not satisfied");
            return false;
        }

        // Build lookup map from just this one response body
        Map<String, String> context = PlaceholderUtils.buildLookupMap(List.of(currentResponseBody));
        String logic = condition.getLogic() != null ? condition.getLogic().toUpperCase() : "AND";
        List<SkipConditionRule> rules = condition.getConditions();

        if ("OR".equals(logic)) {
            for (SkipConditionRule rule : rules) {
                EvalResult result = evaluate(rule, context, List.of(currentResponseBody));
                if (result.matched) {
                    log.debug("[PollCondition] OR condition satisfied: {}", result.description);
                    return true;
                }
            }
            return false;
        } else {
            // AND — all must match
            for (SkipConditionRule rule : rules) {
                EvalResult result = evaluate(rule, context, List.of(currentResponseBody));
                if (!result.matched) {
                    log.debug("[PollCondition] AND condition not yet satisfied: {}", result.description);
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * @return null if no skip should happen; a human-readable reason string if the step should be skipped.
     */
    public String shouldSkip(SkipConditionRequest condition, List<String> previousResponses) {
        if (condition == null || condition.getConditions() == null || condition.getConditions().isEmpty()) {
            return null;
        }

        Map<String, String> context = PlaceholderUtils.buildLookupMap(previousResponses);
        String logic = condition.getLogic() != null ? condition.getLogic().toUpperCase() : "AND";
        List<SkipConditionRule> rules = condition.getConditions();

        if ("OR".equals(logic)) {
            for (SkipConditionRule rule : rules) {
                EvalResult result = evaluate(rule, context, previousResponses);
                if (result.matched) {
                    return "OR condition matched — " + result.description;
                }
            }
            return null; // none matched
        } else {
            // AND — all must match
            StringBuilder matchedDescriptions = new StringBuilder();
            for (SkipConditionRule rule : rules) {
                EvalResult result = evaluate(rule, context, previousResponses);
                if (!result.matched) {
                    return null; // short-circuit: one didn't match
                }
                if (matchedDescriptions.length() > 0) matchedDescriptions.append("; ");
                matchedDescriptions.append(result.description);
            }
            return "AND conditions matched — " + matchedDescriptions;
        }
    }

    private EvalResult evaluate(SkipConditionRule rule, Map<String, String> context, List<String> previousResponses) {
        if (rule.getPath() == null || rule.getOperator() == null) {
            return EvalResult.noMatch("invalid rule — path or operator is null");
        }

        String path = rule.getPath();
        String operator = rule.getOperator();
        String actualValue = context.get(path); // null if path not found

        // Resolve {placeholder} in the expected value
        String expectedRaw = rule.getValue() != null ? String.valueOf(rule.getValue()) : "";
        String expected = PlaceholderUtils.resolveValue(expectedRaw, previousResponses);

        String desc = "path='" + path + "' operator='" + operator + "' expected='" + expected + "' actual='" + actualValue + "'";

        return switch (operator) {
            case "exists" -> {
                boolean shouldExist = !"false".equalsIgnoreCase(expected);
                boolean actuallyExists = actualValue != null;
                yield (shouldExist == actuallyExists)
                        ? EvalResult.matched(desc)
                        : EvalResult.noMatch(desc);
            }
            case "equals" -> {
                if (actualValue == null) yield EvalResult.noMatch(desc + " (field missing)");
                yield actualValue.equals(expected) ? EvalResult.matched(desc) : EvalResult.noMatch(desc);
            }
            case "notEquals" -> {
                if (actualValue == null) yield EvalResult.noMatch(desc + " (field missing)");
                yield !actualValue.equals(expected) ? EvalResult.matched(desc) : EvalResult.noMatch(desc);
            }
            case "contains" -> {
                if (actualValue == null) yield EvalResult.noMatch(desc + " (field missing)");
                yield actualValue.contains(expected) ? EvalResult.matched(desc) : EvalResult.noMatch(desc);
            }
            case "greaterThan" -> {
                if (actualValue == null) yield EvalResult.noMatch(desc + " (field missing)");
                try {
                    double actual = Double.parseDouble(actualValue);
                    double exp = Double.parseDouble(expected);
                    yield actual > exp ? EvalResult.matched(desc) : EvalResult.noMatch(desc);
                } catch (NumberFormatException e) {
                    yield EvalResult.noMatch(desc + " (non-numeric value)");
                }
            }
            case "lessThan" -> {
                if (actualValue == null) yield EvalResult.noMatch(desc + " (field missing)");
                try {
                    double actual = Double.parseDouble(actualValue);
                    double exp = Double.parseDouble(expected);
                    yield actual < exp ? EvalResult.matched(desc) : EvalResult.noMatch(desc);
                } catch (NumberFormatException e) {
                    yield EvalResult.noMatch(desc + " (non-numeric value)");
                }
            }
            case "in" -> {
                if (actualValue == null) yield EvalResult.noMatch(desc + " (field missing)");
                // expected = comma-separated list e.g. "CANCELLED,REFUNDED,CLOSED"
                String[] options = expected.split(",");
                boolean matched = false;
                for (String option : options) {
                    if (actualValue.equals(option.trim())) {
                        matched = true;
                        break;
                    }
                }
                yield matched ? EvalResult.matched(desc) : EvalResult.noMatch(desc);
            }
            default -> {
                log.warn("SkipConditionEvaluator: unknown operator '{}' — treating as no-match", operator);
                yield EvalResult.noMatch("unknown operator: " + operator);
            }
        };
    }

    private record EvalResult(boolean matched, String description) {
        static EvalResult matched(String desc) { return new EvalResult(true, desc); }
        static EvalResult noMatch(String desc)  { return new EvalResult(false, desc); }
    }
}