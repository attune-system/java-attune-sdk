package io.attune;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Polling sensor — uses a {@link ScheduledExecutorService} to poll each active rule at a
 * configurable interval.
 *
 * <p>Example:
 * <pre>{@code
 * public class TemperatureSensor extends PollingSensor {
 *     { interval = 5000; }
 *
 *     @Override
 *     public void poll(RuleState rule) {
 *         String device = (String) rule.triggerParams().getOrDefault("device", "/dev/temp0");
 *         double temp = readTemperature(device);
 *         if (temp > 100) {
 *             emit(Map.of("temperature", temp, "alert", true), EmitOptions.create().rule(rule));
 *         }
 *     }
 * }
 * }</pre>
 */
public class PollingSensor extends Sensor {

    /** Default polling interval in milliseconds. Override or set in {@link #setup()}. */
    protected long interval = 5000;

    private final ConcurrentHashMap<Integer, ScheduledFuture<?>> pollTimers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Override
    public void setup() {
        scheduler = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r, "poll-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Called periodically for each active rule. Override to check for events.
     *
     * @param rule the rule being polled
     */
    public void poll(RuleState rule) {}

    /**
     * Get the polling interval for a specific rule. Checks trigger params for
     * "interval", "interval_seconds", or "poll_interval" keys.
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

    private void startPollTimer(RuleState rule) {
        stopPollTimer(rule.ruleId());
        long intervalMs = getRuleInterval(rule);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (isShuttingDown()) {
                stopPollTimer(rule.ruleId());
                return;
            }
            RuleState current = rules().get(rule.ruleId());
            if (current == null || !current.enabled()) return;
            try {
                poll(current);
            } catch (Exception e) {
                logger.error("Poll error for rule {}: {}", current.ruleRef(), e.getMessage(), e);
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        pollTimers.put(rule.ruleId(), future);
    }

    private void stopPollTimer(int ruleId) {
        ScheduledFuture<?> future = pollTimers.remove(ruleId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void onRuleCreated(RuleState rule) {
        startPollTimer(rule);
    }

    @Override
    public void onRuleEnabled(RuleState rule) {
        startPollTimer(rule);
    }

    @Override
    public void onRuleDisabled(RuleState rule) {
        stopPollTimer(rule.ruleId());
    }

    @Override
    public void onRuleDeleted(RuleState rule) {
        stopPollTimer(rule.ruleId());
    }

    @Override
    public void onRuleUpdated(RuleState rule, Map<String, Object> oldParams) {
        startPollTimer(rule);
    }

    @Override
    public void run() {
        while (!isShuttingDown()) {
            sleep(500);
        }
    }

    @Override
    public void cleanup() {
        for (int ruleId : pollTimers.keySet()) {
            stopPollTimer(ruleId);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
