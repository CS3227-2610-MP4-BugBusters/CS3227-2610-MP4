package mp4.platform;

import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** JDBC access to the current account record; password work never runs with a row lock held. */
final class AuthAccountRepository {
    private final DataSource dataSource;

    AuthAccountRepository(DataSource dataSource) { this.dataSource = dataSource; }

    Optional<AccountSnapshot> findByUsername(String username) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, username, display_name, password_hash, role, active, auth_generation
                     FROM public.app_accounts WHERE username = ?
                     """)) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    Optional<AccountSnapshot> findById(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, username, display_name, password_hash, role, active, auth_generation
                     FROM public.app_accounts WHERE id = ?
                     """)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    boolean confirmCredentialSnapshot(AccountSnapshot snapshot) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT username, password_hash, role, active, auth_generation
                    FROM public.app_accounts WHERE id = ? FOR UPDATE
                    """)) {
                statement.setObject(1, snapshot.id());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return false; }
                    boolean unchanged = rows.getString("username").equals(snapshot.username())
                            && rows.getString("password_hash").equals(snapshot.passwordHash())
                            && rows.getString("role").equals(snapshot.role())
                            && rows.getBoolean("active") == snapshot.active()
                            && rows.getLong("auth_generation") == snapshot.generation()
                            && snapshot.active();
                    connection.commit();
                    return unchanged;
                }
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static AccountSnapshot read(ResultSet rows) throws SQLException {
        return new AccountSnapshot(rows.getObject("id", UUID.class), rows.getString("username"),
                rows.getString("display_name"), rows.getString("password_hash"), rows.getString("role"),
                rows.getBoolean("active"), rows.getLong("auth_generation"));
    }

    record AccountSnapshot(UUID id, String username, String displayName, String passwordHash,
                           String role, boolean active, long generation) { }
}
