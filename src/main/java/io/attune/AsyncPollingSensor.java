package io.attune;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Async polling sensor — runs a dedicated thread per active rule with an async-style
 * loop (using {@link CompletableFuture} patterns).
 *
 * <p>Ideal for I/O-bound checks where each rule's poll may involve blocking I/O
 * (HTTP calls, database queries, etc.).
 *
 * <p>Example:
 * <pre>{@code
 * public class ApiSensor extends AsyncPollingSensor {
 *     { interval = 10000; }
 *
 *     @Override
 *     public void poll(RuleState rule) throws Exception {
 *         String url = (String) rule.triggerParams().get("url");
 *         // perform HTTP check...
 *         if (statusCode >= 500) {
 *             emit(Map.of("url", url, "status", statusCode), EmitOptions.create().rule(rule));
 *         }
 *     }
 * }
 * }</pre>
 */
public class AsyncPollingSensor extends Sensor {

    /** Default polling interval in milliseconds. Override or set in {@link #setup()}. */
    protected long interval = 5000;

    private final ConcurrentHashMap<Integer, Future<?>> pollTasks = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private volatile boolean running = false;

    @Override
    public void setup() {
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "async-poll-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Called periodically for each active rule. Override to check for events.
     *
     * @param rule the rule being polled
     * @throws Exception if an error occurs during polling
     */
    public void poll(RuleState rule) throws Exception {}

    /**
     * Get the polling interval for a specific rule.
     */
    protected long getRuleInterval(RuleState rule) {
        for (String key : new String[]{"interval", "interval_seconds", "poll_interval"}) {
            Object val = rule.triggerParams().get(key);
            if (val != null) {
                try {
                    return Long.parseLong(val.toString());
                } catch (NumberFormatException ignored) {}
            }
        }
        return interval;
    }

    private void startPollTask(RuleState rule) {
        if (!running) return;
        cancelPollTask(rule.ruleId());

        Future<?> future = executor.submit(() -> pollLoop(rule.ruleId()));
        pollTasks.put(rule.ruleId(), future);
    }

    private void pollLoop(int ruleId) {
        while (!isShuttingDown()) {
            RuleState rule = rules().get(ruleId);
            if (rule == null || !rule.enabled()) break;
            try {
                poll(rule);
            } catch (Exception e) {
                if (isShuttingDown()) break;
                logger.error("Poll error for rule {}: {}", rule.ruleRef(), e.getMessage(), e);
            }
            long intervalMs = getRuleInterval(rule);
            sleep(intervalMs);
        }
    }

    private void cancelPollTask(int ruleId) {
        Future<?> future = pollTasks.remove(ruleId);
        if (future != null) {
            future.cancel(true);
        }
    }

    @Override
    public void onRuleCreated(RuleState rule) {
        startPollTask(rule);
    }

    @Override
    public void onRuleEnabled(RuleState rule) {
        startPollTask(rule);
    }

    @Override
    public void onRuleDisabled(RuleState rule) {
        cancelPollTask(rule.ruleId());
    }

    @Override
    public void onRuleDeleted(RuleState rule) {
        cancelPollTask(rule.ruleId());
    }

    @Override
    public void onRuleUpdated(RuleState rule, Map<String, Object> oldParams) {
        startPollTask(rule);
    }

    @Override
    public void run() {
        running = true;
        // Start poll tasks for bootstrapped rules
        for (RuleState rule : rules().values()) {
            if (rule.enabled()) {
                startPollTask(rule);
            }
        }

        while (!isShuttingDown()) {
            sleep(500);
        }
    }

    @Override
    public void cleanup() {
        for (int ruleId : pollTasks.keySet()) {
            cancelPollTask(ruleId);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
