package io.attune;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable context available during sensor execution.
 *
 * <p>Built once at class-load time from environment variables and cached for the
 * lifetime of the process.
 *
 * <p>Usage:
 * <pre>{@code
 * SensorContext ctx = Attune.sensorContext();
 * System.out.println(ctx.sensorRef());
 * Map<String, String> config = ctx.config();
 * }</pre>
 */
public final class SensorContext {

    private static final SensorContext INSTANCE = buildFromEnv();

    private final String sensorRef;
    private final String sensorId;
    private final String apiUrl;
    private final String apiToken;
    private final String mqUrl;
    private final String mqExchange;
    private final String logLevel;
    private final String packRef;
    private final Map<String, String> config;

    private volatile AttuneClient client;

    private SensorContext(String sensorRef, String sensorId, String apiUrl,
                          String apiToken, String mqUrl, String mqExchange,
                          String logLevel, String packRef, Map<String, String> config) {
        this.sensorRef = sensorRef;
        this.sensorId = sensorId;
        this.apiUrl = apiUrl;
        this.apiToken = apiToken;
        this.mqUrl = mqUrl;
        this.mqExchange = mqExchange;
        this.logLevel = logLevel;
        this.packRef = packRef;
        this.config = Collections.unmodifiableMap(config);
    }

    static SensorContext instance() {
        return INSTANCE;
    }

    private static SensorContext buildFromEnv() {
        String sensorRef = env("ATTUNE_SENSOR_REF", "");
        String[] parts = sensorRef.split("\\.");
        String packRef = parts.length >= 2 ? parts[0] : "";

        String prefix = "ATTUNE_SENSOR_CONFIG_";
        Map<String, String> config = new HashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                config.put(entry.getKey().substring(prefix.length()).toLowerCase(), entry.getValue());
            }
        }

        return new SensorContext(
                sensorRef,
                env("ATTUNE_SENSOR_ID", "0"),
                env("ATTUNE_API_URL", "http://localhost:8080"),
                env("ATTUNE_API_TOKEN", ""),
                env("ATTUNE_MQ_URL", "amqp://localhost:5672"),
                env("ATTUNE_MQ_EXCHANGE", "attune"),
                env("ATTUNE_LOG_LEVEL", "info").toUpperCase(),
                packRef,
                config
        );
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    /** The sensor reference (e.g., {@code mypack.my_sensor}). */
    public String sensorRef() { return sensorRef; }

    /** The sensor database ID. */
    public String sensorId() { return sensorId; }

    /** The Attune API base URL. */
    public String apiUrl() { return apiUrl; }

    /** The sensor-scoped API token. */
    public String apiToken() { return apiToken; }

    /** The RabbitMQ connection URL. */
    public String mqUrl() { return mqUrl; }

    /** The RabbitMQ exchange name. */
    public String mqExchange() { return mqExchange; }

    /** The configured log level. */
    public String logLevel() { return logLevel; }

    /** The pack reference derived from sensorRef. */
    public String packRef() { return packRef; }

    /** Sensor-specific config from {@code ATTUNE_SENSOR_CONFIG_*} environment variables. */
    public Map<String, String> config() { return config; }

    /**
     * Returns a lazily-constructed {@link AttuneClient} using the sensor-scoped token.
     */
    public AttuneClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new AttuneClient(apiUrl, apiToken);
                }
            }
        }
        return client;
    }
}
