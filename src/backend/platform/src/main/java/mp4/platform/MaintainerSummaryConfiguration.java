package mp4.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;
import mp4.maintainer.storage.JdbcQueueSnapshotReader;
import mp4.maintainer.summary.BoundedSummaryCache;
import mp4.maintainer.summary.QueueSummaryService;
import mp4.maintainer.summary.SummaryContracts.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration(proxyBeanMethods = false)
class MaintainerSummaryConfiguration {
    @Bean QueueSummaryService maintainerQueueSummary(DataSource database, JdbcIndexedSessionRepository sessions,
            Clock clock, ObjectProvider<SummaryGateway> gateways, ObjectProvider<BudgetAndAdmission> budgets) {
        SummaryGateway gateway = gateways.getIfAvailable(UnavailableGateway::new);
        BudgetAndAdmission budget = budgets.getIfAvailable(() -> (actor, model, lease) -> {
            throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);
        });
        Limits limits = Limits.proposedDefaults();
        return new QueueSummaryService(actor -> current(actor, database, sessions, clock),
                new JdbcQueueSnapshotReader(database, clock, limits.maximumContextReports()), gateway, budget,
                new BoundedSummaryCache(clock), limits, clock);
    }

    private static boolean current(Actor actor, DataSource database, JdbcIndexedSessionRepository sessions, Clock clock) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return false;
        HttpSession local = attributes.getRequest().getSession(false);
        if (local == null || !Objects.equals(local.getId(), actor.sessionIdentity())) return false;
        Object created = local.getAttribute(AuthSessionFreshnessFilter.CREATED);
        Object last = local.getAttribute(AuthSessionFreshnessFilter.LAST);
        long now = clock.millis();
        if (!(created instanceof Long start) || !(last instanceof Long activity)
                || start > now || activity > now || now - start >= Duration.ofHours(8).toMillis()
                || now - activity >= Duration.ofMinutes(30).toMillis()) return false;
        org.springframework.session.Session stored = sessions.findById(actor.sessionIdentity());
        if (stored == null) return false;
        Object context = stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(context instanceof SecurityContext security) || security.getAuthentication() == null
                || !(security.getAuthentication().getPrincipal() instanceof AuthProvider.CurrentUser user)
                || !user.id().equals(actor.accountId()) || user.generation() != actor.generation()
                || !"MAINTAINER".equals(user.role())) return false;
        try (var connection = database.getConnection(); var query = connection.prepareStatement(
                "SELECT role,active,auth_generation FROM public.app_accounts WHERE id=?")) {
            query.setObject(1, actor.accountId());
            query.setQueryTimeout(5);
            try (var row = query.executeQuery()) {
                return row.next() && "MAINTAINER".equals(row.getString("role")) && row.getBoolean("active")
                        && row.getLong("auth_generation") == actor.generation();
            }
        } catch (SQLException failure) { throw new SummaryFailure(FailureCode.AI_UNAVAILABLE); }
    }

    private static final class UnavailableGateway implements SummaryGateway {
        @Override public ModelIdentity modelIdentity() { throw new SummaryFailure(FailureCode.AI_UNAVAILABLE); }
        @Override public ProviderMode providerMode() { return ProviderMode.LIVE; }
        @Override public GatewayResponse generate(GatewayRequest request) { throw new SummaryFailure(FailureCode.AI_UNAVAILABLE); }
    }
}
