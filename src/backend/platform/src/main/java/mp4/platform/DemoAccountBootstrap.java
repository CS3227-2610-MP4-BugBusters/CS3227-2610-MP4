package mp4.platform;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Public, fixed demo identity. Startup never overwrites an existing account. */
final class DemoAccountBootstrap {
    static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final String USERNAME = "demo.maintainer";
    static final String PASSWORD = "demo-maintainer-2026"; // Intentionally public demo credential.
    static void seed(DataSource ds, PasswordEncoder encoder) throws java.sql.SQLException {
        try (var c=ds.getConnection()) {
            try (var s=c.prepareStatement("INSERT INTO app_accounts(id,username,display_name,password_hash,role,demo_locked) VALUES (?,?,'Demo Maintainer',?,'MAINTAINER',TRUE) ON CONFLICT (username) DO NOTHING")) {
                s.setObject(1,ID); s.setString(2,USERNAME); s.setString(3,encoder.encode(PASSWORD)); s.executeUpdate();
            }
            try (var s=c.prepareStatement("SELECT id,display_name,password_hash,role,active,demo_locked FROM app_accounts WHERE username=?")) {
                s.setString(1,USERNAME);
                try (var row=s.executeQuery()) {
                    if(!row.next() || !ID.equals(row.getObject("id",UUID.class)) || !"Demo Maintainer".equals(row.getString("display_name")) || !"MAINTAINER".equals(row.getString("role")) || !row.getBoolean("active") || !row.getBoolean("demo_locked") || !encoder.matches(PASSWORD,row.getString("password_hash")))
                        throw new IllegalStateException("Reserved demo identity conflicts with an existing account");
                }
            }
        }
    }
}
