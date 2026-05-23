package io.attune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Action runner — handles stdin parameter parsing, output formatting, and error handling.
 *
 * <p>Usage with untyped params:
 * <pre>{@code
 * import io.attune.Attune;
 *
 * public class MyAction {
 *     public static void main(String[] args) {
 *         Attune.runAction(params -> {
 *             String name = (String) params.get("name");
 *             return Map.of("greeting", "Hello, " + name + "!");
 *         });
 *     }
 * }
 * }</pre>
 *
 * <p>Usage with typed params:
 * <pre>{@code
 * import io.attune.Attune;
 *
 * record MyParams(String name, int count) {}
 *
 * public class MyAction {
 *     public static void main(String[] args) {
 *         Attune.runAction(MyParams.class, params -> {
 *             return Map.of("greeting", "Hello, " + params.name() + "!".repeat(params.count()));
 *         });
 *     }
 * }
 * }</pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0: success (result written to stdout as JSON)</li>
 *   <li>1: failure (error details written to stdout as JSON with {@code success: false})</li>
 * </ul>
 */
public final class ActionRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ActionRunner() {}

    /**
     * Read action parameters from stdin (JSON format) as a map.
     */
    public static Map<String, Object> readParams() {
        try {
            String raw = readRawParams();
            if (raw.isEmpty()) {
                return Collections.emptyMap();
            }
            return MAPPER.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Read action parameters from stdin (JSON format) and deserialize into the given type.
     *
     * @param type the class to deserialize params into
     * @param <T> the params type
     * @return the deserialized params object
     */
    public static <T> T readParams(Class<T> type) {
        try {
            String raw = readRawParams();
            if (raw.isEmpty()) {
                return MAPPER.convertValue(Collections.emptyMap(), type);
            }
            return MAPPER.readValue(raw, type);
        } catch (Exception e) {
            return MAPPER.convertValue(Collections.emptyMap(), type);
        }
    }

    private static String readRawParams() {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return reader.lines().collect(Collectors.joining("\n")).trim();
    }

    /**
     * Write a JSON result to stdout.
     */
    public static void emitResult(Object payload) {
        try {
            System.out.println(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            System.out.println("{}");
        }
    }

    /**
     * Write a JSON error to stdout.
     */
    public static void emitError(String message, String details) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("success", false);
            payload.put("error", message);
            if (details != null) {
                payload.put("details", details);
            }
            System.out.println(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            System.out.println("{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
        }
    }

    /**
     * Run an action entrypoint with automatic parameter parsing and output handling.
     *
     * @param entrypoint function that receives params map and returns a result object
     * @param catchExceptions if true, uncaught exceptions are caught and reported as JSON errors
     */
    public static void run(Function<Map<String, Object>, Object> entrypoint, boolean catchExceptions) {
        try {
            Map<String, Object> params = readParams();
            Object result = entrypoint.apply(params);
            if (result == null) {
                result = Collections.emptyMap();
            }
            emitResult(result);
            System.exit(0);
        } catch (Exception e) {
            if (!catchExceptions) {
                throw new RuntimeException(e);
            }
            handleException(e);
        }
    }

    /**
     * Run an action entrypoint with exception catching enabled (default behavior).
     *
     * @param entrypoint function that receives params map and returns a result object
     */
    public static void run(Function<Map<String, Object>, Object> entrypoint) {
        run(entrypoint, true);
    }

    /**
     * Run a typed action entrypoint with automatic parameter deserialization and output handling.
     *
     * <p>Params are deserialized into the given type using Jackson. The return value can be
     * any Jackson-serializable object (record, POJO, Map, etc.).
     *
     * @param paramsType the class to deserialize input params into
     * @param entrypoint function that receives a typed params object and returns a result
     * @param catchExceptions if true, uncaught exceptions are caught and reported as JSON errors
     * @param <T> the params type
     */
    public static <T> void run(Class<T> paramsType, Function<T, Object> entrypoint, boolean catchExceptions) {
        try {
            T params = readParams(paramsType);
            Object result = entrypoint.apply(params);
            if (result == null) {
                result = Collections.emptyMap();
            }
            emitResult(result);
            System.exit(0);
        } catch (Exception e) {
            if (!catchExceptions) {
                throw new RuntimeException(e);
            }
            handleException(e);
        }
    }

    /**
     * Run a typed action entrypoint with exception catching enabled (default behavior).
     *
     * @param paramsType the class to deserialize input params into
     * @param entrypoint function that receives a typed params object and returns a result
     * @param <T> the params type
     */
    public static <T> void run(Class<T> paramsType, Function<T, Object> entrypoint) {
        run(paramsType, entrypoint, true);
    }

    private static void handleException(Exception e) {
        String details = null;
        if (System.console() != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            details = sw.toString();
        }
        emitError(e.getMessage(), details);
        System.exit(1);
    }
}
