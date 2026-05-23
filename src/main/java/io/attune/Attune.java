package io.attune;

import java.util.Map;
import java.util.function.Function;

/**
 * Main entry point for the Attune Java SDK.
 *
 * <p>Provides static methods for running actions and sensors, and accessing context singletons.
 *
 * <h2>Actions</h2>
 * <pre>{@code
 * import io.attune.Attune;
 * import java.util.Map;
 *
 * public class MyAction {
 *     public static void main(String[] args) {
 *         Attune.runAction(params -> {
 *             String name = (String) params.get("name");
 *             int count = ((Number) params.getOrDefault("count", 1)).intValue();
 *             return Map.of("greeting", "Hello, " + name + "!".repeat(count));
 *         });
 *     }
 * }
 * }</pre>
 *
 * <h2>Sensors</h2>
 * <pre>{@code
 * import io.attune.*;
 * import java.util.Map;
 *
 * public class MySensor extends PollingSensor {
 *     { interval = 5000; }
 *
 *     @Override
 *     public void poll(RuleState rule) {
 *         emit(Map.of("value", 42), EmitOptions.create().rule(rule));
 *     }
 *
 *     public static void main(String[] args) {
 *         Attune.runSensor(MySensor.class);
 *     }
 * }
 * }</pre>
 */
public final class Attune {

    private Attune() {}

    // ------------------------------------------------------------------
    // Context access
    // ------------------------------------------------------------------

    /** Returns the action execution context singleton. */
    public static ActionContext context() {
        return ActionContext.instance();
    }

    /** Returns the sensor execution context singleton. */
    public static SensorContext sensorContext() {
        return SensorContext.instance();
    }

    // ------------------------------------------------------------------
    // Action entry point
    // ------------------------------------------------------------------

    /**
     * Run an action entrypoint with automatic parameter parsing and output handling.
     *
     * <p>Reads JSON parameters from stdin, passes them to the function, and writes
     * the result as JSON to stdout. Exits with code 0 on success, 1 on failure.
     *
     * @param entrypoint function that receives a params map and returns a result object
     */
    public static void runAction(Function<Map<String, Object>, Object> entrypoint) {
        ActionRunner.run(entrypoint);
    }

    /**
     * Run an action entrypoint with configurable exception handling.
     *
     * @param entrypoint function that receives a params map and returns a result object
     * @param catchExceptions if false, exceptions propagate instead of being reported as JSON
     */
    public static void runAction(Function<Map<String, Object>, Object> entrypoint, boolean catchExceptions) {
        ActionRunner.run(entrypoint, catchExceptions);
    }

    /**
     * Run a typed action entrypoint with automatic parameter deserialization.
     *
     * <p>Reads JSON parameters from stdin, deserializes them into the given type,
     * passes the typed object to the function, and writes the result as JSON to stdout.
     *
     * <p>Example:
     * <pre>{@code
     * record MyParams(String name, int count) {}
     *
     * Attune.runAction(MyParams.class, params -> {
     *     return Map.of("greeting", "Hello, " + params.name() + "!".repeat(params.count()));
     * });
     * }</pre>
     *
     * @param paramsType the class to deserialize input params into
     * @param entrypoint function that receives a typed params object and returns a result
     * @param <T> the params type
     */
    public static <T> void runAction(Class<T> paramsType, Function<T, Object> entrypoint) {
        ActionRunner.run(paramsType, entrypoint);
    }

    /**
     * Run a typed action entrypoint with configurable exception handling.
     *
     * @param paramsType the class to deserialize input params into
     * @param entrypoint function that receives a typed params object and returns a result
     * @param catchExceptions if false, exceptions propagate instead of being reported as JSON
     * @param <T> the params type
     */
    public static <T> void runAction(Class<T> paramsType, Function<T, Object> entrypoint, boolean catchExceptions) {
        ActionRunner.run(paramsType, entrypoint, catchExceptions);
    }

    // ------------------------------------------------------------------
    // Sensor entry point
    // ------------------------------------------------------------------

    /**
     * Instantiate and run a sensor. This method blocks until the sensor shuts down.
     *
     * @param sensorClass the sensor class to instantiate and run
     */
    public static void runSensor(Class<? extends Sensor> sensorClass) {
        try {
            Sensor sensor = sensorClass.getDeclaredConstructor().newInstance();
            int exitCode = sensor.runLifecycle();
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Failed to instantiate sensor: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Run a pre-constructed sensor instance. This method blocks until the sensor shuts down.
     *
     * @param sensor the sensor instance to run
     */
    public static void runSensor(Sensor sensor) {
        int exitCode = sensor.runLifecycle();
        System.exit(exitCode);
    }
}
