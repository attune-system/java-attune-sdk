package io.attune;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SensorTest {

    @Test
    void bootstrapRulesFromEmptyEnv() {
        Sensor sensor = new Sensor();
        sensor.bootstrapRules();
        assertTrue(sensor.rules().isEmpty());
    }

    @Test
    void handleRuleCreatedMessage() {
        AtomicReference<RuleState> created = new AtomicReference<>();
        Sensor sensor = new Sensor() {
            @Override
            public void onRuleCreated(RuleState rule) {
                created.set(rule);
            }
        };

        Map<String, Object> msg = new HashMap<>();
        msg.put("event_type", "RuleCreated");
        msg.put("rule_id", 42);
        msg.put("rule_ref", "mypack.my_rule");
        msg.put("trigger_ref", "my_trigger");
        msg.put("trigger_params", Map.of("interval", 5000));

        sensor.handleRuleMessage(msg);

        assertNotNull(created.get());
        assertEquals(42, created.get().ruleId());
        assertEquals("mypack.my_rule", created.get().ruleRef());
        assertEquals("my_trigger", created.get().triggerRef());
        assertEquals(1, sensor.rules().size());
    }

    @Test
    void handleRuleDeletedMessage() {
        AtomicReference<RuleState> deleted = new AtomicReference<>();
        Sensor sensor = new Sensor() {
            @Override
            public void onRuleCreated(RuleState rule) {}
            @Override
            public void onRuleDeleted(RuleState rule) {
                deleted.set(rule);
            }
        };

        // First create
        Map<String, Object> createMsg = new HashMap<>();
        createMsg.put("event_type", "RuleCreated");
        createMsg.put("rule_id", 1);
        createMsg.put("rule_ref", "r1");
        createMsg.put("trigger_ref", "t1");
        createMsg.put("trigger_params", Collections.emptyMap());
        sensor.handleRuleMessage(createMsg);

        // Then delete
        Map<String, Object> deleteMsg = new HashMap<>();
        deleteMsg.put("event_type", "RuleDeleted");
        deleteMsg.put("rule_id", 1);
        sensor.handleRuleMessage(deleteMsg);

        assertNotNull(deleted.get());
        assertEquals(1, deleted.get().ruleId());
        assertTrue(sensor.rules().isEmpty());
    }

    @Test
    void handleRuleUpdatedMessage() {
        AtomicReference<Map<String, Object>> oldParams = new AtomicReference<>();
        Sensor sensor = new Sensor() {
            @Override
            public void onRuleCreated(RuleState rule) {}
            @Override
            public void onRuleUpdated(RuleState rule, Map<String, Object> old) {
                oldParams.set(old);
            }
        };

        // Create
        Map<String, Object> createMsg = new HashMap<>();
        createMsg.put("event_type", "RuleCreated");
        createMsg.put("rule_id", 1);
        createMsg.put("rule_ref", "r1");
        createMsg.put("trigger_ref", "t1");
        createMsg.put("trigger_params", Map.of("interval", 5000));
        sensor.handleRuleMessage(createMsg);

        // Update
        Map<String, Object> updateMsg = new HashMap<>();
        updateMsg.put("event_type", "RuleUpdated");
        updateMsg.put("rule_id", 1);
        updateMsg.put("rule_ref", "r1");
        updateMsg.put("trigger_ref", "t1");
        updateMsg.put("trigger_params", Map.of("interval", 10000));
        sensor.handleRuleMessage(updateMsg);

        assertNotNull(oldParams.get());
        assertEquals(Map.of("interval", 5000), oldParams.get());
        assertEquals(Map.of("interval", 10000), sensor.rules().get(1).triggerParams());
    }

    @Test
    void shutdownSetsFlag() {
        Sensor sensor = new Sensor();
        assertFalse(sensor.isShuttingDown());
        sensor.shutdown();
        assertTrue(sensor.isShuttingDown());
    }
}
