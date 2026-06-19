package io.attune;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    void handleNotifierEnvelopeWithLowercaseLifecycleEvent() {
        AtomicReference<RuleState> created = new AtomicReference<>();
        Sensor sensor = new Sensor() {
            @Override
            public void onRuleCreated(RuleState rule) {
                created.set(rule);
            }
        };

        sensor.handleNotifierEnvelope(Map.of(
                "type", "notification",
                "payload", Map.of(
                        "event_type", "rule.created",
                        "rule_id", 42,
                        "rule_ref", "mypack.my_rule",
                        "trigger_ref", "my_trigger",
                        "trigger_params", Map.of("interval", 5000),
                        "active", true
                )
        ));

        assertNotNull(created.get());
        assertEquals("mypack.my_rule", created.get().ruleRef());
        assertEquals(Map.of("interval", 5000), created.get().triggerParams());
    }

    @Test
    void handleInactiveRuleCreatedDoesNotInvokeCreatedHook() {
        AtomicReference<RuleState> created = new AtomicReference<>();
        Sensor sensor = new Sensor() {
            @Override
            public void onRuleCreated(RuleState rule) {
                created.set(rule);
            }
        };

        sensor.handleNotifierEnvelope(Map.of(
                "type", "notification",
                "payload", Map.of(
                        "event_type", "rule.created",
                        "rule_id", 7,
                        "rule_ref", "mypack.disabled_rule",
                        "trigger_ref", "my_trigger",
                        "trigger_params", Map.of("interval", 1000),
                        "active", false
                )
        ));

        assertNull(created.get());
        assertFalse(sensor.rules().get(7).enabled());
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

        Map<String, Object> createMsg = new HashMap<>();
        createMsg.put("event_type", "RuleCreated");
        createMsg.put("rule_id", 1);
        createMsg.put("rule_ref", "r1");
        createMsg.put("trigger_ref", "t1");
        createMsg.put("trigger_params", Collections.emptyMap());
        sensor.handleRuleMessage(createMsg);

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

        Map<String, Object> createMsg = new HashMap<>();
        createMsg.put("event_type", "RuleCreated");
        createMsg.put("rule_id", 1);
        createMsg.put("rule_ref", "r1");
        createMsg.put("trigger_ref", "t1");
        createMsg.put("trigger_params", Map.of("interval", 5000));
        sensor.handleRuleMessage(createMsg);

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

    @Test
    void notifierReconnectUsesUpdatedTokenState() throws Exception {
        AtomicReference<String> token = new AtomicReference<>("token-1");
        Sensor sensor = new Sensor() {
            @Override
            protected void sleep(long millis) {
                // Keep reconnect loop deterministic/fast in test.
            }
        };

        SensorTokenProvider originalProvider = sensor.context.tokenProvider();
        try {
            sensor.context.setTokenProvider(() -> new SensorTokenState(token.get(), null));

            Map<String, Object> createMsg = new HashMap<>();
            createMsg.put("event_type", "RuleCreated");
            createMsg.put("rule_id", 1);
            createMsg.put("rule_ref", "r1");
            createMsg.put("trigger_ref", "pack.trigger");
            createMsg.put("trigger_params", Collections.emptyMap());
            sensor.handleRuleMessage(createMsg);

            List<String> authHeaders = new ArrayList<>();
            AtomicInteger connects = new AtomicInteger();
            WebSocket.Builder builder = new RecordingWebSocketBuilder(authHeaders, () -> {
                int attempt = connects.incrementAndGet();
                if (attempt == 1) {
                    token.set("token-2");
                } else if (attempt >= 2) {
                    sensor.shutdown();
                }
            });

            Field httpClientField = Sensor.class.getDeclaredField("httpClient");
            httpClientField.setAccessible(true);
            httpClientField.set(sensor, new RecordingHttpClient(builder));

            Method loop = Sensor.class.getDeclaredMethod("lifecycleConsumeLoop");
            loop.setAccessible(true);
            loop.invoke(sensor);

            assertEquals(List.of("Bearer token-1", "Bearer token-2"), authHeaders);
        } finally {
            sensor.context.setTokenProvider(originalProvider);
        }
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final WebSocket.Builder webSocketBuilder;

        private RecordingHttpClient(WebSocket.Builder webSocketBuilder) {
            this.webSocketBuilder = webSocketBuilder;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("send not used in this test");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException("sendAsync not used in this test");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException("sendAsync not used in this test");
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            return webSocketBuilder;
        }
    }

    private static final class RecordingWebSocketBuilder implements WebSocket.Builder {
        private final List<String> authHeaders;
        private final Runnable onConnect;
        private String authorizationHeader;

        private RecordingWebSocketBuilder(List<String> authHeaders, Runnable onConnect) {
            this.authHeaders = authHeaders;
            this.onConnect = onConnect;
        }

        @Override
        public WebSocket.Builder header(String name, String value) {
            if ("Authorization".equalsIgnoreCase(name)) {
                authorizationHeader = value;
            }
            return this;
        }

        @Override
        public WebSocket.Builder connectTimeout(Duration timeout) {
            return this;
        }

        @Override
        public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
            return this;
        }

        @Override
        public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
            if (authorizationHeader != null) {
                authHeaders.add(authorizationHeader);
            }
            if (onConnect != null) {
                onConnect.run();
            }
            TestWebSocket ws = new TestWebSocket();
            listener.onOpen(ws);
            listener.onClose(ws, 1000, "test");
            return CompletableFuture.completedFuture(ws);
        }
    }

    private static final class TestWebSocket implements WebSocket {
        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
