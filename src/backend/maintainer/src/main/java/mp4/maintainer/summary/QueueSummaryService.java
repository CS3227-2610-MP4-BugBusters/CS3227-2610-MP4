package mp4.maintainer.summary;

import java.time.Clock;
import java.util.Objects;

import static mp4.maintainer.summary.SummaryContracts.*;

/** Dependency-injected service boundary. Generation behavior is intentionally not implemented here. */
public final class QueueSummaryService {
    private final CurrentAuthorization authorization;
    private final SnapshotReader snapshots;
    private final SummaryGateway gateway;
    private final BudgetAndAdmission admission;
    private final SummaryCache cache;
    private final Limits limits;
    private final Clock clock;

    public QueueSummaryService(CurrentAuthorization authorization, SnapshotReader snapshots,
                               SummaryGateway gateway, BudgetAndAdmission admission,
                               SummaryCache cache, Limits limits, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Reserved API for the behavior task. Until that task's approved Red gate, fail closed
     * without consulting any port, calling a provider, or changing cache/budget/domain state. */
    public SummaryResult generate(Actor actor, Filters filters, boolean regenerate) {
        throw new NotImplementedException();
    }

    public static final class NotImplementedException extends RuntimeException {
        private final String code = "NOT_IMPLEMENTED";
        public NotImplementedException() { super("Queue summary generation is not implemented"); }
        public String code() { return code; }
    }
}
