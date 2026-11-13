package mp4.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/** Wiring for the Reporter report check (task 2.3): Reporter key only, Reporter model, off without a key. */
class ReportClarifyConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(MaintainerSummaryConfiguration.class, SocLlmRoleConfiguration.class,
                    ReportClarifyConfiguration.class, RuntimeDependencies.class);

    @Configuration(proxyBeanMethods = false)
    static class RuntimeDependencies {
        @Bean DataSource database() { return mock(DataSource.class); }
        @Bean JdbcIndexedSessionRepository sessions() { return mock(JdbcIndexedSessionRepository.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Test void reporterKeyWiresSharedGatewayProviderWithReporterModel() {
        contexts.withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_REPORTER=reporter-placeholder",
                "SOC_LLM_MODEL=shared-model", "SOC_LLM_MODEL_REPORTER=reporter-model",
                "SOC_LLM_RPM=3", "SOC_LLM_TPM=77824").run(context -> {
            assertNull(context.getStartupFailure());
            ReportClarifyProvider provider = context.getBean(ReportClarifyProvider.class);
            assertInstanceOf(SocLlmReportClarifyProvider.class, provider);
            assertEquals("reporter-model", provider.modelName());
            assertNotNull(context.getBean(ReportClarifyService.class));
        });
    }

    /** RC-08b: a valid Reporter key alone does not turn the check on while SoC LLM is disabled or unset. */
    @Test void reporterKeyWithSocLlmDisabledOrUnsetLeavesTheCheckUnavailable() {
        String[][] settings = {
            {"SOC_LLM_ENABLED=false", "SOC_LLM_API_KEY_REPORTER=reporter-placeholder", "SOC_LLM_MODEL=shared-model",
                "SOC_LLM_RPM=3", "SOC_LLM_TPM=77824"},
            {"SOC_LLM_API_KEY_REPORTER=reporter-placeholder", "SOC_LLM_MODEL=shared-model",
                "SOC_LLM_RPM=3", "SOC_LLM_TPM=77824"}
        };
        for (String[] properties : settings) {
            contexts.withPropertyValues(properties).run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(0, context.getBeansOfType(ReportClarifyProvider.class).size());
                assertFalse(context.containsBean("socLlmRoleServices"));
                var reply = context.getBean(ReportClarifyService.class).handle(UUID.randomUUID(),
                        "{\"title\":\"T\",\"description\":\"D\"}".getBytes(StandardCharsets.UTF_8));
                assertEquals(503, reply.status());
                assertEquals("AI_UNAVAILABLE", reply.code());
            });
        }
    }

    @Test void developerKeyAloneLeavesTheCheckUnavailable() {
        contexts.withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_DEVELOPER=developer-placeholder",
                "SOC_LLM_MODEL=shared-model", "SOC_LLM_RPM=3", "SOC_LLM_TPM=77824").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(ReportClarifyProvider.class).size());
            var reply = context.getBean(ReportClarifyService.class).handle(UUID.randomUUID(),
                    "{\"title\":\"T\",\"description\":\"D\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(503, reply.status());
            assertEquals("AI_UNAVAILABLE", reply.code());
        });
    }
}
