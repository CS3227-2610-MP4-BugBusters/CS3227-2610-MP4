package mp4.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.context.SecurityContextHolder;

/** Adds current account eligibility to existing role transactions without changing their contracts. */
public final class ActorTransactionDataSource extends DelegatingDataSource {
    // Existing handlers may translate store failures; preserve the auth decision for the servlet boundary.
    private static final ThreadLocal<Boolean> REVOKED = new ThreadLocal<>();
    static void clearRevocation() { REVOKED.remove(); }
    static boolean wasRevoked() { return Boolean.TRUE.equals(REVOKED.get()); }
    public ActorTransactionDataSource(DataSource target) { super(target); }

    @Override public Connection getConnection() throws SQLException {
        var actor = actor();
        return protect(super.getConnection(), actor);
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        var actor = actor();
        return protect(super.getConnection(username, password), actor);
    }

    private static AuthProvider.CurrentUser actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthProvider.CurrentUser user)) throw new Revoked();
        return user;
    }

    private static Connection protect(Connection connection, AuthProvider.CurrentUser actor) {
        boolean[] checked = {false};
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    String name = method.getName();
                    if (name.equals("equals")) return proxy == arguments[0];
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("toString")) return "AuthenticatedConnection";
                    try {
                        if ((name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement"))
                                && !connection.getAutoCommit() && !checked[0]) {
                            check(connection, actor); checked[0] = true;
                        }
                        Object result = method.invoke(connection, arguments);
                        if (name.equals("setAutoCommit")) {
                            checked[0] = false;
                            if (Boolean.FALSE.equals(arguments[0])) { check(connection, actor); checked[0] = true; }
                        } else if (name.equals("commit") || name.equals("rollback")) checked[0] = false;
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static void check(Connection connection, AuthProvider.CurrentUser actor) throws SQLException {
        try (var limits = connection.createStatement()) {
            limits.execute("SET LOCAL lock_timeout='5s'");
            limits.execute("SET LOCAL statement_timeout='5s'");
        }
        String lock = connection.isReadOnly() ? "" : " FOR SHARE";
        try (var query = connection.prepareStatement("SELECT role,active,auth_generation FROM public.app_accounts WHERE id=?" + lock)) {
            query.setObject(1, actor.id());
            try (var row = query.executeQuery()) {
                if (!row.next() || !row.getBoolean("active") || !actor.role().equals(row.getString("role"))
                        || actor.generation() != row.getLong("auth_generation")) throw new Revoked();
            }
        }
    }

    static final class Revoked extends RuntimeException {
        Revoked() { REVOKED.set(true); }
    }
}
