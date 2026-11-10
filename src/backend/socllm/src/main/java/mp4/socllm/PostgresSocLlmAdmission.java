package mp4.socllm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import mp4.maintainer.summary.SummaryContracts.AdmissionLease;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL admission shared atomically by role aliases, application instances and workers. */
public final class PostgresSocLlmAdmission implements SocLlmContracts.RoleAdmission, AutoCloseable {
    private static final Duration ACTOR_WINDOW = Duration.ofMinutes(5);
    private static final Duration PROVIDER_WINDOW = Duration.ofMinutes(1);
    private static final Duration ROW_RETENTION = Duration.ofHours(24);
    private static final int GLOBAL_IN_FLIGHT_LIMIT = 4;
    private static final Duration WORKER_LEASE = Duration.ofSeconds(60);

    /** The secret is never included in the record's textual representation. */
    public record KeyBudget(String secret, int requestsPerMinute, int reservedTokensPerMinute) {
        public KeyBudget {
            secret = SocLlmContracts.canonicalApiKey(secret);
            if (requestsPerMinute <= 0 || reservedTokensPerMinute <= 0) {
                throw new IllegalArgumentException("Invalid role provider budget.");
            }
        }

        @Override public String toString() {
            return "KeyBudget[secret=<redacted>, requestsPerMinute=" + requestsPerMinute
                    + ", reservedTokensPerMinute=" + reservedTokensPerMinute + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<SocLlmContracts.Role, KeyBudget> budgets;
    private final TransactionTemplate transaction;
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(action -> {
        Thread thread = new Thread(action, "mp4-socllm-lease-renewal");
        thread.setDaemon(true);
        return thread;
    });

    public PostgresSocLlmAdmission(DataSource source, Map<SocLlmContracts.Role, KeyBudget> budgets) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(source));
        this.jdbc.setQueryTimeout(2);
        this.budgets = Map.copyOf(budgets);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(3);
    }

    @Override
    public AdmissionLease acquire(SocLlmContracts.Role role, UUID actor, SocLlmContracts.Task task,
            int reservedTokens, Duration requestedLease) {
        SocLlmTransactionBoundary.requireNoCallerTransaction();
        KeyBudget roleBudget = budgets.get(role);
        if (role == null || actor == null || task == null || requestedLease == null
                || requestedLease.isZero() || requestedLease.isNegative()
                || roleBudget == null || task.role() != role || reservedTokens <= 0) {
            throw unavailable();
        }

        String fingerprint = fingerprint(roleBudget.secret());
        KeyBudget effectiveBudget = strictestBudget(roleBudget, fingerprint);
        if (reservedTokens > effectiveBudget.reservedTokensPerMinute()) throw unavailable();
        UUID requestId = UUID.randomUUID();

        try {
            transaction.executeWithoutResult(status -> admitLocked(
                    requestId, actor, role, task, reservedTokens, fingerprint, effectiveBudget));
        } catch (SummaryFailure safeFailure) {
            throw safeFailure;
        } catch (RuntimeException databaseFailure) {
            throw unavailable();
        }
        return new WorkerLease(requestId);
    }

