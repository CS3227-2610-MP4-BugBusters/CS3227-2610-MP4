package mp4.platform;

import java.util.Map;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmRoleService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Connects Developer suggestions only to the shared Developer role service. */
@Configuration(proxyBeanMethods = false)
class SuggestionProviderConfiguration {
    static final class DeveloperRoleReady implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return SocLlmConfiguration.enabledAndBudgetsComplete(environment)
                    && SocLlmContracts.isValidApiKey(environment.getProperty("SOC_LLM_API_KEY_DEVELOPER"));
        }
    }

    @Bean
    @Conditional(DeveloperRoleReady.class)
    SuggestionProvider socSuggestionProvider(Environment environment,
            @Qualifier("socLlmRoleServices") Map<SocLlmContracts.Role, SocLlmRoleService> services) {
        SocLlmRoleService developerGateway = services.get(SocLlmContracts.Role.DEVELOPER);
        return new SocLlmSuggestionProvider(developerGateway,
                environment.getRequiredProperty("SOC_LLM_API_KEY_DEVELOPER"),
                environment.getRequiredProperty("SOC_LLM_MODEL"));
    }
}
