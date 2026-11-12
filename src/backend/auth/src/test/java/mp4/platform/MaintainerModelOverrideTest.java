package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import mp4.maintainer.summary.SummaryContracts.SummaryGateway;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmRoleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/** Verifies Maintainer model selection is isolated from shared Reporter/Developer settings. */
class MaintainerModelOverrideTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(MaintainerSummaryConfiguration.class, RuntimeDependencies.class);

    @Configuration(proxyBeanMethods = false)
    static class RuntimeDependencies {
        @Bean DataSource database() { return mock(DataSource.class); }
        @Bean JdbcIndexedSessionRepository sessions() { return mock(JdbcIndexedSessionRepository.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Test
    void maintainerOverrideSelectsItsGatewayModelWhileReporterAndDeveloperKeepSharedModel() {
        contexts.withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_MAINTAINER=maintainer-placeholder",
                "SOC_LLM_API_KEY_REPORTER=reporter-placeholder", "SOC_LLM_API_KEY_DEVELOPER=developer-placeholder",
                "SOC_LLM_MODEL=shared-model", "SOC_LLM_MODEL_MAINTAINER=maintainer-model",
                "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048").run(context -> {
            assertNull(context.getStartupFailure());
            SummaryGateway gateway = context.getBean(SummaryGateway.class);
            assertEquals("maintainer-model", gateway.modelIdentity().model());
            @SuppressWarnings("unchecked")
            Map<SocLlmContracts.Role, SocLlmRoleService> services =
                    (Map<SocLlmContracts.Role, SocLlmRoleService>) context.getBean("socLlmRoleServices");
            assertEquals(java.util.Set.of(SocLlmContracts.Role.REPORTER, SocLlmContracts.Role.DEVELOPER),
                    services.keySet());
        });
    }

    @Test
    void absentOrBlankMaintainerOverrideFallsBackToSharedRequiredModel() {
        for (String override : new String[] {null, ""}) {
            var configured = contexts.withPropertyValues("SOC_LLM_ENABLED=true",
                    "SOC_LLM_API_KEY_MAINTAINER=maintainer-placeholder", "SOC_LLM_MODEL=shared-model",
                    "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048");
            if (override != null) configured = configured.withPropertyValues("SOC_LLM_MODEL_MAINTAINER=" + override);
            configured.run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals("shared-model", context.getBean(SummaryGateway.class).modelIdentity().model());
            });
        }
    }
}
