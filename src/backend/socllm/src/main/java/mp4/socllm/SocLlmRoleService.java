package mp4.socllm;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import mp4.maintainer.summary.SummaryContracts.AdmissionLease;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import mp4.socllm.SocLlmContracts.Role;
import mp4.socllm.SocLlmContracts.RoleAdmission;
import mp4.socllm.SocLlmContracts.Task;

/** Trusted backend entry for role-bound tasks, with a bounded worker pool and admission lease ownership. */
public final class SocLlmRoleService implements SocLlmContracts.RoleGateway, AutoCloseable {
    @FunctionalInterface interface ProviderCall {
        String generate(String instructions, String input, int outputTokens, Duration deadline);
    }

    private final Role role;
    private final RoleAdmission admissions;
    private final ProviderCall provider;
    private final SocLlmTaskPolicies policies;
    private final ThreadPoolExecutor workers;

    public SocLlmRoleService(Role role, String apiKey, String model, RoleAdmission admissions, Clock clock) {
        this(role, admissions, provider(apiKey, model, clock));
    }

    private static ProviderCall provider(String apiKey, String model, Clock clock) {
        String canonicalKey = SocLlmContracts.canonicalApiKey(apiKey);
        return (instructions, input, tokens, deadline) ->
                new SocLlmHttpProvider(canonicalKey, model, clock).generate(instructions, input, tokens, deadline);
    }

    SocLlmRoleService(Role role, RoleAdmission admissions, ProviderCall provider) {
        this(role, admissions, provider, SocLlmTaskPolicies.defaults());
    }

    SocLlmRoleService(Role role, RoleAdmission admissions, ProviderCall provider, SocLlmTaskPolicies policies) {
        this.role = Objects.requireNonNull(role);
        this.admissions = Objects.requireNonNull(admissions);
        this.provider = Objects.requireNonNull(provider);
        this.policies = Objects.requireNonNull(policies);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), action -> {
            Thread thread = new Thread(action, "mp4-socllm-" + role.name().toLowerCase());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public String generate(Role actorRole, UUID actorId, Task task, String instructions, String input,
            int outputTokens, Duration deadline) {
        SocLlmTransactionBoundary.requireNoCallerTransaction();
        validate(actorRole, actorId, task, instructions, input, outputTokens, deadline);
        int tokenReservation = instructions.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + outputTokens;
        AdmissionLease lease = admissions.acquire(role, actorId, task, tokenReservation, Duration.ofSeconds(60));
        AtomicInteger state = new AtomicInteger(); // 0=not started, 1=running, 2=cancelled before start, 3=finished
        Future<String> future;
        try {
            future = workers.submit(() -> {
                if (!state.compareAndSet(0, 1)) return null;
                try {
                    lease.renewUntilWorkerTerminates();
                    return provider.generate(instructions, input, outputTokens, deadline);
                } finally {
                    state.set(3);
                    lease.close();
                }
            });
        } catch (RejectedExecutionException saturated) {
            lease.close();
            throw new SummaryFailure(FailureCode.AI_BUSY, 5);
        }

        try {
            return future.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            closeIfNotStarted(lease, state);
            throw new SummaryFailure(FailureCode.AI_TIMEOUT);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            closeIfNotStarted(lease, state);
            Thread.currentThread().interrupt();
            throw new SummaryFailure(FailureCode.AI_TIMEOUT);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof SummaryFailure safe) throw safe;
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
    }

    @Override public String generate(SocLlmContracts.Call call) {
        Objects.requireNonNull(call);
        return generate(call.role(), call.actorId(), call.task(), call.instructions(), call.input(),
                call.outputTokens(), call.deadline());
    }

    private void validate(Role actorRole, UUID actorId, Task task, String instructions, String input,
            int outputTokens, Duration deadline) {
        if (actorRole != role || actorId == null || task == null || task.role() != role
                || instructions == null || instructions.isBlank() || input == null || input.isBlank()) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
        // Limits come from the server-owned policy of this call's own task, never from the caller.
        SocLlmTaskPolicy policy = policies.forTask(task);
        if (input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > policy.maxInputBytes()
                || instructions.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > policy.maxInstructionBytes()
                || outputTokens < 1 || outputTokens > policy.maxOutputTokens() || deadline == null
                || deadline.isNegative() || deadline.isZero() || deadline.compareTo(policy.maxDeadline()) > 0) {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        }
    }

    private static void closeIfNotStarted(AdmissionLease lease, AtomicInteger state) {
        if (state.compareAndSet(0, 2)) lease.close();
    }

    @Override public void close() { workers.shutdownNow(); }
}
