package io.attune;

/**
 * Snapshot of a managed sensor token state.
 *
 * @param token current sensor token value
 * @param expiresAt optional expiry timestamp (typically ISO-8601 UTC)
 */
public record SensorTokenState(String token, String expiresAt) {

    public SensorTokenState {
        token = token != null ? token : "";
        if (expiresAt != null && expiresAt.isBlank()) {
            expiresAt = null;
        }
    }

    /** Returns true when a non-blank token is available. */
    public boolean hasToken() {
        return !token.isBlank();
    }
}
