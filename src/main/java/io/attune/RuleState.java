package io.attune;

import java.util.Collections;
import java.util.Map;

/**
 * Represents an active rule bound to a sensor.
 *
 * @param ruleId the rule database ID
 * @param ruleRef the rule reference string (e.g., {@code mypack.alert_rule})
 * @param triggerRef the trigger reference this rule is bound to
 * @param triggerParams per-rule trigger parameters (interval, query, etc.)
 * @param enabled whether the rule is currently enabled
 */
public record RuleState(
        int ruleId,
        String ruleRef,
        String triggerRef,
        Map<String, Object> triggerParams,
        boolean enabled
) {
    public RuleState {
        if (triggerParams == null) {
            triggerParams = Collections.emptyMap();
        }
    }

    /**
     * Returns a copy of this rule with updated enabled state.
     */
    public RuleState withEnabled(boolean enabled) {
        return new RuleState(ruleId, ruleRef, triggerRef, triggerParams, enabled);
    }

    /**
     * Returns a copy of this rule with updated trigger params.
     */
    public RuleState withTriggerParams(Map<String, Object> triggerParams) {
        return new RuleState(ruleId, ruleRef, triggerRef, triggerParams, enabled);
    }
}
