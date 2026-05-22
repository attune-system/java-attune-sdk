package io.attune;

/**
 * Options for event emission from sensors.
 */
public class EmitOptions {

    private RuleState rule;
    private String triggerRef;
    private boolean targetRule;

    private EmitOptions() {}

    public static EmitOptions create() {
        return new EmitOptions();
    }

    /** Associate this emission with a specific rule. */
    public EmitOptions rule(RuleState rule) {
        this.rule = rule;
        return this;
    }

    /** Override the trigger reference. */
    public EmitOptions triggerRef(String triggerRef) {
        this.triggerRef = triggerRef;
        return this;
    }

    /** When true and a rule is provided, target only that specific rule. */
    public EmitOptions targetRule(boolean targetRule) {
        this.targetRule = targetRule;
        return this;
    }

    public RuleState rule() { return rule; }
    public String triggerRef() { return triggerRef; }
    public boolean targetRule() { return targetRule; }
}
