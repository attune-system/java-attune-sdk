package io.attune;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * <p>Usage:
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
 * <p>Exit codes:
 * <ul>
 *   <li>0: success (result written to stdout as JSON)</li>
 *   <li>1: failure (error details written to stdout as JSON with {@code success: false})</li>
 * </ul>
 */
public final class ActionRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ActionRunner() {}

    /**
     * Read action parameters from stdin (JSON format).
     */
    public static Map<String, Object> readParams() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String raw = reader.lines().collect(Collectors.joining("\n")).trim();
            if (raw.isEmpty()) {
                return Collections.emptyMap();
            }
            return MAPPER.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
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

    /**
     * Run an action entrypoint with exception catching enabled (default behavior).
     *
     * @param entrypoint function that receives params map and returns a result object
     */
    public static void run(Function<Map<String, Object>, Object> entrypoint) {
        run(entrypoint, true);
    }
}
