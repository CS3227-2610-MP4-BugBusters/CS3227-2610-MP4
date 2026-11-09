package mp4.maintainer.workflow;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static mp4.maintainer.workflow.MaintainerContracts.*;

/** Executable fail-closed boundary reserved for the separately gated workflow behaviors. */
public final class MaintainerService {
    private final Repository repository;

    public MaintainerService(Repository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public ReportPage listReports(Actor actor, QueryFilters filters) { throw notImplemented(); }
    public ReportDetail reportDetail(Actor actor, UUID reportId) { throw notImplemented(); }
    public List<Person> developers(Actor actor) { throw notImplemented(); }
    public ReportSummary updatePriority(Actor actor, UUID reportId, PriorityChange change) { throw notImplemented(); }
    public ReportSummary assign(Actor actor, UUID reportId, AssignmentChange change) { throw notImplemented(); }
    public ReportSummary returnResolution(Actor actor, UUID reportId, ReturnResolution change) { throw notImplemented(); }
    public ReportSummary close(Actor actor, UUID reportId, CloseReport change) { throw notImplemented(); }
    public ReportSummary reopen(Actor actor, UUID reportId, ReopenReport change) { throw notImplemented(); }
    public CommentResult addComment(Actor actor, UUID reportId, AddComment comment) { throw notImplemented(); }

    private static ServiceException notImplemented() {
        return new ServiceException("NOT_IMPLEMENTED", 503, "Maintainer workflow is not implemented");
    }

    /** Safe service error shape for a future HTTP adapter. */
    public static final class ServiceException extends RuntimeException {
        private final String code;
        private final int statusCode;

        public ServiceException(String code, int statusCode, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
            if (statusCode < 400 || statusCode > 599) throw new IllegalArgumentException("statusCode must be an error status");
            this.statusCode = statusCode;
        }
        public String code() { return code; }
        public int statusCode() { return statusCode; }
    }
}
