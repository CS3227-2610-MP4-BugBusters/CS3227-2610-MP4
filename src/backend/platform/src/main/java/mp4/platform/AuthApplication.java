package mp4.platform;

import java.time.Clock;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Executable web setup; authentication is denied until its tested implementation is supplied. */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AuthApplication.class);
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "8080",
                "spring.main.banner-mode", "off"));
        app.run(args);
    }

    @Bean
    Clock authClock() { return Clock.systemUTC(); }
}
