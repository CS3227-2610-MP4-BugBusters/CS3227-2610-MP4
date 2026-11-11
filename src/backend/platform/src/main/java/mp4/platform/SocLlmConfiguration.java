package mp4.platform;

import java.time.Clock;
import mp4.maintainer.summary.SummaryContracts.BudgetAndAdmission;
import mp4.maintainer.summary.SummaryContracts.FailureCode;
import mp4.maintainer.summary.SummaryContracts.GatewayRequest;
import mp4.maintainer.summary.SummaryContracts.GatewayResponse;
import mp4.maintainer.summary.SummaryContracts.ModelIdentity;
import mp4.maintainer.summary.SummaryContracts.ProviderMode;
import mp4.maintainer.summary.SummaryContracts.SummaryFailure;
import mp4.maintainer.summary.SummaryContracts.SummaryGateway;
import mp4.socllm.MaintainerAdmissionAdapter;
import mp4.socllm.SocLlmContracts;
import mp4.socllm.SocLlmHttpProvider;
import mp4.socllm.SocLlmTaskPolicies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Creates Maintainer AI ports only when the complete Maintainer configuration opts in. */
@Configuration(proxyBeanMethods = false)
@Conditional(SocLlmConfiguration.MaintainerReady.class)
class SocLlmConfiguration {
    static final class MaintainerReady implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return enabledAndBudgetsComplete(environment)
                    && SocLlmContracts.isValidApiKey(environment.getProperty("SOC_LLM_API_KEY_MAINTAINER"));
        }
    }

    @Bean
    SummaryGateway maintainerSocLlmSummaryGateway(Environment environment, Clock clock) {
        ModelIdentity identity = new ModelIdentity(
                "SoCLaaS", environment.getRequiredProperty("SOC_LLM_MODEL"), "fixed-endpoint-v1");
        SocLlmHttpProvider provider = new SocLlmHttpProvider(
                environment.getRequiredProperty("SOC_LLM_API_KEY_MAINTAINER"), identity.model(), clock);
        int maxReportBytes = SocLlmTaskPolicies.defaults()
                .forTask(SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY).maxInputBytes();

        return new SummaryGateway() {
            @Override public ModelIdentity modelIdentity() { return identity; }
            @Override public ProviderMode providerMode() { return ProviderMode.LIVE; }

            @Override
            public GatewayResponse generate(GatewayRequest request) {
                mp4.socllm.SocLlmTransactionBoundary.requireNoCallerTransaction();
                String reports = request.reports().value();
                int reportBytes = reports.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (reportBytes > maxReportBytes) throw new SummaryFailure(FailureCode.AI_INVALID_OUTPUT);
                String response = provider.generate(request.instructions().value(), reports,
                        request.outputTokenLimit(), request.deadline());
                return new GatewayResponse(response, identity, ProviderMode.LIVE);
            }
        };
    }

    @Bean
    BudgetAndAdmission maintainerSocLlmAdmission(SocLlmContracts.RoleAdmission admission) {
        return new MaintainerAdmissionAdapter(admission);
    }

    static boolean enabledAndBudgetsComplete(Environment environment) {
        return Boolean.parseBoolean(environment.getProperty("SOC_LLM_ENABLED", "false"))
                && hasValue(environment.getProperty("SOC_LLM_MODEL"))
                && positiveInt(environment.getProperty("SOC_LLM_RPM"))
                && positiveInt(environment.getProperty("SOC_LLM_TPM"));
    }

    static boolean hasValue(String value) { return value != null && !value.isBlank(); }

    private static boolean positiveInt(String value) {
        try { return Integer.parseInt(value) > 0; }
        catch (RuntimeException invalid) { return false; }
    }
}
