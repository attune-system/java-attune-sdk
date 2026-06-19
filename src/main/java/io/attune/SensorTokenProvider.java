package io.attune;

/**
 * Resolves the current managed sensor token state.
 *
 * <p>Implementations may read from process state, a runtime-managed file,
 * or any other external source that can rotate token values over time.
 */
@FunctionalInterface
public interface SensorTokenProvider {

    /** Returns the current token state. */
    SensorTokenState currentTokenState();
}
