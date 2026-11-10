package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmRoleService;
import mp4.maintainer.summary.SummaryContracts.BudgetAndAdmission;
import mp4.maintainer.summary.SummaryContracts.SummaryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

class SocLlmRoleConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(MaintainerSummaryConfiguration.class, RuntimeDependencies.class);

    @Configuration(proxyBeanMethods=false)
    static class RuntimeDependencies {
        @Bean DataSource database(){return mock(DataSource.class);}
        @Bean JdbcIndexedSessionRepository sessions(){return mock(JdbcIndexedSessionRepository.class);}
        @Bean Clock clock(){return Clock.systemUTC();}
    }

    @Test void reporterKeyActivatesOnlyReporterBackendPort() {
        contexts.withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_REPORTER=reporter-placeholder",
                "SOC_LLM_MODEL=test-model", "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(SummaryGateway.class).size());
            assertEquals(0, context.getBeansOfType(BudgetAndAdmission.class).size());
            @SuppressWarnings("unchecked") Map<SocLlmContracts.Role,SocLlmRoleService> services =
                    (Map<SocLlmContracts.Role,SocLlmRoleService>) context.getBean("socLlmRoleServices");
            assertEquals(java.util.Set.of(SocLlmContracts.Role.REPORTER), services.keySet());
        });
    }

    @Test void paddedKeysAreCanonicalizedAndInvalidRoleKeysDoNotBreakStartup() {
        contexts.withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_REPORTER=  reporter-token==  ",
                "SOC_LLM_API_KEY_DEVELOPER=developer token", "SOC_LLM_MODEL=test-model",
                "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048").run(context -> {
            assertNull(context.getStartupFailure());
            @SuppressWarnings("unchecked") Map<SocLlmContracts.Role,SocLlmRoleService> services =
                    (Map<SocLlmContracts.Role,SocLlmRoleService>) context.getBean("socLlmRoleServices");
            assertEquals(java.util.Set.of(SocLlmContracts.Role.REPORTER), services.keySet());
        });

        contexts.withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_MAINTAINER=invalid key",
                "SOC_LLM_MODEL=test-model", "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048").run(context -> {
            assertNull(context.getStartupFailure(), "invalid optional key must not break ordinary startup");
            assertEquals(0, context.getBeansOfType(SummaryGateway.class).size());
            assertEquals(0, context.getBeansOfType(BudgetAndAdmission.class).size());
        });
    }
}
