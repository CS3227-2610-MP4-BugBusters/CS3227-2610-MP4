package mp4.platform;

import java.util.Map;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmRoleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Connects the Reporter report check only to the shared Reporter role service. */
@Configuration(proxyBeanMethods = false)
class ReportClarifyConfiguration {
    static final class ReporterRoleReady implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return SocLlmConfiguration.enabledAndBudgetsComplete(environment)
                    && SocLlmContracts.isValidApiKey(environment.getProperty("SOC_LLM_API_KEY_REPORTER"));
        }
    }

    @Bean
    @Conditional(ReporterRoleReady.class)
    ReportClarifyProvider socReportClarifyProvider(Environment environment,
            @Qualifier("socLlmRoleServices") Map<SocLlmContracts.Role, SocLlmRoleService> services) {
        return new SocLlmReportClarifyProvider(services.get(SocLlmContracts.Role.REPORTER),
                SocLlmRoleConfiguration.modelForRole(environment, SocLlmContracts.Role.REPORTER));
    }

    /** Without a Reporter key the check answers 503 AI_UNAVAILABLE and never calls a provider. */
    @Bean
    ReportClarifyService reportClarifyService(ObjectProvider<ReportClarifyProvider> providers) {
        return new ReportClarifyService(providers.getIfAvailable());
    }
}
