package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import mp4.maintainer.summary.QueueSummaryService;
import mp4.maintainer.summary.SummaryContracts.BudgetAndAdmission;
import mp4.maintainer.summary.SummaryContracts.ModelIdentity;
import mp4.maintainer.summary.SummaryContracts.SummaryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/** Configuration boundary checks for explicit SoCLaaS activation. */
class MaintainerSocLlmConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(MaintainerSummaryConfiguration.class, RuntimeDependencies.class);

    @Configuration(proxyBeanMethods = false)
    static class RuntimeDependencies {
        @Bean DataSource database() { return mock(DataSource.class); }
        @Bean JdbcIndexedSessionRepository sessions() { return mock(JdbcIndexedSessionRepository.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Test
    void disabledByDefaultKeepsSummaryServiceAvailableWithoutActivatingAi() {
        contexts.run(context -> {
            assertNull(context.getStartupFailure(), "ordinary startup must not require SoCLaaS settings");
            assertNotNull(context.getBean(QueueSummaryService.class));
            assertEquals(0, context.getBeansOfType(SummaryGateway.class).size());
            assertEquals(0, context.getBeansOfType(BudgetAndAdmission.class).size());
        });
    }

    @Test
    void explicitlyEnabledCompleteConfigurationSelectsConfiguredModelAndAdmission() {
        contexts.withPropertyValues(
                "SOC_LLM_ENABLED=true",
                "SOC_LLM_API_KEY_MAINTAINER=test-placeholder-key",
                "SOC_LLM_MODEL=contract-test-model",
                "SOC_LLM_RPM=3",
                "SOC_LLM_TPM=2048")
            .run(context -> {
                assertNull(context.getStartupFailure(), "valid explicit configuration must build the AI ports");
                Map<String, SummaryGateway> gateways = context.getBeansOfType(SummaryGateway.class);
                Map<String, BudgetAndAdmission> admissions = context.getBeansOfType(BudgetAndAdmission.class);
                assertAll(
                        () -> assertEquals(1, gateways.size(), "complete configuration must activate one summary gateway"),
                        () -> assertEquals(1, admissions.size(), "complete configuration must activate shared admission"));
                if (gateways.size() == 1) {
                    ModelIdentity model = gateways.values().iterator().next().modelIdentity();
                    assertEquals("contract-test-model", model.model());
                }
            });
    }

    @Test
    void enabledButIncompleteConfigurationRemainsUnavailable() {
        contexts.withPropertyValues(
                "SOC_LLM_ENABLED=true",
                "SOC_LLM_MODEL=contract-test-model",
                "SOC_LLM_RPM=3",
                "SOC_LLM_TPM=2048")
            .run(context -> {
                assertNull(context.getStartupFailure(), "incomplete optional AI configuration must not break startup");
                assertNotNull(context.getBean(QueueSummaryService.class));
                assertEquals(0, context.getBeansOfType(SummaryGateway.class).size());
                assertEquals(0, context.getBeansOfType(BudgetAndAdmission.class).size());
            });
    }
}
