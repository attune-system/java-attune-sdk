package io.attune;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensorContextTest {

    @Test
    void contextHasDefaults() {
        SensorContext ctx = SensorContext.instance();
        assertNotNull(ctx);
        assertNotNull(ctx.sensorRef());
        assertEquals("http://localhost:8080", ctx.apiUrl());
        assertEquals("amqp://localhost:5672", ctx.mqUrl());
        assertEquals("attune", ctx.mqExchange());
        assertEquals("INFO", ctx.logLevel());
    }

    @Test
    void configReturnsEmptyMapWithoutEnv() {
        SensorContext ctx = SensorContext.instance();
        assertNotNull(ctx.config());
    }
}
