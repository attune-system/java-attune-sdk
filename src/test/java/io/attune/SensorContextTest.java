package io.attune;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SensorContextTest {

    @Test
    void contextHasDefaults() {
        SensorContext ctx = SensorContext.instance();
        assertNotNull(ctx);
        assertNotNull(ctx.sensorRef());
        assertEquals("http://localhost:8080", ctx.apiUrl());
        assertEquals("ws://localhost:8081/ws", ctx.notifierWsUrl());
        assertEquals("INFO", ctx.logLevel());
    }

    @Test
    void configReturnsEmptyMapWithoutEnv() {
        SensorContext ctx = SensorContext.instance();
        assertNotNull(ctx.config());
    }

    @Test
    void tokenProviderCanRotateTokenForContextAndClient() {
        SensorContext ctx = SensorContext.instance();
        SensorTokenProvider originalProvider = ctx.tokenProvider();

        try {
            AtomicReference<String> token = new AtomicReference<>("token-1");
            ctx.setTokenProvider(() -> new SensorTokenState(token.get(), "2099-01-01T00:00:00Z"));

            assertEquals("token-1", ctx.apiToken());
            assertEquals("2099-01-01T00:00:00Z", ctx.apiTokenExpiresAt().orElseThrow());

            AttuneClient client = ctx.client();
            assertEquals("token-1", client.apiToken());

            token.set("token-2");
            assertEquals("token-2", ctx.apiToken());
            assertEquals("token-2", client.apiToken());
        } finally {
            ctx.setTokenProvider(originalProvider);
        }
    }

    @Test
    void buildTokenProviderFallsBackWhenNoRotationSourceConfigured() throws Exception {
        SensorTokenProvider provider = invokeBuildTokenProvider("fallback-token", null, null);
        SensorTokenState state = provider.currentTokenState();
        assertEquals("fallback-token", state.token());
        assertNull(state.expiresAt());
    }

    private static SensorTokenProvider invokeBuildTokenProvider(
            String initialToken,
            String initialExpiresAt,
            String tokenStatePath
    ) throws Exception {
        Method method = SensorContext.class.getDeclaredMethod(
                "buildTokenProvider",
                String.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        return (SensorTokenProvider) method.invoke(null, initialToken, initialExpiresAt, tokenStatePath);
    }
}
