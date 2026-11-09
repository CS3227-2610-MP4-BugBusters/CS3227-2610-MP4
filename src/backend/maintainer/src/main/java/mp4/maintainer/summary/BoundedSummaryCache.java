package mp4.maintainer.summary;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static mp4.maintainer.summary.SummaryContracts.*;

/** One short-lived result per account; cached values still require delivery authorization and source checks. */
public final class BoundedSummaryCache implements SummaryCache {
    private static final int MAXIMUM_ACCOUNTS = 1000;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private record Entry(CacheKey key, SummaryResult result, Instant expiresAt) {}

    public BoundedSummaryCache(Clock clock) { this.clock = clock; }

    @Override public SummaryResult find(CacheKey key, Instant now) {
        Entry entry = entries.get(key.accountId());
        if (entry == null) return null;
        if (!now.isBefore(entry.expiresAt()) || !entry.key().equals(key)) {
            entries.remove(key.accountId(), entry);
            return null;
        }
        return entry.result();
    }

    @Override public synchronized void store(CacheKey key, SummaryResult result, Instant expiresAt) {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        if (!now.isBefore(expiresAt)) return;
        if (!entries.containsKey(key.accountId()) && entries.size() >= MAXIMUM_ACCOUNTS)
            entries.keySet().stream().findFirst().ifPresent(entries::remove);
        entries.put(key.accountId(), new Entry(key, result, expiresAt));
    }
}
