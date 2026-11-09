package mp4.maintainer.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Records shared publication without opening another transaction or retaining private content. */
public final class SharedReportHistory {
    private SharedReportHistory() {}

    public static void checklistPublished(Connection connection, UUID reportId, UUID actorId,
                                           long reportVersion, boolean invalidatedProposal) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO report_history"
                + "(id,report_id,type,text,actor_id,report_version) VALUES(?,?,?,?,?,?)")) {
            insert.setObject(1, UUID.randomUUID()); insert.setObject(2, reportId);
            insert.setString(3, "checklist_shared");
            insert.setString(4, invalidatedProposal
                    ? "Published a changed checklist and superseded the pending resolution"
                    : "Published a changed checklist");
            insert.setObject(5, actorId); insert.setLong(6, reportVersion);
            if (insert.executeUpdate() != 1) throw new SQLException("Shared publication history was not recorded");
        }
    }
}
