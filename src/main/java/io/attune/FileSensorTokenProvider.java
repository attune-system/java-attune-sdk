package io.attune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * File-backed token provider for managed sensor token rotation.
 *
 * <p>Expected JSON shape:
 * <pre>{@code
 * {
 *   "token": "eyJ...",
 *   "expires_at": "2026-12-31T00:00:00Z"
 * }
 * }</pre>
 *
 * <p>For compatibility, {@code api_token} and {@code token_expires_at} are
 * also accepted field names.
 */
public final class FileSensorTokenProvider implements SensorTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path statePath;
    private final SensorTokenState fallbackState;

    public FileSensorTokenProvider(Path statePath) {
        this(statePath, null);
    }

    public FileSensorTokenProvider(Path statePath, SensorTokenState fallbackState) {
        this.statePath = Objects.requireNonNull(statePath, "statePath cannot be null");
        this.fallbackState = fallbackState;
    }

    @Override
    public SensorTokenState currentTokenState() {
        try {
            return readState();
        } catch (Exception e) {
            if (fallbackState != null && fallbackState.hasToken()) {
                return fallbackState;
            }
            throw new IllegalStateException(
                    "Unable to read sensor token state from " + statePath + ": " + e.getMessage(),
                    e
            );
        }
    }

    private SensorTokenState readState() throws IOException {
        if (!Files.exists(statePath)) {
            throw new IOException("state file does not exist");
        }

        Map<String, Object> body = MAPPER.readValue(Files.readAllBytes(statePath), MAP_TYPE);
        String token = firstNonBlank(
                stringValue(body.get("token")),
                stringValue(body.get("api_token"))
        );
        if (token == null || token.isBlank()) {
            throw new IOException("state file does not contain a token");
        }

        String expiresAt = firstNonBlank(
                stringValue(body.get("expires_at")),
                stringValue(body.get("token_expires_at"))
        );
        return new SensorTokenState(token, expiresAt);
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
