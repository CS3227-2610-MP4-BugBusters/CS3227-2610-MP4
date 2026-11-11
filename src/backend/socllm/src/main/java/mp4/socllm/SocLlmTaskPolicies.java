package mp4.socllm;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import mp4.socllm.SocLlmContracts.Task;

/** Immutable registry holding exactly one limit policy for every registered task. */
public final class SocLlmTaskPolicies {
    private static final SocLlmTaskPolicies DEFAULTS = createDefaults();

    private final Map<Task, SocLlmTaskPolicy> policies;

    private SocLlmTaskPolicies(Map<Task, SocLlmTaskPolicy> policies) {
        this.policies = policies;
    }

    /** Every task with the initial limits. */
    public static SocLlmTaskPolicies defaults() {
        return DEFAULTS;
    }

    /** Copies the given policies; fails if any task is missing or any policy is null. */
    public static SocLlmTaskPolicies of(Map<Task, SocLlmTaskPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        EnumMap<Task, SocLlmTaskPolicy> copy = new EnumMap<>(Task.class);
        for (Task task : Task.values()) {
            SocLlmTaskPolicy policy = policies.get(task);
            if (policy == null) throw new IllegalArgumentException("Missing SoCLaaS policy for task " + task + ".");
            copy.put(task, policy);
        }
        return new SocLlmTaskPolicies(Collections.unmodifiableMap(copy));
    }

    public SocLlmTaskPolicy forTask(Task task) {
        return policies.get(Objects.requireNonNull(task, "task"));
    }

    private static SocLlmTaskPolicies createDefaults() {
        EnumMap<Task, SocLlmTaskPolicy> initial = new EnumMap<>(Task.class);
        for (Task task : Task.values()) initial.put(task, SocLlmTaskPolicy.initial());
        return of(initial);
    }
}
