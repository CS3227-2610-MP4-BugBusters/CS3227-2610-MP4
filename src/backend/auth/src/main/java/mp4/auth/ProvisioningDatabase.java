package mp4.auth;

import org.flywaydb.core.Flyway;

import java.util.Map;
import java.net.URI;
import java.net.URISyntaxException;

/** Auth-owned PostgreSQL connection configuration and isolated migration bootstrap. */
final class ProvisioningDatabase {
    private ProvisioningDatabase() {}

    record Config(String url, String user, String password) {
        static Config fromEnvironment(Map<String, String> env) {
            String url = env.get("MP4_AUTH_JDBC_URL");
            String user = env.get("MP4_AUTH_DB_USER");
            String password = env.get("MP4_AUTH_DB_PASSWORD");
            if (!validUrl(url) || user == null || user.isBlank() || password == null || password.isEmpty()) {
                throw new IllegalArgumentException("invalid database configuration");
            }
            return new Config(url, user, password);
        }

        private static boolean validUrl(String value) {
            if (value == null || !value.startsWith("jdbc:")) return false;
            try {
                URI uri = new URI(value.substring(5));
                String path = uri.getRawPath();
                return "postgresql".equals(uri.getScheme()) && uri.getHost() != null && uri.getPort() >= 1 && uri.getPort() <= 65535
                        && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && path != null && path.length() > 1 && path.indexOf('/', 1) < 0;
            } catch (URISyntaxException | IllegalArgumentException e) { return false; }
        }
    }

    static void initialize(Config config) {
        Flyway.configure()
                .loggers(SilentFlywayLogCreator.class.getName())
                .dataSource(config.url(), config.user(), config.password())
                .defaultSchema("public")
                .locations("classpath:db/auth")
                .table("mp4_auth_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("99")
                .load().migrate();
    }
}
