package mp4.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables the temporary SoC adapter only when both server-side settings exist. */
@Configuration(proxyBeanMethods = false)
final class SuggestionProviderConfiguration {
    @Bean
    @Conditional(SocProviderConfigured.class)
    SuggestionProvider socSuggestionProvider(
            org.springframework.core.env.Environment environment) {
        String baseUrl = environment.getProperty("SOCLAAS_BASE_URL", "");
        String key = environment.getProperty("SOCLAAS_API_KEY", "");
        String model = environment.getProperty("SOCLAAS_MODEL", "qwen3.5:9b");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new SocSuggestionProvider(client, URI.create(baseUrl), key, model, Duration.ofSeconds(15));
    }

    static final class SocProviderConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            String baseUrl = environment.getProperty("SOCLAAS_BASE_URL", "");
            String key = environment.getProperty("SOCLAAS_API_KEY", "");
            if (baseUrl.isBlank() || key.isBlank()) return false;
            try {
                return "https".equalsIgnoreCase(URI.create(baseUrl).getScheme());
            } catch (IllegalArgumentException invalidUri) {
                return false;
            }
        }
    }
}
