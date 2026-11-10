package mp4.platform;

import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import mp4.socllm.PostgresSocLlmAdmission;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmRoleService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Activates only the Reporter/Developer role services whose own keys are configured. */
@Configuration(proxyBeanMethods = false)
@Conditional(SocLlmRoleConfiguration.AnyRoleReady.class)
class SocLlmRoleConfiguration {
    static final class AnyRoleReady implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return SocLlmConfiguration.enabledAndBudgetsComplete(environment)
                    && (SocLlmContracts.isValidApiKey(environment.getProperty("SOC_LLM_API_KEY_REPORTER"))
                    || SocLlmContracts.isValidApiKey(environment.getProperty("SOC_LLM_API_KEY_DEVELOPER"))
                    || SocLlmContracts.isValidApiKey(environment.getProperty("SOC_LLM_API_KEY_MAINTAINER")));
        }
    }

    static final class AuthDatabaseConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return SocLlmConfiguration.hasValue(context.getEnvironment().getProperty("mp4.auth.jdbc-url"));
        }
    }

    @Bean
    PostgresSocLlmAdmission socLlmAdmission(DataSource dataSource, Environment environment) {
        int requestsPerMinute = Integer.parseInt(environment.getRequiredProperty("SOC_LLM_RPM"));
        int reservedTokensPerMinute = Integer.parseInt(environment.getRequiredProperty("SOC_LLM_TPM"));
        Map<SocLlmContracts.Role, PostgresSocLlmAdmission.KeyBudget> budgets =
                new EnumMap<>(SocLlmContracts.Role.class);

        for (SocLlmContracts.Role role : SocLlmContracts.Role.values()) {
            String key = environment.getProperty("SOC_LLM_API_KEY_" + role.name());
            if (SocLlmContracts.isValidApiKey(key)) {
                budgets.put(role, new PostgresSocLlmAdmission.KeyBudget(
                        key, requestsPerMinute, reservedTokensPerMinute));
            }
        }
        return new PostgresSocLlmAdmission(dataSource, budgets);
    }

    @Bean
    @Conditional(AuthDatabaseConfigured.class)
    @DependsOn("authMigrations")
    org.springframework.beans.factory.InitializingBean registerSocLlmBudgets(
            PostgresSocLlmAdmission admission) {
        return admission::registerConfiguredBudgets;
    }

    @Bean
    Map<SocLlmContracts.Role, SocLlmRoleService> socLlmRoleServices(
            Environment environment, Clock clock, SocLlmContracts.RoleAdmission admission) {
        Map<SocLlmContracts.Role, SocLlmRoleService> services = new EnumMap<>(SocLlmContracts.Role.class);
        for (SocLlmContracts.Role role : List.of(SocLlmContracts.Role.REPORTER, SocLlmContracts.Role.DEVELOPER)) {
            String key = environment.getProperty("SOC_LLM_API_KEY_" + role.name());
            if (SocLlmContracts.isValidApiKey(key)) {
                services.put(role, new SocLlmRoleService(
                        role, key, environment.getRequiredProperty("SOC_LLM_MODEL"), admission, clock));
            }
        }
        return Collections.unmodifiableMap(services);
    }

    @Bean
    DisposableBean closeSocLlmServices(
            Map<SocLlmContracts.Role, SocLlmRoleService> services,
            PostgresSocLlmAdmission admission) {
        return () -> {
            services.values().forEach(SocLlmRoleService::close);
            admission.close();
        };
    }
}
