package io.attune;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionContextTest {

    @Test
    void contextHasDefaults() {
        ActionContext ctx = ActionContext.instance();
        assertNotNull(ctx);
        assertNotNull(ctx.actionRef());
        assertNotNull(ctx.packRef());
        assertNotNull(ctx.executionId());
        assertEquals("http://localhost:8080", ctx.apiUrl());
    }

    @Test
    void hasApiTokenReturnsFalseWithoutEnv() {
        ActionContext ctx = ActionContext.instance();
        // In test env, ATTUNE_API_TOKEN is not set
        assertFalse(ctx.hasApiToken());
    }

    @Test
    void clientThrowsWithoutToken() {
        ActionContext ctx = ActionContext.instance();
        assertThrows(IllegalStateException.class, ctx::client);
    }
}
