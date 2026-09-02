package io.attune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for all Attune sensors.
 *
 * <p>Provides:
 * <ul>
 *   <li>Signal handling (shutdown hook) for graceful shutdown</li>
 *   <li>Rule lifecycle hooks (created, enabled, disabled, deleted, updated)</li>
 *   <li>Event emission via the Attune API</li>
 *   <li>Structured logging via SLF4J</li>
 *   <li>Bootstrap from {@code ATTUNE_SENSOR_TRIGGERS} environment variable</li>
 *   <li>Managed sensor lifecycle updates via notifier WebSocket</li>
 * </ul>
 *
 * <p>Subclasses should override {@link #run()} for custom event loops, or extend
 * {@link PollingSensor} / {@link AsyncPollingSensor} for polling patterns.
 *
 * <p>Example (custom event loop):
 * <pre>{@code
 * public class FileTailSensor extends Sensor {
 *     @Override
 *     public void run() {
 *         while (!isShuttingDown()) {
 *             // check for events...
 *             sleep(500);
 *         }
 *     }
 * }
 * }</pre>
 */
public class Sensor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    protected final SensorContext context;
    protected final Logger logger;

    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final ConcurrentHashMap<Integer, RuleState> rules = new ConcurrentHashMap<>();
    private final Set<String> managedTriggerRefs = ConcurrentHashMap.newKeySet();
    private volatile HttpClient httpClient;
    private volatile WebSocket lifecycleSocket;

    public Sensor() {
        this.context = SensorContext.instance();
        this.logger = LoggerFactory.getLogger("attune.sensor." +
                (context.sensorRef().isEmpty() ? "unknown" : context.sensorRef()));
    }

    // ------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------

    /** Whether a shutdown signal has been received. */
    public boolean isShuttingDown() {
        return shutdownRequested.get();
    }

    /** Active rules keyed by rule_id. Returns an unmodifiable snapshot. */
    public Map<Integer, RuleState> rules() {
        return Collections.unmodifiableMap(new HashMap<>(rules));
    }

    /** Sensor-level configuration from environment variables. */
    public Map<String, String> config() {
        return context.config();
    }

    // ------------------------------------------------------------------
    // Lifecycle hooks (override in subclasses)
    // ------------------------------------------------------------------

    /** Called once before the main loop starts. Override to initialize resources. */
    public void setup() {}

    /** Called once during shutdown. Override to release resources. */
    public void cleanup() {}

    /**
     * Main sensor loop. Override for custom event-driven sensors.
     *
     * <p>The default implementation waits for shutdown (suitable when all work
     * is driven by rule lifecycle hooks).
     */
    public void run() {
        while (!isShuttingDown()) {
            sleep(500);
        }
    }

    // ------------------------------------------------------------------
    // Rule lifecycle hooks (override in subclasses)
    // ------------------------------------------------------------------

    /** Called when a new rule is created and enabled for this sensor. */
    public void onRuleCreated(RuleState rule) {}

    /** Called when a previously disabled rule is re-enabled. Default delegates to onRuleCreated. */
    public void onRuleEnabled(RuleState rule) {
        onRuleCreated(rule);
    }

    /** Called when an active rule is disabled. */
    public void onRuleDisabled(RuleState rule) {}

    /** Called when a rule is permanently deleted. Default delegates to onRuleDisabled. */
    public void onRuleDeleted(RuleState rule) {
        onRuleDisabled(rule);
    }

    /** Called when a rule's trigger parameters change. Default disables then re-enables. */
    public void onRuleUpdated(RuleState rule, Map<String, Object> oldParams) {
        onRuleDisabled(rule);
        onRuleEnabled(rule);
    }

    // ------------------------------------------------------------------
    // Event emission
    // ------------------------------------------------------------------

    /**
     * Emit a sensor event via the Attune API.
     *
     * @param payload the event payload as a map
     * @return the event ID if successfully posted, or null on failure
     */
    public Integer emit(Map<String, Object> payload) {
        return emit(payload, EmitOptions.create());
    }

    /**
     * Emit a sensor event via the Attune API using a typed payload object.
     *
     * <p>The object is serialized to a map using Jackson. Records, POJOs, and any
     * Jackson-serializable objects are supported.
     *
     * @param payload the event payload object
     * @return the event ID if successfully posted, or null on failure
     */
    public Integer emitTyped(Object payload) {
        return emitTyped(payload, EmitOptions.create());
    }

    /**
     * Emit a sensor event via the Attune API using a typed payload object with options.
     *
     * <p>The object is serialized to a map using Jackson. Records, POJOs, and any
     * Jackson-serializable objects are supported.
     *
     * @param payload the event payload object
     * @param options emission options (rule, triggerRef, targetRule)
     * @return the event ID if successfully posted, or null on failure
     */
    @SuppressWarnings("unchecked")
    public Integer emitTyped(Object payload, EmitOptions options) {
        Map<String, Object> payloadMap = MAPPER.convertValue(payload, MAP_TYPE);
        return emit(payloadMap, options);
    }

    /**
     * Emit a sensor event via the Attune API with options.
     *
     * @param payload the event payload
     * @param options emission options (rule, triggerRef, targetRule)
     * @return the event ID if successfully posted, or null on failure
     */
    public Integer emit(Map<String, Object> payload, EmitOptions options) {
        RuleState rule = options.rule();
        String resolvedTriggerRef = options.triggerRef();
        if (resolvedTriggerRef == null && rule != null) {
            resolvedTriggerRef = rule.triggerRef();
        }
        if (resolvedTriggerRef == null || resolvedTriggerRef.isEmpty()) {
            resolvedTriggerRef = context.sensorRef();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trigger_ref", resolvedTriggerRef);
        body.put("payload", payload);
        body.put("source", context.sensorRef());
        if (rule != null) {
            body.put("trigger_instance_id", triggerInstanceId(rule));
            if (options.targetRule()) {
                body.put("rule_ref", rule.ruleRef());
            }
        }

        try {
            return doEmit(body);
        } catch (Exception e) {
            logger.warn("Transport error, retrying: {}", e.getMessage());
            try {
                return doEmit(body);
            } catch (Exception retryEx) {
                logger.error("Failed to emit event after retry: {}", retryEx.getMessage());
                return null;
            }
        }
    }

    static String triggerInstanceId(RuleState rule) {
        return "rule_" + rule.ruleId();
    }

    @SuppressWarnings("unchecked")
    private Integer doEmit(Map<String, Object> body) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(context.apiUrl() + "/api/v1/events"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + context.apiToken())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.error("Failed to emit event: HTTP {}", response.statusCode());
            return null;
        }

        Map<String, Object> respBody = MAPPER.readValue(response.body(), MAP_TYPE);
        Map<String, Object> data = (Map<String, Object>) respBody.get("data");
        if (data != null && data.get("id") != null) {
            Integer eventId = ((Number) data.get("id")).intValue();
            logger.debug("Event emitted, id={}", eventId);
            return eventId;
        }
        return null;
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }
        return httpClient;
    }

    // ------------------------------------------------------------------
    // Rule management
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    void handleNotifierEnvelope(Map<String, Object> envelope) {
        String type = stringValue(envelope.get("type"));
        if ("welcome".equals(type)) {
            return;
        }
        if ("error".equals(type)) {
            logger.warn("Notifier websocket error frame: {}", envelope.getOrDefault("message", envelope));
            return;
        }

        Map<String, Object> payload;
        if ("notification".equals(type)) {
            Object rawPayload = envelope.get("payload");
            if (!(rawPayload instanceof Map<?, ?> rawMap)) {
                return;
            }
            payload = (Map<String, Object>) rawMap;
        } else if (envelope.containsKey("event_type")) {
            payload = envelope;
        } else {
            return;
        }

        handleRuleMessage(payload);
    }

    void handleNotifierText(String text) {
        try {
            handleNotifierEnvelope(MAPPER.readValue(text, MAP_TYPE));
        } catch (Exception e) {
            logger.warn("Invalid notifier websocket message: {}", e.getMessage());
        }
    }

    void handleRuleMessage(Map<String, Object> message) {
        String eventType = normalizeRuleEventType(stringValue(message.get("event_type")));
        if (eventType == null) {
            return;
        }

        Integer ruleId = intValue(message.get("rule_id"));
        if (ruleId == null) {
            return;
        }

        boolean active = booleanValue(message.get("active"), true);
        String incomingRuleRef = stringValue(message.get("rule_ref"));
        String incomingTriggerRef = firstNonBlank(
                stringValue(message.get("trigger_ref")),
                stringValue(message.get("trigger_type"))
        );
        Map<String, Object> incomingTriggerParams = mapValue(message.get("trigger_params"));
        if (incomingTriggerRef != null && !incomingTriggerRef.isBlank()) {
            managedTriggerRefs.add(incomingTriggerRef);
        }

        RuleState existing = rules.get(ruleId);
        String ruleRef = firstNonBlank(incomingRuleRef, existing != null ? existing.ruleRef() : null, "rule_" + ruleId);
        String triggerRef = firstNonBlank(incomingTriggerRef, existing != null ? existing.triggerRef() : null, "");
        Map<String, Object> triggerParams = !incomingTriggerParams.isEmpty()
                ? incomingTriggerParams
                : existing != null ? existing.triggerParams() : Collections.emptyMap();

        switch (eventType) {
            case "RuleCreated" -> {
                RuleState rule = new RuleState(ruleId, ruleRef, triggerRef, triggerParams, active);
                rules.put(ruleId, rule);

                if (existing != null && !existing.triggerParams().equals(triggerParams)) {
                    onRuleUpdated(rule, existing.triggerParams());
                } else if (existing != null && !existing.enabled() && active) {
                    onRuleEnabled(rule);
                } else if (existing != null && existing.enabled() && !active) {
                    onRuleDisabled(rule);
                } else if (active) {
                    onRuleCreated(rule);
                }
            }
            case "RuleEnabled" -> {
                RuleState rule = new RuleState(ruleId, ruleRef, triggerRef, triggerParams, true);
                rules.put(ruleId, rule);

                if (existing != null && !existing.triggerParams().equals(triggerParams)) {
                    onRuleUpdated(rule, existing.triggerParams());
                } else if (existing != null) {
                    onRuleEnabled(rule);
                } else {
                    onRuleCreated(rule);
                }
            }
            case "RuleDisabled" -> {
                RuleState disabled = new RuleState(ruleId, ruleRef, triggerRef, triggerParams, false);
                rules.put(ruleId, disabled);
                if (existing != null) {
                    onRuleDisabled(disabled);
                }
            }
            case "RuleDeleted" -> {
                RuleState rule = rules.remove(ruleId);
                if (rule != null) {
                    onRuleDeleted(rule);
                }
            }
            case "RuleUpdated" -> {
                RuleState updated = new RuleState(
                        ruleId,
                        ruleRef,
                        triggerRef,
                        triggerParams,
                        existing == null || existing.enabled()
                );
                rules.put(ruleId, updated);
                if (existing != null) {
                    if (!existing.triggerParams().equals(triggerParams)) {
                        onRuleUpdated(updated, existing.triggerParams());
                    }
                } else if (updated.enabled()) {
                    onRuleCreated(updated);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void bootstrapRules() {
        String raw = System.getenv("ATTUNE_SENSOR_TRIGGERS");
        if (raw == null || raw.isBlank()) raw = "[]";

        List<Map<String, Object>> triggers;
        try {
            triggers = MAPPER.readValue(raw, LIST_MAP_TYPE);
        } catch (Exception e) {
            triggers = Collections.emptyList();
        }

        for (Map<String, Object> item : triggers) {
            Object ruleId = item.get("id");
            if (ruleId == null) ruleId = item.get("rule_id");
            if (ruleId == null) continue;

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("event_type", "RuleCreated");
            msg.put("rule_id", ruleId);
            msg.put("rule_ref", item.getOrDefault("ref", item.getOrDefault("rule_ref", "rule_" + ruleId)));
            msg.put("trigger_ref", item.getOrDefault("trigger_ref", ""));
            msg.put("trigger_params", item.getOrDefault("config", item.getOrDefault("trigger_params", Collections.emptyMap())));
            msg.put("active", item.getOrDefault("active", true));
            handleRuleMessage(msg);
        }
    }

    // ------------------------------------------------------------------
    // Notifier lifecycle stream
    // ------------------------------------------------------------------

    private Thread startLifecycleStream() {
        if (managedTriggerRefs.isEmpty()) {
            logger.info("Skipping notifier websocket listener; no managed trigger refs from ATTUNE_SENSOR_TRIGGERS");
            return null;
        }
        if (context.notifierWsUrl().isBlank()) {
            logger.warn("Skipping notifier websocket listener; ATTUNE_NOTIFIER_WS_URL is not set");
            return null;
        }

        Thread thread = new Thread(this::lifecycleConsumeLoop, "notifier-websocket-listener");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void lifecycleConsumeLoop() {
        while (!isShuttingDown()) {
            CountDownLatch closed = new CountDownLatch(1);
            LifecycleWebSocketListener listener = new LifecycleWebSocketListener(closed);
            WebSocket webSocket = null;

            try {
                String token = context.apiToken();
                if (token.isBlank()) {
                    logger.warn("Notifier websocket token unavailable; retrying in 5s");
                    sleep(5000);
                    continue;
                }

                webSocket = getHttpClient().newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .buildAsync(URI.create(context.notifierWsUrl()), listener)
                        .join();
                lifecycleSocket = webSocket;

                for (String triggerRef : new TreeSet<>(managedTriggerRefs)) {
                    subscribeToTriggerRef(webSocket, triggerRef);
                }
                logger.info("Notifier websocket connected, trigger_refs={}", new TreeSet<>(managedTriggerRefs));

                while (!isShuttingDown() && closed.getCount() > 0) {
                    if (closed.await(1, TimeUnit.SECONDS)) {
                        break;
                    }
                }
            } catch (Exception e) {
                if (!isShuttingDown()) {
                    logger.warn("Notifier websocket error, retrying in 5s: {}", e.getMessage());
                }
            } finally {
                if (webSocket != null) {
                    try {
                        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
                    } catch (Exception ignored) {
                        webSocket.abort();
                    }
                }
                if (lifecycleSocket == webSocket) {
                    lifecycleSocket = null;
                }
                listener.close();
            }

            if (!isShuttingDown()) {
                sleep(5000);
            }
        }
    }

    private void subscribeToTriggerRef(WebSocket webSocket, String triggerRef) {
        if (triggerRef == null || triggerRef.isBlank()) {
            return;
        }

        try {
            String subscribeMessage = MAPPER.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "filter", "trigger_ref:" + triggerRef
            ));
            webSocket.sendText(subscribeMessage, true).join();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to subscribe to trigger_ref:" + triggerRef, e);
        }
    }

    private void closeLifecycleSocket() {
        WebSocket webSocket = lifecycleSocket;
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
        } catch (Exception ignored) {
            webSocket.abort();
        } finally {
            if (lifecycleSocket == webSocket) {
                lifecycleSocket = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Signal handling & lifecycle
    // ------------------------------------------------------------------

    /** Programmatically request sensor shutdown. */
    public void shutdown() {
        shutdownRequested.set(true);
        closeLifecycleSocket();
    }

    /**
     * Execute the full sensor lifecycle (called by {@link Attune#runSensor(Class)}).
     */
    int runLifecycle() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook triggered");
            shutdownRequested.set(true);
            closeLifecycleSocket();
        }));

        try {
            bootstrapRules();
            setup();
            startLifecycleStream();
            logger.info("Sensor started, active_rules={}", rules.size());
            run();
        } catch (Exception e) {
            logger.error("Sensor error: {}", e.getMessage(), e);
            return 1;
        } finally {
            shutdownRequested.set(true);
            closeLifecycleSocket();
            try {
                cleanup();
            } catch (Exception e) {
                logger.error("Cleanup error: {}", e.getMessage(), e);
            }
            logger.info("Sensor stopped");
        }

        return 0;
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    /** Sleep for the given milliseconds, returning early if shutting down. */
    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String normalizeRuleEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        return switch (eventType) {
            case "rule.created", "RuleCreated" -> "RuleCreated";
            case "rule.enabled", "RuleEnabled" -> "RuleEnabled";
            case "rule.disabled", "RuleDisabled" -> "RuleDisabled";
            case "rule.deleted", "RuleDeleted" -> "RuleDeleted";
            case "rule.updated", "RuleUpdated" -> "RuleUpdated";
            default -> null;
        };
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            return (Map<String, Object>) rawMap;
        }
        return Collections.emptyMap();
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return defaultValue;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return values.length > 0 ? values[values.length - 1] : null;
    }

    private final class LifecycleWebSocketListener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final StringBuilder textBuffer = new StringBuilder();

        private LifecycleWebSocketListener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                handleNotifierText(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!isShuttingDown()) {
                logger.warn("Notifier websocket listener error: {}", error.getMessage());
            }
            closed.countDown();
        }

        private void close() {
            closed.countDown();
        }
    }
}
