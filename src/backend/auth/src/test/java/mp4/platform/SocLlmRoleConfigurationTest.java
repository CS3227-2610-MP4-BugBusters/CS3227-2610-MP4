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

    @Test void legacyDeveloperProviderIsNotRegisteredFromOldSoCSettings() {
        contexts.withUserConfiguration(SuggestionProviderConfiguration.class)
                .withPropertyValues(
                        "SOCLAAS_BASE_URL=https://soc.example.invalid/v1",
                        "SOCLAAS_API_KEY=legacy-placeholder")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(SuggestionProvider.class).size(),
                            "Developer suggestions must use the shared role gateway, not the legacy direct provider");
                });
    }

    @Test void validDeveloperRoleKeyWiresOnlyTheSharedGatewaySuggestionProvider() {
        contexts.withUserConfiguration(SocLlmRoleConfiguration.class, SuggestionProviderConfiguration.class)
                .withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_DEVELOPER=developer-placeholder",
                        "SOC_LLM_MODEL=test-model", "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(SocLlmSuggestionProvider.class, context.getBean(SuggestionProvider.class));
                    @SuppressWarnings("unchecked") Map<SocLlmContracts.Role, SocLlmRoleService> services =
                            (Map<SocLlmContracts.Role, SocLlmRoleService>) context.getBean("socLlmRoleServices");
                    assertEquals(java.util.Set.of(SocLlmContracts.Role.DEVELOPER), services.keySet());
                });
    }

    @Test void developerModelOverrideIsUsedBySuggestionProviderWhileReporterRemainsConfigured() {
        contexts.withUserConfiguration(SocLlmRoleConfiguration.class, SuggestionProviderConfiguration.class)
                .withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_REPORTER=reporter-placeholder",
                        "SOC_LLM_API_KEY_DEVELOPER=developer-placeholder", "SOC_LLM_MODEL=shared-model",
                        "SOC_LLM_MODEL_DEVELOPER=developer-model", "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    @SuppressWarnings("unchecked") Map<SocLlmContracts.Role, SocLlmRoleService> services =
                            (Map<SocLlmContracts.Role, SocLlmRoleService>) context.getBean("socLlmRoleServices");
                    assertEquals(java.util.Set.of(SocLlmContracts.Role.REPORTER, SocLlmContracts.Role.DEVELOPER),
                            services.keySet());
                    assertEquals("developer-model",
                            context.getBean(SuggestionProvider.class).modelName());
                });
    }

    @Test void developerSuggestionModelFallsBackToSharedModelWhenOverrideIsMissing() {
        contexts.withUserConfiguration(SocLlmRoleConfiguration.class, SuggestionProviderConfiguration.class)
                .withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_DEVELOPER=developer-placeholder",
                        "SOC_LLM_MODEL=shared-model", "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("shared-model", context.getBean(SuggestionProvider.class).modelName());
                });
    }

    private static org.springframework.core.env.Environment env(String... pairs) {
        var environment = new org.springframework.core.env.StandardEnvironment();
        Map<String, Object> values = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", values));
        return environment;
    }

    @Test void RC09a_reporterModelOverrideIsUsedWhenNonblankAndTrimmed() {
        var environment = env("SOC_LLM_MODEL", "shared-model", "SOC_LLM_MODEL_REPORTER", "  reporter-model ");
        assertEquals("reporter-model", SocLlmRoleConfiguration.modelForRole(environment, SocLlmContracts.Role.REPORTER));
    }

    @Test void RC09b_reporterModelFallsBackToSharedModelWhenOverrideIsBlankOrMissing() {
        assertEquals("shared-model", SocLlmRoleConfiguration.modelForRole(
                env("SOC_LLM_MODEL", "shared-model", "SOC_LLM_MODEL_REPORTER", "   "), SocLlmContracts.Role.REPORTER));
        assertEquals("shared-model", SocLlmRoleConfiguration.modelForRole(
                env("SOC_LLM_MODEL", "shared-model"), SocLlmContracts.Role.REPORTER));
    }

    @Test void RC09_reporterOverrideDoesNotAffectDeveloperOrMaintainerModels() {
        var environment = env("SOC_LLM_MODEL", "shared-model", "SOC_LLM_MODEL_REPORTER", "reporter-model",
                "SOC_LLM_MODEL_DEVELOPER", "developer-model");
        assertEquals("developer-model", SocLlmRoleConfiguration.modelForRole(environment, SocLlmContracts.Role.DEVELOPER));
        assertEquals("shared-model", SocLlmRoleConfiguration.modelForRole(environment, SocLlmContracts.Role.MAINTAINER));
    }

    @Test void blankDeveloperSuggestionModelOverrideFallsBackToSharedModel() {
        contexts.withUserConfiguration(SocLlmRoleConfiguration.class, SuggestionProviderConfiguration.class)
                .withPropertyValues("SOC_LLM_ENABLED=true", "SOC_LLM_API_KEY_DEVELOPER=developer-placeholder",
                        "SOC_LLM_MODEL=shared-model", "SOC_LLM_MODEL_DEVELOPER=   ",
                        "SOC_LLM_RPM=3", "SOC_LLM_TPM=2048")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("shared-model", context.getBean(SuggestionProvider.class).modelName());
                });
    }
}
