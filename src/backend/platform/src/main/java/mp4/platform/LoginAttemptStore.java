package mp4.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.scheduling.annotation.Scheduled;

/** Shared atomic rolling login budgets with bounded HMAC-key retention. */
final class LoginAttemptStore {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration RETENTION = Duration.ofHours(24);
    private final DataSource dataSource;
    private final Clock clock;
    private final byte[] secret;

    LoginAttemptStore(DataSource dataSource, Clock clock, String secret) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) throw new IllegalArgumentException("rate limiter secret must be at least 32 UTF-8 bytes");
    }

    int admit(String normalizedUsername, String trustedClientAddress) throws SQLException {
        cleanupExpired();
        String userKey = digest("user:" + normalizedUsername);
        String clientKey = digest("client:" + trustedClientAddress);
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String key : new TreeSet<>(List.of(userKey, clientKey))) {
                    try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                        lock.setString(1, key);
                        lock.execute();
                    }
                }
                int userCount = count(connection, userKey, now);
                int clientCount = count(connection, clientKey, now);
                if (userCount >= 10 || clientCount >= 100) {
                    connection.rollback();
                    return retryAfter(userKey, clientKey, now);
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO public.mp4_auth_login_attempt(key_digest, attempted_at)
                        VALUES (?, ?), (?, ?)
                        """)) {
                    insert.setString(1, userKey);
                    insert.setTimestamp(2, Timestamp.from(now));
                    insert.setString(3, clientKey);
                    insert.setTimestamp(4, Timestamp.from(now));
                    insert.executeUpdate();
                }
                connection.commit();
                return 0;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    void cleanupExpired() throws SQLException {
        Instant cutoff = clock.instant().minus(RETENTION);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM public.mp4_auth_login_attempt WHERE attempted_at < ?")) {
                delete.setTimestamp(1, Timestamp.from(cutoff));
                delete.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Scheduled(fixedDelayString = "${mp4.auth.cleanup-interval-ms:900000}")
    void scheduledCleanup() throws SQLException { cleanupExpired(); }

    private int retryAfter(String userKey, String clientKey, Instant now) throws SQLException {
        return Math.min(900, Math.max(1, Math.max(nextExpiry(userKey, now), nextExpiry(clientKey, now))));
    }

    private int nextExpiry(String key, Instant now) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT min(attempted_at) FROM public.mp4_auth_login_attempt
                     WHERE key_digest = ? AND attempted_at >= ?
                     """)) {
            query.setString(1, key);
            query.setTimestamp(2, Timestamp.from(now.minus(WINDOW)));
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                Timestamp oldest = rows.getTimestamp(1);
                if (oldest == null) return 1;
                return (int) Math.max(1, Duration.between(now, oldest.toInstant().plus(WINDOW)).getSeconds());
            }
        }
    }

    private static int count(Connection connection, String key, Instant now) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT count(*) FROM public.mp4_auth_login_attempt
                WHERE key_digest = ? AND attempted_at >= ?
                """)) {
            query.setString(1, key);
            query.setTimestamp(2, Timestamp.from(now.minus(WINDOW)));
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
        }
    }
}