    /**
     * Registers the strictest budgets known to this runtime. Updates are monotonic decreases;
     * a deployment cannot raise a physical-key budget by starting with weaker local settings.
     */
    public void registerConfiguredBudgets() {
        SocLlmTransactionBoundary.requireNoCallerTransaction();
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('lock_timeout', '500ms', true)", String.class);
                jdbc.queryForObject("SELECT set_config('statement_timeout', '2000ms', true)", String.class);
                jdbc.queryForObject("SELECT id FROM mp4_socllm.quota_lock WHERE id=1 FOR UPDATE", Integer.class);
                for (KeyBudget budget : strictestBudgetsByPhysicalKey().values()) {
                    registerOrTightenBudget(fingerprint(budget.secret()), budget);
                }
            });
        } catch (RuntimeException databaseFailure) {
            throw unavailable();
        }
    }

    private void admitLocked(UUID requestId, UUID actor, SocLlmContracts.Role role,
            SocLlmContracts.Task task, int reservedTokens, String fingerprint, KeyBudget budget) {
        jdbc.queryForObject("SELECT set_config('lock_timeout', '500ms', true)", String.class);
        jdbc.queryForObject("SELECT set_config('statement_timeout', '2000ms', true)", String.class);
        jdbc.queryForObject("SELECT id FROM mp4_socllm.quota_lock WHERE id=1 FOR UPDATE", Integer.class);
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();

        // Never discard a non-null lease based on its timestamp. A worker that ignores cancellation
        // remains protected even if a heartbeat failed or the server clock advanced.
        jdbc.update("DELETE FROM mp4_socllm.attempt WHERE admitted_at < ? AND in_flight_until IS NULL",
                Timestamp.from(now.minus(ROW_RETENTION)));

        Long actorInFlight = jdbc.queryForObject("""
                SELECT count(*) FROM mp4_socllm.attempt
                WHERE actor_id=? AND role=? AND in_flight_until IS NOT NULL
                """, Long.class, actor, role.name());
        if (actorInFlight != null && actorInFlight > 0) throw busy();

        Long actorStarts = jdbc.queryForObject("""
                SELECT count(*) FROM mp4_socllm.attempt
                WHERE actor_id=? AND role=? AND admitted_at>?
                """, Long.class, actor, role.name(), Timestamp.from(now.minus(ACTOR_WINDOW)));
        if (actorStarts != null && actorStarts >= 3) {
            throw new SummaryFailure(FailureCode.AI_RATE_LIMITED, 300);
        }

        Long keyInFlight = jdbc.queryForObject("""
                SELECT count(*) FROM mp4_socllm.attempt
                WHERE key_fingerprint=? AND in_flight_until IS NOT NULL
                """, Long.class, fingerprint);
        if (keyInFlight != null && keyInFlight >= GLOBAL_IN_FLIGHT_LIMIT) throw busy();

        KeyBudget sharedBudget = registerOrTightenBudget(fingerprint, budget);
        List<AttemptRow> attempts = jdbc.query("""
                SELECT admitted_at,reserved_tokens FROM mp4_socllm.attempt
                WHERE key_fingerprint=? AND admitted_at>?
                ORDER BY admitted_at
                """, (row, index) -> new AttemptRow(row.getTimestamp("admitted_at").toInstant(),
                row.getInt("reserved_tokens")), fingerprint, Timestamp.from(now.minus(PROVIDER_WINDOW)));
        long reservedTotal = attempts.stream().mapToLong(AttemptRow::reservedTokens).sum();
        if (attempts.size() >= sharedBudget.requestsPerMinute()
                || reservedTotal + reservedTokens > sharedBudget.reservedTokensPerMinute()) {
            throw new SummaryFailure(FailureCode.AI_RATE_LIMITED, 60);
        }

        jdbc.update("""
                INSERT INTO mp4_socllm.attempt
                    (request_id,actor_id,role,task,key_fingerprint,admitted_at,reserved_tokens,in_flight_until)
                VALUES (?,?,?,?,?,?,?,clock_timestamp() + interval '60 seconds')
                """, requestId, actor, role.name(), task.name(), fingerprint, Timestamp.from(now), reservedTokens);
    }

    private KeyBudget strictestBudget(KeyBudget roleBudget, String roleFingerprint) {
        int requests = roleBudget.requestsPerMinute();
        int tokens = roleBudget.reservedTokensPerMinute();
        for (KeyBudget configured : budgets.values()) {
            if (fingerprint(configured.secret()).equals(roleFingerprint)) {
                requests = Math.min(requests, configured.requestsPerMinute());
                tokens = Math.min(tokens, configured.reservedTokensPerMinute());
            }
        }
        return new KeyBudget(roleBudget.secret(), requests, tokens);
    }

    private Map<String, KeyBudget> strictestBudgetsByPhysicalKey() {
        Map<String, KeyBudget> policies = new java.util.HashMap<>();
        for (KeyBudget budget : budgets.values()) {
            String fingerprint = fingerprint(budget.secret());
            KeyBudget previous = policies.get(fingerprint);
            if (previous == null) {
                policies.put(fingerprint, budget);
            } else {
                policies.put(fingerprint, new KeyBudget(budget.secret(),
                        Math.min(previous.requestsPerMinute(), budget.requestsPerMinute()),
                        Math.min(previous.reservedTokensPerMinute(), budget.reservedTokensPerMinute())));
            }
        }
        return policies;
    }

    private KeyBudget registerOrTightenBudget(String fingerprint, KeyBudget configured) {
        return jdbc.queryForObject("""
                INSERT INTO mp4_socllm.provider_budget
                    (key_fingerprint,requests_per_minute,reserved_tokens_per_minute)
                VALUES (?,?,?)
                ON CONFLICT (key_fingerprint) DO UPDATE SET
                    requests_per_minute=LEAST(mp4_socllm.provider_budget.requests_per_minute,EXCLUDED.requests_per_minute),
                    reserved_tokens_per_minute=LEAST(mp4_socllm.provider_budget.reserved_tokens_per_minute,EXCLUDED.reserved_tokens_per_minute),
                    updated_at=clock_timestamp()
                RETURNING requests_per_minute,reserved_tokens_per_minute
                """, (row, index) -> new KeyBudget(configured.secret(),
                row.getInt("requests_per_minute"), row.getInt("reserved_tokens_per_minute")), fingerprint,
                configured.requestsPerMinute(), configured.reservedTokensPerMinute());
    }

    private final class WorkerLease implements AdmissionLease {
        private final UUID requestId;
        private ScheduledFuture<?> heartbeat;
        private boolean closed;

        WorkerLease(UUID requestId) { this.requestId = requestId; }

        @Override
        public synchronized void renewUntilWorkerTerminates() {
            if (closed) return;
            renew();
            if (heartbeat == null) heartbeat = renewer.scheduleAtFixedRate(this::renew, 15, 15, TimeUnit.SECONDS);
        }

        private synchronized void renew() {
            if (closed) return;
            try {
                int updated = jdbc.update("""
                        UPDATE mp4_socllm.attempt
                        SET in_flight_until=clock_timestamp() + interval '60 seconds'
                        WHERE request_id=? AND in_flight_until IS NOT NULL
                        """, requestId);
                if (updated != 1) throw unavailable();
            } catch (RuntimeException databaseFailure) {
                // The durable non-null lease remains protective if renewal cannot reach PostgreSQL.
                throw unavailable();
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (heartbeat != null) heartbeat.cancel(false);
            try {
                int updated = jdbc.update("UPDATE mp4_socllm.attempt SET in_flight_until=NULL WHERE request_id=?", requestId);
                if (updated != 1) throw unavailable();
            } catch (RuntimeException databaseFailure) {
                // Leave the lease non-null and fail closed; operator recovery is required.
                throw unavailable();
            }
        }
    }

    private static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    SocLlmContracts.canonicalApiKey(secret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static SummaryFailure unavailable() { return new SummaryFailure(FailureCode.AI_UNAVAILABLE); }
    private static SummaryFailure busy() { return new SummaryFailure(FailureCode.AI_BUSY, 5); }

    @Override public void close() { renewer.shutdownNow(); }

    private record AttemptRow(Instant admittedAt, int reservedTokens) {}
}
