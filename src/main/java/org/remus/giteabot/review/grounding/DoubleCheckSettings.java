package org.remus.giteabot.review.grounding;

import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamName;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Per-workflow settings; disputed or context-changed priors bypass new-finding selection. */
public record DoubleCheckSettings(boolean enabled, Severity minSeverity, Long aiIntegrationId) {

    public DoubleCheckSettings {
        Objects.requireNonNull(minSeverity, "minSeverity");
        if (aiIntegrationId != null && aiIntegrationId <= 0) aiIntegrationId = null;
    }

    public static DoubleCheckSettings defaults() {
        return new DoubleCheckSettings(true, Severity.LOW, null);
    }

    public static DoubleCheckSettings fromParams(Map<String, Object> params) {
        Object id = params.get("doubleCheckAiIntegrationId");
        Long integrationId = id == null || id.toString().isBlank() ? null : Long.valueOf(id.toString());
        return new DoubleCheckSettings(
                Boolean.parseBoolean(params.getOrDefault("doubleCheckEnabled", true).toString()),
                Severity.parse(params.getOrDefault("doubleCheckMinSeverity", "LOW").toString()), integrationId);
    }

    public boolean selects(String severity) {
        return enabled && Severity.parse(severity).ordinal() >= minSeverity.ordinal();
    }

    public static WorkflowParamField enabledField(WorkflowParamName name) {
        return new WorkflowParamField(name, "Enable finding double-check", WorkflowParamField.ParamType.BOOLEAN,
                false, "true", "Verify new findings with AI. Disputed or context-changed priors are always checked.");
    }

    public static WorkflowParamField severityField(WorkflowParamName name) {
        return new WorkflowParamField(name, "Minimum double-check severity", false, "LOW",
                "New findings below this severity only require deterministic quote verification.",
                Arrays.stream(Severity.values()).map(value ->
                        new WorkflowParamField.EnumOption(value.name(), value.name(), null)).toList());
    }

    public static WorkflowParamField integrationField(WorkflowParamName name) {
        return new WorkflowParamField(name, "Double-check AI integration ID", WorkflowParamField.ParamType.INTEGER,
                false, "0", "Optional second AI integration. Blank or 0 uses the review integration.");
    }

    public enum Severity {
        LOW, MEDIUM, HIGH, BLOCKER;

        public static Severity parse(String value) {
            if (value == null || value.isBlank()) return LOW;
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return LOW;
            }
        }
    }
}
