package io.attune;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sensor context available during sensor execution.
 *
 * <p>Base configuration is built once from environment variables and cached for the
 * lifetime of the process. The API token source is mutable for managed sensors so
 * token rotation can be observed without process restarts.
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
    private final String notifierWsUrl;
    private final String logLevel;
    private final String packRef;
    private final Map<String, String> config;
    private final String configuredApiTokenStatePath;

    private volatile SensorTokenProvider tokenProvider;
    private volatile AttuneClient client;

    private SensorContext(String sensorRef, String sensorId, String apiUrl,
                          String notifierWsUrl,
                          String logLevel, String packRef, Map<String, String> config) {
        this.sensorRef = sensorRef;
        this.sensorId = sensorId;
        this.apiUrl = apiUrl;
        this.notifierWsUrl = notifierWsUrl;
        this.logLevel = logLevel;
        this.packRef = packRef;
        this.config = Collections.unmodifiableMap(config);
        String initialToken = env("ATTUNE_API_TOKEN", "");
        String initialExpiresAt = blankToNull(System.getenv("ATTUNE_API_TOKEN_EXPIRES_AT"));
        String tokenStatePath = blankToNull(System.getenv("ATTUNE_SENSOR_TOKEN_STATE_PATH"));
        this.configuredApiTokenStatePath = tokenStatePath;
        this.tokenProvider = buildTokenProvider(initialToken, initialExpiresAt, tokenStatePath);
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
                env("ATTUNE_NOTIFIER_WS_URL", "ws://localhost:8081/ws"),
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

    /**
     * The current sensor-scoped API token.
     *
     * <p>For managed sensors this may change over time when a mutable token
     * provider is configured.
     */
    public String apiToken() { return tokenState().token(); }

    /** Optional expiry timestamp metadata for the current sensor token. */
    public Optional<String> apiTokenExpiresAt() { return Optional.ofNullable(tokenState().expiresAt()); }

    /** Returns the current token state snapshot from the configured provider. */
    public SensorTokenState tokenState() {
        SensorTokenState state = tokenProvider.currentTokenState();
        return state != null ? state : new SensorTokenState("", null);
    }

    /** Returns true when the current token provider yields a token. */
    public boolean hasApiToken() {
        return tokenState().hasToken();
    }

    /** Returns the current token provider. */
    public SensorTokenProvider tokenProvider() { return tokenProvider; }

    /** Returns the optional runtime token-state file path from environment. */
    public Optional<String> configuredApiTokenStatePath() {
        return Optional.ofNullable(configuredApiTokenStatePath);
    }

    /**
     * Replace the managed sensor token provider.
     *
     * <p>This enables runtime-driven token rotation without process restart.
     */
    public void setTokenProvider(SensorTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider != null
                ? tokenProvider
                : () -> new SensorTokenState("", null);
    }

    /** The notifier WebSocket URL used for managed sensor lifecycle updates. */
    public String notifierWsUrl() { return notifierWsUrl; }

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
                    client = new AttuneClient(apiUrl, this::apiToken);
                }
            }
        }
        return client;
    }

    private static SensorTokenProvider buildTokenProvider(
            String initialToken,
            String initialExpiresAt,
            String tokenStatePath
    ) {
        SensorTokenState fallback = new SensorTokenState(initialToken, initialExpiresAt);
        if (tokenStatePath == null) {
            return () -> fallback;
        }
        return new FileSensorTokenProvider(Path.of(tokenStatePath), fallback);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
