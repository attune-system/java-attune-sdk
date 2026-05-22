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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>Optional RabbitMQ consumer for rule lifecycle events</li>
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
    private volatile HttpClient httpClient;

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
     * @param payload the event payload
     * @return the event ID if successfully posted, or null on failure
     */
    public Integer emit(Map<String, Object> payload) {
        return emit(payload, EmitOptions.create());
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
            body.put("trigger_instance_id", "rule_" + rule.ruleRef());
            if (options.targetRule()) {
                body.put("rule_ref", rule.ruleRef());
            }
        }

        try {
            return doEmit(body);
        } catch (Exception e) {
            // Retry once on connection errors
            logger.warn("Transport error, retrying: {}", e.getMessage());
            try {
                return doEmit(body);
            } catch (Exception retryEx) {
                logger.error("Failed to emit event after retry: {}", retryEx.getMessage());
                return null;
            }
        }
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
    void handleRuleMessage(Map<String, Object> message) {
        String eventType = (String) message.getOrDefault("event_type", "");
        Object rawRuleId = message.get("rule_id");
        if (rawRuleId == null) return;

        int ruleId = ((Number) rawRuleId).intValue();
        String ruleRef = (String) message.getOrDefault("rule_ref", "rule_" + ruleId);
        String triggerRef = (String) message.getOrDefault("trigger_ref",
                (String) message.getOrDefault("trigger_type", ""));
        Map<String, Object> triggerParams = (Map<String, Object>) message.getOrDefault("trigger_params", Collections.emptyMap());

        switch (eventType) {
            case "RuleCreated", "RuleEnabled" -> {
                RuleState rule = new RuleState(ruleId, ruleRef, triggerRef, triggerParams, true);
                RuleState existing = rules.get(ruleId);
                rules.put(ruleId, rule);

                if (existing != null && !existing.triggerParams().equals(triggerParams)) {
                    onRuleUpdated(rule, existing.triggerParams());
                } else if ("RuleEnabled".equals(eventType) && existing != null) {
                    onRuleEnabled(rule);
                } else {
                    onRuleCreated(rule);
                }
            }
            case "RuleDisabled" -> {
                RuleState rule = rules.get(ruleId);
                if (rule != null) {
                    rules.put(ruleId, rule.withEnabled(false));
                    onRuleDisabled(rule);
                }
            }
            case "RuleDeleted" -> {
                RuleState rule = rules.remove(ruleId);
                if (rule != null) {
                    onRuleDeleted(rule);
                }
            }
            case "RuleUpdated" -> {
                RuleState existing = rules.get(ruleId);
                if (existing != null) {
                    Map<String, Object> oldParams = existing.triggerParams();
                    RuleState updated = existing.withTriggerParams(triggerParams);
                    rules.put(ruleId, updated);
                    if (!oldParams.equals(triggerParams)) {
                        onRuleUpdated(updated, oldParams);
                    }
                } else {
                    RuleState rule = new RuleState(ruleId, ruleRef, triggerRef, triggerParams, true);
                    rules.put(ruleId, rule);
                    onRuleCreated(rule);
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
            handleRuleMessage(msg);
        }
    }

    // ------------------------------------------------------------------
    // MQ consumer (optional)
    // ------------------------------------------------------------------

    private Thread startMqConsumer() {
        String mqUrl = System.getenv("ATTUNE_MQ_URL");
        if (mqUrl == null || mqUrl.isEmpty()) return null;

        // Check if RabbitMQ client is available
        try {
            Class.forName("com.rabbitmq.client.ConnectionFactory");
        } catch (ClassNotFoundException e) {
            logger.error("RabbitMQ client library required for MQ rule lifecycle. " +
                    "Add com.rabbitmq:amqp-client to your dependencies.");
            return null;
        }

        Thread thread = new Thread(this::mqConsumeLoop, "mq-consumer");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void mqConsumeLoop() {
        String queueName = "sensor." + context.sensorRef();
        String[] routingKeys = {"rule.created", "rule.enabled", "rule.disabled", "rule.deleted", "rule.updated"};

        while (!isShuttingDown()) {
            try {
                var factory = new com.rabbitmq.client.ConnectionFactory();
                factory.setUri(context.mqUrl());
                factory.setRequestedHeartbeat(30);

                try (var connection = factory.newConnection();
                     var channel = connection.createChannel()) {

                    channel.exchangeDeclare(context.mqExchange(), "topic", true);
                    channel.queueDeclare(queueName, true, false, false, null);
                    for (String rk : routingKeys) {
                        channel.queueBind(queueName, context.mqExchange(), rk);
                    }

                    logger.info("MQ connected, queue={}", queueName);

                    var consumerTag = channel.basicConsume(queueName, false,
                            (tag, delivery) -> {
                                try {
                                    Map<String, Object> message = MAPPER.readValue(delivery.getBody(), MAP_TYPE);
                                    handleRuleMessage(message);
                                } catch (Exception e) {
                                    logger.warn("Invalid MQ message: {}", e.getMessage());
                                }
                                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            },
                            (tag) -> {});

                    // Wait until shutdown
                    while (!isShuttingDown()) {
                        sleep(1000);
                    }

                    channel.basicCancel(consumerTag);
                }
            } catch (Exception e) {
                logger.warn("MQ connection error, retrying in 5s: {}", e.getMessage());
                sleep(5000);
            }
        }
    }

    // ------------------------------------------------------------------
    // Signal handling & lifecycle
    // ------------------------------------------------------------------

    /** Programmatically request sensor shutdown. */
    public void shutdown() {
        shutdownRequested.set(true);
    }

    /**
     * Execute the full sensor lifecycle (called by {@link Attune#runSensor(Class)}).
     */
    int runLifecycle() {
        // Install shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook triggered");
            shutdownRequested.set(true);
        }));

        try {
            bootstrapRules();
            setup();
            startMqConsumer();
            logger.info("Sensor started, active_rules={}", rules.size());
            run();
        } catch (Exception e) {
            logger.error("Sensor error: {}", e.getMessage(), e);
            return 1;
        } finally {
            shutdownRequested.set(true);
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
}
