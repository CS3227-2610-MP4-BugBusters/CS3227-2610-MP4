package mp4.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Fail-closed infrastructure baseline: only the non-sensitive health check is public. */
@Configuration
public class ClosedSecurityConfiguration {
    @Bean
    SecurityFilterChain closedSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(r -> r.requestMatchers("/api/health").permitAll().anyRequest().denyAll())
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).build();
    }
}
