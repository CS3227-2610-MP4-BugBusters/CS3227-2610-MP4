package mp4.developer;

import java.sql.SQLException;
import java.util.UUID;

/** Application boundary for publishing a persisted checklist draft. */
public interface ChecklistShareService {
    enum Outcome {
        SHARED,
        UNCHANGED,
        FORBIDDEN,
        INVALID_STATE,
        STALE_VERSION,
        EMPTY_DRAFT,
        VERSION_LIMIT
    }

    long MAX_SAFE_VERSION = 9_007_199_254_740_991L;

    record ShareRequest(
            ChecklistDraftStore.Actor actor,
            UUID reportId,
            long expectedDraftRevision,
            long expectedReportVersion) {}

    record ShareResult(Outcome outcome, Long reportVersion) {}

    ShareResult share(ShareRequest request) throws SQLException;
}
