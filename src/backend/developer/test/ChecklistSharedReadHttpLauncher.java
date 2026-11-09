package mp4.developer;

import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;
import mp4.developer.ChecklistDraftStore.Actor;
import mp4.developer.ChecklistDraftStore.Role;

/** Mounts local checklist handlers with an explicit test-only Maintainer view grant. */
public final class ChecklistSharedReadHttpLauncher {
    public static void main(String[] args) throws Exception {
        var database = new PGSimpleDataSource();
        database.setURL(requiredEnv("MP4_TEST_JDBC_URL"));
        String password = System.getenv("MP4_TEST_DB_PASSWORD");
        if (password != null) database.setPassword(password);

        UUID authorizedMaintainer = UUID.fromString(requiredEnv("MP4_TEST_MAINTAINER_ID"));
        var sharedHandler = new ChecklistSharedHttpHandler(database,
                (Actor actor, UUID reportId) -> actor.active()
                        && actor.role() == Role.MAINTAINER
                        && actor.id().equals(authorizedMaintainer));
        var shareHandler = new ChecklistShareHttpHandler(new JdbcChecklistShareService(database));
        var draftHandler = new ChecklistDraftHttpHandler(new ChecklistDraftStore(database));
        ChecklistSharedReadHttpHarness.start((exchange, actor) -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/checklist/shared")) {
                sharedHandler.handle(exchange, actor);
            } else if (path.endsWith("/checklist/share")) {
                shareHandler.handle(exchange, actor);
            } else if (path.endsWith("/checklist/draft")) {
                draftHandler.handle(exchange, actor);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        });
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
