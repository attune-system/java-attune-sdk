package io.attune;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleStateTest {

    @Test
    void constructsWithDefaults() {
        RuleState rule = new RuleState(1, "mypack.rule1", "trigger1", null, true);
        assertEquals(1, rule.ruleId());
        assertEquals("mypack.rule1", rule.ruleRef());
        assertEquals("trigger1", rule.triggerRef());
        assertEquals(Collections.emptyMap(), rule.triggerParams());
        assertTrue(rule.enabled());
    }

    @Test
    void withEnabledCreatesNewInstance() {
        RuleState rule = new RuleState(1, "r", "t", Map.of("key", "val"), true);
        RuleState disabled = rule.withEnabled(false);
        assertTrue(rule.enabled());
        assertFalse(disabled.enabled());
        assertEquals(rule.ruleId(), disabled.ruleId());
    }

    @Test
    void withTriggerParamsCreatesNewInstance() {
        RuleState rule = new RuleState(1, "r", "t", Map.of("a", "1"), true);
        RuleState updated = rule.withTriggerParams(Map.of("b", "2"));
        assertEquals(Map.of("a", "1"), rule.triggerParams());
        assertEquals(Map.of("b", "2"), updated.triggerParams());
    }
}
