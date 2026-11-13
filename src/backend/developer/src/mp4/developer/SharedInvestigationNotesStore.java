package mp4.developer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Stores saved Developer drafts separately from the note explicitly published to Maintainers. */
public final class SharedInvestigationNotesStore {
    public static final long MAX_SAFE_VERSION = 9_007_199_254_740_991L;
    private static final List<String> SHAREABLE_STATES = List.of("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED");

    public enum Outcome { OK, SAVED, SHARED, FORBIDDEN, INVALID_STATE, STALE_VERSION, EMPTY_DRAFT, VERSION_LIMIT }
    public record Actor(UUID id, String role, long generation) {}
    public record Draft(String text, long draftRevision, long reportVersion) {}
    public record Result(Outcome outcome, Draft draft, long reportVersion, boolean changed) {}

    private final DataSource database;

    public SharedInvestigationNotesStore(DataSource database) { this.database = database; }

    public Result read(Actor actor, UUID reportId) throws SQLException {
        if (!isDeveloper(actor)) return result(Outcome.FORBIDDEN);
        try (Connection c = transaction()) {
            try {
                if (!activeDeveloper(c, actor)) { c.commit(); return result(Outcome.FORBIDDEN); }
                long reportVersion;
                try (var report = c.prepareStatement("SELECT assignee_id,version FROM reports WHERE id=? FOR UPDATE")) {
                    report.setObject(1, reportId);
                    try (var row = report.executeQuery()) {
                        if (!row.next() || !actor.id().equals(row.getObject("assignee_id", UUID.class))) {
                            c.commit(); return result(Outcome.FORBIDDEN);
                        }
                        reportVersion = row.getLong("version");
                    }
                }
                if (reportVersion > MAX_SAFE_VERSION) { c.commit(); return result(Outcome.VERSION_LIMIT); }
                Draft draft = new Draft("", 0, reportVersion);
                try (var query = c.prepareStatement(
                        "SELECT text,draft_revision FROM developer_shared_note_drafts WHERE report_id=?")) {
                    query.setObject(1, reportId);
                    try (var row = query.executeQuery()) {
                        if (row.next()) draft = new Draft(row.getString("text"), row.getLong("draft_revision"), reportVersion);
                    }
                }
                if (draft.draftRevision() > MAX_SAFE_VERSION) { c.commit(); return result(Outcome.VERSION_LIMIT); }
                c.commit();
                return new Result(Outcome.OK, draft, reportVersion, false);
            } catch (SQLException | RuntimeException failure) { rollback(c, failure); throw failure; }
        }
    }

    public Result save(Actor actor, UUID reportId, long expectedRevision, String text) throws SQLException {
        if (!isDeveloper(actor)) return result(Outcome.FORBIDDEN);
        try (Connection c = transaction()) {
            try {
                if (!activeDeveloper(c, actor)) { c.commit(); return result(Outcome.FORBIDDEN); }
                Report report = report(c, actor, reportId);
                if (report == null) { c.commit(); return result(Outcome.FORBIDDEN); }
                if (!SHAREABLE_STATES.contains(report.status())) { c.commit(); return result(Outcome.INVALID_STATE); }
                Draft current = draft(c, reportId, report.version());
                if (current.draftRevision() != expectedRevision) { c.commit(); return result(Outcome.STALE_VERSION); }
                if (current.draftRevision() >= MAX_SAFE_VERSION) { c.commit(); return result(Outcome.VERSION_LIMIT); }
                long next = Math.addExact(current.draftRevision(), 1);
                try (var write = c.prepareStatement("""
                        INSERT INTO developer_shared_note_drafts(report_id,text,draft_revision) VALUES(?,?,?)
                        ON CONFLICT(report_id) DO UPDATE SET text=EXCLUDED.text,draft_revision=EXCLUDED.draft_revision
                        """)) {
                    write.setObject(1, reportId); write.setString(2, text); write.setLong(3, next); write.executeUpdate();
                }
                c.commit();
                return new Result(Outcome.SAVED, new Draft(text, next, report.version()), report.version(), false);
            } catch (SQLException | RuntimeException failure) { rollback(c, failure); throw failure; }
        }
    }

