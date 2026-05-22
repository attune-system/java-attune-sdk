package io.attune;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Immutable context available during action execution.
 *
 * <p>Built once at class-load time from environment variables and cached for the
 * lifetime of the process (which is a single action execution).
 *
 * <p>Usage:
 * <pre>{@code
 * ActionContext ctx = Attune.context();
 * System.out.println(ctx.executionId());
 * if (ctx.hasApiToken()) {
 *     AttuneClient client = ctx.client();
 * }
 * }</pre>
 */
public final class ActionContext {

    private static final ActionContext INSTANCE = buildFromEnv();

    private final String actionRef;
    private final String packRef;
    private final String executionId;
    private final String apiUrl;
    private final String apiToken;
    private final Path artifactsDir;
    private final Path runtimeEnvsDir;
    private final String ruleRef;
    private final String triggerRef;

    private volatile AttuneClient client;

    private ActionContext(String actionRef, String packRef, String executionId,
                          String apiUrl, String apiToken, Path artifactsDir,
                          Path runtimeEnvsDir, String ruleRef, String triggerRef) {
        this.actionRef = actionRef;
        this.packRef = packRef;
        this.executionId = executionId;
        this.apiUrl = apiUrl;
        this.apiToken = apiToken;
        this.artifactsDir = artifactsDir;
        this.runtimeEnvsDir = runtimeEnvsDir;
        this.ruleRef = ruleRef;
        this.triggerRef = triggerRef;
    }

    static ActionContext instance() {
        return INSTANCE;
    }

    private static ActionContext buildFromEnv() {
        String artifacts = System.getenv("ATTUNE_ARTIFACTS_DIR");
        String runtimeEnvs = System.getenv("ATTUNE_RUNTIME_ENVS_DIR");
        return new ActionContext(
                env("ATTUNE_ACTION", ""),
                env("ATTUNE_PACK_REF", ""),
                env("ATTUNE_EXEC_ID", ""),
                env("ATTUNE_API_URL", "http://localhost:8080"),
                System.getenv("ATTUNE_API_TOKEN"),
                artifacts != null ? Path.of(artifacts) : null,
                runtimeEnvs != null ? Path.of(runtimeEnvs) : null,
                System.getenv("ATTUNE_RULE"),
                System.getenv("ATTUNE_TRIGGER")
        );
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    /** The action reference (e.g., {@code mypack.deploy}). */
    public String actionRef() { return actionRef; }

    /** The pack reference (e.g., {@code mypack}). */
    public String packRef() { return packRef; }

    /** The execution database ID. */
    public String executionId() { return executionId; }

    /** The Attune API base URL. */
    public String apiUrl() { return apiUrl; }

    /** The execution-scoped API token, or empty if not available. */
    public Optional<String> apiToken() { return Optional.ofNullable(apiToken); }

    /** Whether an execution-scoped API token is available. */
    public boolean hasApiToken() { return apiToken != null && !apiToken.isEmpty(); }

    /** Path to the shared artifact volume (if available). */
    public Optional<Path> artifactsDir() { return Optional.ofNullable(artifactsDir); }

    /** Path to the runtime environments root (if available). */
    public Optional<Path> runtimeEnvsDir() { return Optional.ofNullable(runtimeEnvsDir); }

    /** The rule reference (if triggered by a rule). */
    public Optional<String> ruleRef() { return Optional.ofNullable(ruleRef); }

    /** The trigger reference (if triggered by an event). */
    public Optional<String> triggerRef() { return Optional.ofNullable(triggerRef); }

    /**
     * Returns a lazily-constructed {@link AttuneClient} using the execution-scoped token.
     *
     * @throws IllegalStateException if no API token is available
     */
    public AttuneClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    if (!hasApiToken()) {
                        throw new IllegalStateException(
                                "No API token available. The action must have execution permission " +
                                "sets configured to receive an API token.");
                    }
                    client = new AttuneClient(apiUrl, apiToken);
                }
            }
        }
        return client;
    }
}
