package mp4.socllm;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import mp4.maintainer.summary.SummaryContracts.AdmissionLease;

/** Role/task and admission contracts for trusted MP4 backend callers. */
public final class SocLlmContracts {
    private SocLlmContracts() {}

    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");

    /**
     * Canonical form shared by the HTTP Authorization header and durable quota identity.
     * Only outer whitespace is normalized; remaining characters must be valid bearer-token
     * characters so HttpHeaders cannot silently trim or reinterpret a configured key.
     */
    public static String canonicalApiKey(String value) {
        if (value == null) throw new IllegalArgumentException("SoCLaaS API key is required.");
        String canonical = value.strip();
        if (canonical.isEmpty() || !BEARER_TOKEN.matcher(canonical).matches()) {
            throw new IllegalArgumentException("SoCLaaS API key is invalid.");
        }
        return canonical;
    }

    public static boolean isValidApiKey(String value) {
        try {
            canonicalApiKey(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public enum Role { REPORTER, DEVELOPER, MAINTAINER }

    public enum Task {
        REPORTER_ASSIST(Role.REPORTER),
        DEVELOPER_ASSIST(Role.DEVELOPER),
        MAINTAINER_QUEUE_SUMMARY(Role.MAINTAINER);

        private final Role role;
        Task(Role role) { this.role = role; }
        public Role role() { return role; }
    }

    /** Actor, role and task metadata comes from a trusted backend caller, never request JSON. */
    public record Call(Role role, UUID actorId, Task task, String instructions, String input,
            int outputTokens, Duration deadline) {
        public Call {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(instructions, "instructions");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(deadline, "deadline");
            if (task.role() != role || instructions.isBlank() || input.isBlank()
                    || outputTokens < 1 || deadline.isNegative() || deadline.isZero()) {
                throw new IllegalArgumentException("Invalid role AI call.");
            }
        }

        @Override public String toString() {
            return "Call[role=" + role + ", task=" + task + ", content=<redacted>]";
        }
    }

    public interface RoleGateway { String generate(Call call); }

    public interface RoleAdmission {
        AdmissionLease acquire(Role role, UUID actor, Task task, int reservedTokens, Duration lease);
    }
}