    public Result share(Actor actor, UUID reportId, long expectedRevision, long expectedReportVersion)
            throws SQLException {
        if (!isDeveloper(actor)) return result(Outcome.FORBIDDEN);
        try (Connection c = transaction()) {
            try {
                if (!activeDeveloper(c, actor)) { c.commit(); return result(Outcome.FORBIDDEN); }
                Report report = report(c, actor, reportId);
                if (report == null) { c.commit(); return result(Outcome.FORBIDDEN); }
                if (!SHAREABLE_STATES.contains(report.status())) { c.commit(); return result(Outcome.INVALID_STATE); }
                if (report.version() != expectedReportVersion) { c.commit(); return result(Outcome.STALE_VERSION); }
                Draft draft = draft(c, reportId, report.version());
                UUID publishedId = null;
                try (var query = c.prepareStatement(
                        "SELECT published_note_id FROM developer_shared_note_drafts WHERE report_id=? FOR UPDATE")) {
                    query.setObject(1, reportId);
                    try (var row = query.executeQuery()) { if (row.next()) publishedId = row.getObject(1, UUID.class); }
                }
                if (draft.draftRevision() != expectedRevision) { c.commit(); return result(Outcome.STALE_VERSION); }
                if (draft.text().isBlank()) { c.commit(); return result(Outcome.EMPTY_DRAFT); }
                String publishedText = null;
                if (publishedId != null) {
                    try (var query = c.prepareStatement("SELECT text FROM report_shared_notes WHERE id=? AND report_id=?")) {
                        query.setObject(1, publishedId); query.setObject(2, reportId);
                        try (var row = query.executeQuery()) { if (row.next()) publishedText = row.getString(1); }
                    }
                }
                if (draft.text().equals(publishedText)) {
                    c.commit(); return new Result(Outcome.SHARED, null, report.version(), false);
                }
                if (report.version() >= MAX_SAFE_VERSION) { c.commit(); return result(Outcome.VERSION_LIMIT); }
                if (publishedId == null) {
                    publishedId = UUID.randomUUID();
                    try (var insert = c.prepareStatement(
                            "INSERT INTO report_shared_notes(id,report_id,author_id,text) VALUES(?,?,?,?)")) {
                        insert.setObject(1, publishedId); insert.setObject(2, reportId);
                        insert.setObject(3, actor.id()); insert.setString(4, draft.text()); insert.executeUpdate();
                    }
                    try (var update = c.prepareStatement(
                            "UPDATE developer_shared_note_drafts SET published_note_id=? WHERE report_id=?")) {
                        update.setObject(1, publishedId); update.setObject(2, reportId); update.executeUpdate();
                    }
                } else {
                    try (var update = c.prepareStatement(
                            "UPDATE report_shared_notes SET author_id=?,text=? WHERE id=? AND report_id=?")) {
                        update.setObject(1, actor.id()); update.setString(2, draft.text());
                        update.setObject(3, publishedId); update.setObject(4, reportId);
                        if (update.executeUpdate() != 1) throw new SQLException("Published note disappeared");
                    }
                }
                boolean reviewing = "RESOLUTION_PROPOSED".equals(report.status());
                long nextVersion = Math.addExact(report.version(), 1);
                if (reviewing) {
                    try (var proposal = c.prepareStatement(
                            "UPDATE resolution_proposals SET status='SUPERSEDED' WHERE id=? AND report_id=? AND status='PENDING'")) {
                        proposal.setObject(1, report.proposalId()); proposal.setObject(2, reportId);
                        if (proposal.executeUpdate() != 1) throw new SQLException("Current proposal is not pending");
                    }
                    try (var update = c.prepareStatement("UPDATE reports SET status='IN_PROGRESS',version=? WHERE id=?")) {
                        update.setLong(1, nextVersion); update.setObject(2, reportId); update.executeUpdate();
                    }
                } else {
                    try (var update = c.prepareStatement("UPDATE reports SET version=? WHERE id=?")) {
                        update.setLong(1, nextVersion); update.setObject(2, reportId); update.executeUpdate();
                    }
                }
                mp4.maintainer.storage.SharedReportHistory.developerNotePublished(c, reportId, actor.id(), nextVersion, reviewing);
                c.commit();
                return new Result(Outcome.SHARED, null, nextVersion, true);
            } catch (SQLException | RuntimeException failure) { rollback(c, failure); throw failure; }
        }
    }

    private Connection transaction() throws SQLException {
        Connection c = database.getConnection();
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(false);
        return c;
    }

    private static Report report(Connection c, Actor actor, UUID reportId) throws SQLException {
        try (var query = c.prepareStatement(
                "SELECT assignee_id,status,version,current_proposal_id FROM reports WHERE id=? FOR UPDATE")) {
            query.setObject(1, reportId);
            try (var row = query.executeQuery()) {
                if (!row.next() || !actor.id().equals(row.getObject("assignee_id", UUID.class))) return null;
                return new Report(row.getString("status"), row.getLong("version"),
                        row.getObject("current_proposal_id", UUID.class));
            }
        }
    }

    private static Draft draft(Connection c, UUID reportId, long reportVersion) throws SQLException {
        try (var query = c.prepareStatement(
                "SELECT text,draft_revision FROM developer_shared_note_drafts WHERE report_id=?")) {
            query.setObject(1, reportId);
            try (var row = query.executeQuery()) {
                return row.next() ? new Draft(row.getString("text"), row.getLong("draft_revision"), reportVersion)
                        : new Draft("", 0, reportVersion);
            }
        }
    }

    private static boolean isDeveloper(Actor actor) { return actor != null && actor.id() != null && "DEVELOPER".equals(actor.role()); }
    private static boolean activeDeveloper(Connection c, Actor actor) throws SQLException {
        try (var query = c.prepareStatement("SELECT role,active,auth_generation FROM app_accounts WHERE id=? FOR UPDATE")) {
            query.setObject(1, actor.id());
            try (var row = query.executeQuery()) {
                return row.next() && row.getBoolean("active") && "DEVELOPER".equals(row.getString("role"))
                        && actor.generation() == row.getLong("auth_generation");
            }
        }
    }
    private static Result result(Outcome outcome) { return new Result(outcome, null, 0, false); }
    private static void rollback(Connection c, Throwable failure) {
        try { c.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }
    private record Report(String status, long version, UUID proposalId) {}
}
