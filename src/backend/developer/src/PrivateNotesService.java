package mp4.developer;

import java.sql.SQLException;
import java.util.UUID;
import mp4.developer.ChecklistDraftStore.Actor;

/** Private investigation notes operations for an actor resolved by trusted authentication. */
public interface PrivateNotesService {
    Note read(Actor actor, UUID reportId) throws SQLException, AccessDeniedException;

    SaveResult replace(Actor actor, UUID reportId, String text, long expectedNotesRevision)
            throws SQLException, AccessDeniedException, InvalidStateException, StaleRevisionException,
            RevisionLimitException;

    record Note(String text, long notesRevision) {}
    record SaveResult(long notesRevision) {}

    final class AccessDeniedException extends Exception {}
    final class InvalidStateException extends Exception {}
    final class StaleRevisionException extends Exception {}
    final class RevisionLimitException extends Exception {}
}
