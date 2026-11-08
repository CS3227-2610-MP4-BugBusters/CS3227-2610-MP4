package mp4.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** Transactional persistence for immutable fixed-role accounts. */
final class AccountProvisioningService {
    private static final String USERNAME_UNIQUE = "app_accounts_username_key";
    private static final String ID_UNIQUE = "app_accounts_pkey";
    private final ProvisioningDatabase.Config config;
    private final DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt",
            Map.of("bcrypt", new BCryptPasswordEncoder(12)));

    AccountProvisioningService(ProvisioningDatabase.Config config) { this.config = config; }

    Created create(ProvisioningRequest request, byte[] password) throws SQLException {
        String hash = encoder.encode(new String(password, java.nio.charset.StandardCharsets.UTF_8));
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO public.app_accounts (id, username, display_name, password_hash, role, active, auth_generation) VALUES (?, ?, ?, ?, ?, TRUE, 0)")) {
                insert.setObject(1, request.id());
                insert.setString(2, request.username());
                insert.setString(3, request.displayName());
                insert.setString(4, hash);
                insert.setString(5, request.role());
                insert.executeUpdate();
                connection.commit();
                return new Created(request.id(), request.role());
            } catch (SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                if (isKnownDuplicate(e)) throw new DuplicateAccount();
                throw e;
            }
        }
    }

    Lifecycle change(String command, String username, byte[] password) throws SQLException {
        String hash = password == null ? null : encoder.encode(new String(password, java.nio.charset.StandardCharsets.UTF_8));
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement(command.equals("disable")
                    ? "UPDATE public.app_accounts SET active=FALSE,auth_generation=auth_generation+1 WHERE username=? RETURNING id,role"
                    : "UPDATE public.app_accounts SET password_hash=?,auth_generation=auth_generation+1 WHERE username=? RETURNING id,role")) {
                if (command.equals("disable")) update.setString(1, username);
                else { update.setString(1, hash); update.setString(2, username); }
                try (var row = update.executeQuery()) {
                    if (!row.next()) { connection.rollback(); throw new AccountNotFound(); }
                    Lifecycle result = new Lifecycle(row.getObject(1, UUID.class), row.getString(2));
                    connection.commit(); return result;
                }
            } catch (SQLException | RuntimeException e) { try { connection.rollback(); } catch (SQLException ignored) {} throw e; }
        }
    }

    private static boolean isKnownDuplicate(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if ("23505".equals(current.getSQLState()) && current instanceof org.postgresql.util.PSQLException pg
                    && pg.getServerErrorMessage() != null
                    && (USERNAME_UNIQUE.equals(pg.getServerErrorMessage().getConstraint()) || ID_UNIQUE.equals(pg.getServerErrorMessage().getConstraint()))) return true;
        }
        return false;
    }

    record Created(UUID id, String role) { }
    record Lifecycle(UUID id, String role) { }
    static final class DuplicateAccount extends RuntimeException { }
    static final class AccountNotFound extends RuntimeException { }
}
