package io.attune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensorEmitTypedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // ------------------------------------------------------------------
    // Test payload types
    // ------------------------------------------------------------------

    public static class TempAlert {
        public double temperature;
        public boolean alert;

        public TempAlert() {}
        public TempAlert(double temperature, boolean alert) {
            this.temperature = temperature;
            this.alert = alert;
        }
    }

    public static class StatusEvent {
        public String url;
        public int statusCode;
        public String message;

        public StatusEvent() {}
        public StatusEvent(String url, int statusCode, String message) {
            this.url = url;
            this.statusCode = statusCode;
            this.message = message;
        }
    }

    // ------------------------------------------------------------------
    // Tests verifying typed payloads convert to maps correctly
    // ------------------------------------------------------------------

    @Test
    void pojoConvertsToMapViaJackson() {
        TempAlert alert = new TempAlert(105.3, true);
        Map<String, Object> map = MAPPER.convertValue(alert, MAP_TYPE);
        assertEquals(105.3, map.get("temperature"));
        assertEquals(true, map.get("alert"));
    }

    @Test
    void pojoWithStringFieldsConvertsCorrectly() {
        StatusEvent event = new StatusEvent("https://example.com", 503, "Service Unavailable");
        Map<String, Object> map = MAPPER.convertValue(event, MAP_TYPE);
        assertEquals("https://example.com", map.get("url"));
        assertEquals(503, map.get("statusCode"));
        assertEquals("Service Unavailable", map.get("message"));
    }

    @Test
    void emitTypedDelegatesToEmitWithConvertedMap() {
        // Create a sensor subclass that captures what emit() receives
        final Map<String, Object>[] captured = new Map[1];
        Sensor sensor = new Sensor() {
            @Override
            public Integer emit(Map<String, Object> payload, EmitOptions options) {
                captured[0] = payload;
                return 99;
            }
        };

        TempAlert alert = new TempAlert(42.5, false);
        Integer result = sensor.emitTyped(alert);

        assertNotNull(captured[0]);
        assertEquals(42.5, captured[0].get("temperature"));
        assertEquals(false, captured[0].get("alert"));
        assertEquals(99, result);
    }

    @Test
    void emitTypedWithOptionsPassesOptionsThrough() {
        final EmitOptions[] capturedOptions = new EmitOptions[1];
        Sensor sensor = new Sensor() {
            @Override
            public Integer emit(Map<String, Object> payload, EmitOptions options) {
                capturedOptions[0] = options;
                return 1;
            }
        };

        RuleState rule = new RuleState(7, "mypack.rule", "my_trigger", Map.of("key", "val"), true);
        EmitOptions opts = EmitOptions.create().rule(rule).targetRule(true);
        sensor.emitTyped(new StatusEvent("http://test", 200, "OK"), opts);

        assertNotNull(capturedOptions[0]);
        assertEquals(rule, capturedOptions[0].rule());
        assertTrue(capturedOptions[0].targetRule());
    }

    @Test
    void emitTypedWithNoArgOptionsWorks() {
        final Map<String, Object>[] captured = new Map[1];
        Sensor sensor = new Sensor() {
            @Override
            public Integer emit(Map<String, Object> payload, EmitOptions options) {
                captured[0] = payload;
                return 5;
            }
        };

        Integer result = sensor.emitTyped(new TempAlert(99.9, true));
        assertEquals(5, result);
        assertEquals(99.9, captured[0].get("temperature"));
        assertEquals(true, captured[0].get("alert"));
    }

    @Test
    void emitTypedHandlesNestedObjects() {
        // Verifies nested structures serialize properly
        class NestedPayload {
            public String name = "test";
            public Map<String, Object> metadata = Map.of("version", 2, "active", true);
        }

        final Map<String, Object>[] captured = new Map[1];
        Sensor sensor = new Sensor() {
            @Override
            public Integer emit(Map<String, Object> payload, EmitOptions options) {
                captured[0] = payload;
                return 10;
            }
        };

        sensor.emitTyped(new NestedPayload());

        assertEquals("test", captured[0].get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) captured[0].get("metadata");
        assertEquals(2, meta.get("version"));
        assertEquals(true, meta.get("active"));
    }
}
