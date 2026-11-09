package mp4.maintainer.storage;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import mp4.maintainer.workflow.MaintainerContracts.*;
import mp4.maintainer.workflow.MaintainerService.ServiceException;

/** Reads Maintainer projections from the existing shared report and evidence tables. */
public final class JdbcMaintainerRepository implements Repository {
    private final DataSource dataSource;

    public JdbcMaintainerRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public ReportPage listReports(Actor actor, QueryFilters filters) {
        Objects.requireNonNull(filters, "filters");
        return readSnapshot(connection -> {
            authorize(connection, actor);
            String where = " WHERE (?::text IS NULL OR r.status = ?)"
                    + " AND (?::text IS NULL OR r.priority = ?)"
                    + " AND (?::uuid IS NULL OR r.assignee_id = ?)";
            long total;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT count(*) FROM reports r" + where)) {
                bindFilters(count, filters);
                try (ResultSet rows = count.executeQuery()) { rows.next(); total = rows.getLong(1); }
            }

            List<ReportSummary> items = new ArrayList<>();
            String sql = "SELECT r.id,r.title,r.status,r.priority,r.reporter_id,r.assignee_id,r.version,"
                    + "r.created_at,r.updated_at, reporter.display_name AS reporter_name,"
                    + "assignee.display_name AS assignee_name FROM reports r"
                    + " LEFT JOIN public.app_accounts reporter ON reporter.id=r.reporter_id"
                    + " LEFT JOIN public.app_accounts assignee ON assignee.id=r.assignee_id"
                    + where + " ORDER BY r.updated_at DESC,r.id ASC LIMIT ? OFFSET ?";
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                int next = bindFilters(query, filters);
                query.setInt(next++, filters.limit());
                query.setInt(next, filters.offset());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) items.add(summary(rows));
                }
            }
            return new ReportPage(items, total);
        });
    }

    @Override public ReportDetail findReportDetail(Actor actor, UUID reportId) {
        Objects.requireNonNull(reportId, "reportId");
        return readSnapshot(connection -> {
            authorize(connection, actor);
            ReportRow report = findReport(connection, reportId);
            return report == null ? null : detail(connection, report);
        });
    }

    @Override public List<Person> listActiveDevelopers(Actor actor) {
        return readSnapshot(connection -> {
            authorize(connection, actor);
            List<Person> people = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id,display_name FROM public.app_accounts WHERE role='DEVELOPER' AND active=true")) {
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) people.add(new Person(rows.getObject("id", UUID.class), rows.getString("display_name")));
                }
            }
            people.sort(Comparator.comparing(Person::displayName).thenComparing(person -> person.id().toString()));
            return List.copyOf(people);
        });
    }

    @Override public <T> T inTransaction(TransactionWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                boundWaits(connection);
                T result = work.apply(new UnitOfWork(connection));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof ServiceException safe) throw safe;
                throw unavailable();
            }
        } catch (SQLException failure) { throw unavailable(); }
    }

    private final class UnitOfWork implements Transaction {
        private final Connection connection;
        private UUID lockedReport;

        private UnitOfWork(Connection connection) { this.connection = connection; }

        @Override public List<AccountEligibility> lockAccountsInIdOrder(Actor expected, List<UUID> ids) {
            if (lockedReport != null) throw unavailable();
            return execute(c -> {
                List<AccountEligibility> result = new ArrayList<>();
                java.sql.Array accounts = c.createArrayOf("uuid", ids.toArray(UUID[]::new));
                try (var query = c.prepareStatement("SELECT id,role,active,auth_generation FROM public.app_accounts"
                        + " WHERE id=ANY(?) ORDER BY id FOR UPDATE")) {
                    query.setArray(1, accounts);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) result.add(new AccountEligibility(rows.getObject("id", UUID.class),
                                Role.valueOf(rows.getString("role")), rows.getBoolean("active"), rows.getLong("auth_generation")));
                    }
                } finally { accounts.free(); }
                return List.copyOf(result);
            });
        }

        @Override public ReportCore lockReport(UUID id) {
            return execute(c -> {
                if (lockedReport != null && !lockedReport.equals(id)) throw unavailable();
                ReportRow row = findReport(c, id, true);
                lockedReport = id;
                return row == null ? null : core(row);
            });
        }

        @Override public AggregateChildren loadChildren(UUID id) {
            requireReport(id);
            return execute(c -> new AggregateChildren(evidence(c, "report_shared_notes", id), checklist(c, id),
                    evidence(c, "report_responses", id), comments(c, id), proposals(c, id), history(c, id)));
        }

        @Override public Person person(UUID id) { return execute(c -> JdbcMaintainerRepository.this.person(c, id)); }

        @Override public ReportSummary projectSummary(ReportCore report) {
            requireReport(report.id());
            return execute(c -> {
                ReportRow stored = findReport(c, report.id());
                if (stored == null) throw unavailable();
                return new ReportSummary(stored.id, stored.title, stored.status, stored.priority,
                        JdbcMaintainerRepository.this.person(c, stored.reporterId),
                        stored.assigneeId == null ? null : JdbcMaintainerRepository.this.person(c, stored.assigneeId),
                        stored.version, stored.createdAt, stored.updatedAt);
            });
        }

        @Override public void updateReport(ReportCore report) {
            requireReport(report.id());
            execute(c -> {
                try (var update = c.prepareStatement("UPDATE public.reports SET priority=?,status=?,assignee_id=?,"
                        + "current_proposal_id=?,version=?,updated_at=? WHERE id=? AND version=?")) {
                    update.setString(1, report.priority().name()); update.setString(2, storageStatus(report.status()));
                    update.setObject(3, report.assigneeId()); update.setObject(4, report.currentProposalId());
                    update.setLong(5, report.version()); update.setTimestamp(6, Timestamp.from(report.updatedAt()));
                    update.setObject(7, report.id()); update.setLong(8, report.version() - 1);
                    if (update.executeUpdate() != 1) throw stale();
                }
                return null;
            });
        }

        @Override public void appendHistory(UUID id, HistoryEvent event) {
            requireReport(id);
            execute(c -> {
                try (var insert = c.prepareStatement("INSERT INTO report_history"
                        + "(id,report_id,type,text,actor_id,created_at,report_version)"
                        + " SELECT ?,?,?,?,?,?,version FROM reports WHERE id=?")) {
                    insert.setObject(1, event.id()); insert.setObject(2, id); insert.setString(3, event.type());
                    insert.setString(4, event.text()); insert.setObject(5, event.actor().id());
                    insert.setTimestamp(6, Timestamp.from(event.createdAt())); insert.setObject(7, id);
                    if (insert.executeUpdate() != 1) throw unavailable();
                }
                return null;
            });
        }

        @Override public void insertComment(UUID id, Comment comment) {
            requireReport(id);
            execute(c -> {
                try (var insert = c.prepareStatement("INSERT INTO report_comments(id,report_id,author_id,text,created_at) VALUES(?,?,?,?,?)")) {
                    insert.setObject(1, comment.id()); insert.setObject(2, id); insert.setObject(3, comment.author().id());
                    insert.setString(4, comment.text()); insert.setTimestamp(5, Timestamp.from(comment.createdAt()));
                    if (insert.executeUpdate() != 1) throw unavailable();
                }
                return null;
            });
        }

        @Override public void updateProposal(UUID id, Proposal proposal) {
            requireReport(id);
            execute(c -> {
                try (var update = c.prepareStatement("UPDATE resolution_proposals SET status=?"
                        + " WHERE report_id=? AND id=? AND version=? AND status='PENDING'")) {
                    update.setString(1, proposal.status().name()); update.setObject(2, id);
                    update.setObject(3, proposal.id()); update.setLong(4, proposal.version());
                    if (update.executeUpdate() != 1) throw stale();
                }
                return null;
            });
        }

        private void requireReport(UUID id) { if (!id.equals(lockedReport)) throw unavailable(); }
        private <T> T execute(SqlRead<T> operation) {
            try { return operation.run(connection); }
            catch (SQLException failure) { throw unavailable(); }
        }
    }

    /** Authorization callback for the legacy shared-checklist endpoint. The report row is already locked there. */
    public boolean canViewSharedChecklist(Actor actor, UUID reportId) throws SQLException {
        if (actor == null || actor.role() != Role.MAINTAINER || !actor.active()) return false;
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                boundWaits(connection);
                authorize(connection, actor);
                try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM reports WHERE id=?")) {
                    query.setObject(1, reportId);
                    try (ResultSet rows = query.executeQuery()) {
                        boolean exists = rows.next();
                        connection.commit();
                        return exists;
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private ReportDetail detail(Connection connection, ReportRow row) throws SQLException {
        Person reporter = person(connection, row.reporterId);
        Person assignee = row.assigneeId == null ? null : person(connection, row.assigneeId);
        ReportSummary summary = new ReportSummary(row.id, row.title, row.status, row.priority, reporter, assignee,
                row.version, row.createdAt, row.updatedAt);
        List<Evidence> notes = evidence(connection, "report_shared_notes", row.id);
        List<Evidence> responses = evidence(connection, "report_responses", row.id);
        List<Comment> comments = comments(connection, row.id);
        List<ChecklistItem> checklist = checklist(connection, row.id);
        List<Proposal> proposals = proposals(connection, row.id);
        List<HistoryEvent> history = history(connection, row.id);
        Proposal current = null;
        List<Proposal> retained = new ArrayList<>();
        for (Proposal proposal : proposals) {
            if (proposal.id().equals(row.currentProposalId) && proposal.status() == ProposalStatus.PENDING) current = proposal;
            else retained.add(proposal);
        }
        // The established browser DTO has no proposalHistory slot. Preserve retained proposal metadata in its
        // readable history list, without synthesizing an author for legacy rows that have none recorded.
        for (Proposal proposal : retained) {
            Person author = proposal.author();
            String created = proposal.createdAt().equals(Instant.EPOCH) ? "time not recorded" : proposal.createdAt().toString();
            String retainedText = "Proposal " + proposal.id() + " (version " + proposal.version() + ", "
                    + proposal.status().name() + ") by " + author.displayName() + " [" + author.id()
                    + "] at " + created + "\n\n" + proposal.text();
            history.add(new HistoryEvent(UUID.nameUUIDFromBytes(("proposal-history:" + proposal.id()).getBytes(StandardCharsets.UTF_8)),
                    "Proposal retained", retainedText, author, proposal.createdAt()));
        }
        history.sort(Comparator.comparing(HistoryEvent::createdAt).thenComparing(event -> event.id().toString()));
        return new ReportDetail(summary, row.description, row.reproductionSteps, row.expectedBehavior,
                row.actualBehavior, row.environment, notes, checklist, responses, comments, current, retained, history);
    }

    private ReportRow findReport(Connection connection, UUID id) throws SQLException {
        return findReport(connection, id, false);
    }

    private ReportRow findReport(Connection connection, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT r.id,r.title,r.description,r.reporter_id,r.assignee_id,r.status,r.priority,r.version,
                       r.created_at,r.updated_at,r.reproduction_steps,r.expected_behavior,r.actual_behavior,
                       r.environment,r.current_proposal_id
                FROM reports r WHERE r.id=?
                """ + (lock ? " FOR UPDATE" : ""))) {
            query.setObject(1, id);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? reportRow(rows) : null; }
        }
    }

    private List<Evidence> evidence(Connection connection, String table, UUID reportId) throws SQLException {
        // Table names are internal constants at each call site; no request value is interpolated into SQL.
        List<Evidence> result = new ArrayList<>();
        String sql = "SELECT id,author_id,text,created_at FROM " + table + " WHERE report_id=? ORDER BY created_at,id";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, reportId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new Evidence(rows.getObject("id", UUID.class),
                        person(connection, rows.getObject("author_id", UUID.class)), rows.getString("text"), instant(rows, "created_at")));
            }
        }
        return result;
    }

    private List<Comment> comments(Connection connection, UUID reportId) throws SQLException {
        List<Comment> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,author_id,text,created_at FROM report_comments WHERE report_id=? ORDER BY created_at,id")) {
            query.setObject(1, reportId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new Comment(rows.getObject("id", UUID.class),
                        person(connection, rows.getObject("author_id", UUID.class)), rows.getString("text"), instant(rows, "created_at")));
            }
        }
        return result;
    }

    private List<ChecklistItem> checklist(Connection connection, UUID reportId) throws SQLException {
        List<ChecklistItem> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT shared_steps FROM investigation_checklists WHERE report_id=?")) {
            query.setObject(1, reportId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return result;
                java.sql.Array array = rows.getArray("shared_steps");
                try {
                    String[] steps = (String[]) array.getArray();
                    for (int index = 0; index < steps.length; index++) {
                        UUID key = UUID.nameUUIDFromBytes((reportId + ":shared-step:" + index + ":" + steps[index]).getBytes(StandardCharsets.UTF_8));
                        result.add(new ChecklistItem(key, steps[index], false));
                    }
                } finally { array.free(); }
            }
        }
        return result;
    }

    private List<Proposal> proposals(Connection connection, UUID reportId) throws SQLException {
        List<Proposal> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT p.id,p.version,p.status,p.contents,m.author_id,m.created_at
                FROM resolution_proposals p LEFT JOIN resolution_proposal_metadata m ON m.proposal_id=p.id
                WHERE p.report_id=? ORDER BY COALESCE(m.created_at,'epoch'::timestamptz),p.id
                """)) {
            query.setObject(1, reportId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    UUID id = rows.getObject("id", UUID.class);
                    UUID authorId = rows.getObject("author_id", UUID.class);
                    Instant created = instantOrNull(rows, "created_at");
                    if (created == null) created = Instant.EPOCH;
                    Person author = authorId == null ? new Person(new UUID(0, 0), "Author not recorded") : person(connection, authorId);
                    result.add(new Proposal(id, rows.getLong("version"), ProposalStatus.valueOf(rows.getString("status")),
                            rows.getString("contents"), author, created));
                }
            }
        }
        return result;
    }

    private List<HistoryEvent> history(Connection connection, UUID reportId) throws SQLException {
        List<HistoryEvent> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,type,text,actor_id,created_at FROM report_history WHERE report_id=? ORDER BY created_at,id")) {
            query.setObject(1, reportId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new HistoryEvent(rows.getObject("id", UUID.class), rows.getString("type"),
                        rows.getString("text"), person(connection, rows.getObject("actor_id", UUID.class)), instant(rows, "created_at")));
            }
        }
        return result;
    }

    private Person person(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT display_name FROM public.app_accounts WHERE id=?")) {
            query.setObject(1, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new Person(id, rows.getString("display_name")) : new Person(id, "Account no longer available");
            }
        }
    }

    private static void authorize(Connection connection, Actor actor) throws SQLException {
        if (actor == null || actor.role() != Role.MAINTAINER || !actor.active()) throw forbidden();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT role,active,auth_generation FROM public.app_accounts WHERE id=?")) {
            query.setObject(1, actor.accountId());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !"MAINTAINER".equals(rows.getString("role")) || !rows.getBoolean("active")) throw forbidden();
                if (rows.getLong("auth_generation") != actor.generation()) throw unauthenticated();
            }
        }
    }

    private <T> T readSnapshot(SqlRead<T> read) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                boundWaits(connection);
                T value = read.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw unavailable();
            }
        } catch (SQLException failure) { throw unavailable(); }
    }

    private static int bindFilters(PreparedStatement query, QueryFilters filters) throws SQLException {
        String status = filters.status() == null ? null : storageStatus(filters.status());
        String priority = filters.priority() == null ? null : filters.priority().name();
        query.setString(1, status); query.setString(2, status);
        query.setString(3, priority); query.setString(4, priority);
        query.setObject(5, filters.assigneeId()); query.setObject(6, filters.assigneeId());
        return 7;
    }

    private static String storageStatus(Status status) {
        return switch (status) {
            case SUBMITTED -> "SUBMITTED"; case ASSIGNED -> "ASSIGNED"; case IN_PROGRESS -> "IN_PROGRESS";
            case RESOLUTION_PROPOSED -> "RESOLUTION_PROPOSED"; case CLOSED -> "CLOSED";
        };
    }

    private static ReportSummary summary(ResultSet rows) throws SQLException {
        UUID assigneeId = rows.getObject("assignee_id", UUID.class);
        String assigneeName = rows.getString("assignee_name");
        return new ReportSummary(rows.getObject("id", UUID.class), rows.getString("title"),
                Status.fromStorage(rows.getString("status")), Priority.valueOf(rows.getString("priority")),
                new Person(rows.getObject("reporter_id", UUID.class), displayName(rows.getString("reporter_name"))),
                assigneeId == null ? null : new Person(assigneeId, displayName(assigneeName)), rows.getLong("version"),
                instant(rows, "created_at"), instant(rows, "updated_at"));
    }

    private static ReportRow reportRow(ResultSet rows) throws SQLException {
        return new ReportRow(rows.getObject("id", UUID.class), rows.getString("title"), rows.getString("description"),
                rows.getObject("reporter_id", UUID.class), rows.getObject("assignee_id", UUID.class),
                Status.fromStorage(rows.getString("status")), Priority.valueOf(rows.getString("priority")), rows.getLong("version"),
                instant(rows, "created_at"), instant(rows, "updated_at"), rows.getString("reproduction_steps"),
                rows.getString("expected_behavior"), rows.getString("actual_behavior"), rows.getString("environment"),
                rows.getObject("current_proposal_id", UUID.class));
    }

    private static ReportCore core(ReportRow row) {
        return new ReportCore(row.id, row.title, row.description, row.reporterId, row.assigneeId,
                row.status, row.priority, row.version, row.createdAt, row.updatedAt, row.reproductionSteps,
                row.expectedBehavior, row.actualBehavior, row.environment, row.currentProposalId);
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException { return rows.getTimestamp(column).toInstant(); }
    private static String displayName(String value) { return value == null ? "Account no longer available" : value; }
    private static void boundWaits(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL statement_timeout = '5s'");
            statement.execute("SET LOCAL lock_timeout = '5s'");
        }
    }
    private static Instant instantOrNull(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }
    private static ServiceException forbidden() { return new ServiceException("FORBIDDEN", 403, "Active Maintainer access is required"); }
    private static ServiceException unauthenticated() { return new ServiceException("UNAUTHENTICATED", 401, "The current Maintainer session is no longer valid"); }
    private static ServiceException unavailable() { return new ServiceException("TEMPORARILY_UNAVAILABLE", 503, "Maintainer workflow is temporarily unavailable"); }
    private static ServiceException stale() { return new ServiceException("STALE_VERSION", 409, "The report or proposal has changed"); }

    @FunctionalInterface private interface SqlRead<T> { T run(Connection connection) throws SQLException; }
    private record ReportRow(UUID id, String title, String description, UUID reporterId, UUID assigneeId,
            Status status, Priority priority, long version, Instant createdAt, Instant updatedAt,
            String reproductionSteps, String expectedBehavior, String actualBehavior, String environment,
            UUID currentProposalId) { }
}
