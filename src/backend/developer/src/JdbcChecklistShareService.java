package mp4.developer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

import static mp4.developer.ChecklistShareService.Outcome;

/** Publishes a persisted checklist draft under the report lifecycle lock. */
public final class JdbcChecklistShareService implements ChecklistShareService {
    private static final List<String> SHAREABLE_STATES =
            List.of("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED");

    private final DataSource database;
    private final PublicationObserver publicationObserver;

    /** Shared application integration runs inside the same publication transaction. */
    @FunctionalInterface
    public interface PublicationObserver {
        void published(Connection connection, ShareRequest request, long reportVersion, boolean invalidatedProposal)
                throws SQLException;
    }

    public JdbcChecklistShareService(DataSource database) {
        this(database, (connection, request, version, invalidated) -> {});
    }

    public JdbcChecklistShareService(DataSource database, PublicationObserver publicationObserver) {
        this.database = database;
        this.publicationObserver = java.util.Objects.requireNonNull(publicationObserver);
    }

    @Override
    public ShareResult share(ShareRequest request) throws SQLException {
        if (!request.actor().active() || request.actor().role() != ChecklistDraftStore.Role.DEVELOPER) {
            return new ShareResult(Outcome.FORBIDDEN, null);
        }
        if (request.expectedDraftRevision() < 0 || request.expectedReportVersion() < 0) {
            throw new IllegalArgumentException("Expected revisions must be nonnegative");
        }

        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                ShareResult result = shareUnderReportLock(connection, request);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private ShareResult shareUnderReportLock(Connection connection, ShareRequest request) throws SQLException {
        String status;
        long reportVersion;
        Object proposalId;
        try (var report = connection.prepareStatement(
                "SELECT assignee_id, status, version, current_proposal_id FROM reports WHERE id = ? FOR UPDATE")) {
            report.setObject(1, request.reportId());
            try (var row = report.executeQuery()) {
                if (!row.next() || !request.actor().id().equals(row.getObject("assignee_id", java.util.UUID.class))) {
                    return new ShareResult(Outcome.FORBIDDEN, null);
                }
                status = row.getString("status");
                reportVersion = row.getLong("version");
                proposalId = row.getObject("current_proposal_id");
            }
        }

        if (!SHAREABLE_STATES.contains(status)) {
            return new ShareResult(Outcome.INVALID_STATE, null);
        }
        if (reportVersion != request.expectedReportVersion()) {
            return new ShareResult(Outcome.STALE_VERSION, null);
        }

        long draftRevision = 0;
        List<String> draftSteps = List.of();
        List<String> sharedSteps = List.of();
        try (var checklist = connection.prepareStatement(
                "SELECT draft_steps, draft_revision, shared_steps FROM investigation_checklists WHERE report_id = ?")) {
            checklist.setObject(1, request.reportId());
            try (var row = checklist.executeQuery()) {
                if (row.next()) {
                    draftRevision = row.getLong("draft_revision");
                    draftSteps = List.of((String[]) row.getArray("draft_steps").getArray());
                    sharedSteps = List.of((String[]) row.getArray("shared_steps").getArray());
                }
            }
        }
        if (draftRevision != request.expectedDraftRevision()) {
            return new ShareResult(Outcome.STALE_VERSION, null);
        }
        if (draftSteps.isEmpty()) {
            return new ShareResult(Outcome.EMPTY_DRAFT, null);
        }
        if (draftSteps.equals(sharedSteps)) {
            return new ShareResult(Outcome.UNCHANGED, reportVersion);
        }
        if (reportVersion >= MAX_SAFE_VERSION) {
            return new ShareResult(Outcome.VERSION_LIMIT, null);
        }

        var publishedSteps = connection.createArrayOf("text", draftSteps.toArray(String[]::new));
        try (var update = connection.prepareStatement(
                "UPDATE investigation_checklists SET shared_steps = ? WHERE report_id = ?")) {
            update.setArray(1, publishedSteps);
            update.setObject(2, request.reportId());
            if (update.executeUpdate() != 1) {
                throw new SQLException("Checklist disappeared while its report was locked");
            }
        } finally {
            publishedSteps.free();
        }

        long nextReportVersion = Math.addExact(reportVersion, 1);
        if ("RESOLUTION_PROPOSED".equals(status)) {
            try (var supersede = connection.prepareStatement(
                    "UPDATE resolution_proposals SET status = 'SUPERSEDED' "
                            + "WHERE id = ? AND report_id = ? AND status = 'PENDING'")) {
                supersede.setObject(1, proposalId);
                supersede.setObject(2, request.reportId());
                if (supersede.executeUpdate() != 1) {
                    throw new SQLException("Current pending resolution proposal does not belong to the report");
                }
            }
            try (var updateReport = connection.prepareStatement(
                    "UPDATE reports SET status = 'IN_PROGRESS', version = ? WHERE id = ?")) {
                updateReport.setLong(1, nextReportVersion);
                updateReport.setObject(2, request.reportId());
                updateReport.executeUpdate();
            }
        } else {
            try (var updateReport = connection.prepareStatement(
                    "UPDATE reports SET version = ? WHERE id = ?")) {
                updateReport.setLong(1, nextReportVersion);
                updateReport.setObject(2, request.reportId());
                updateReport.executeUpdate();
            }
        }
        publicationObserver.published(connection, request, nextReportVersion, "RESOLUTION_PROPOSED".equals(status));
        return new ShareResult(Outcome.SHARED, nextReportVersion);
    }
}
