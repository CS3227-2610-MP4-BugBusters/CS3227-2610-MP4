package mp4.discussion;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import mp4.discussion.ReportDiscussionService.Comment;
import mp4.discussion.ReportDiscussionService.Page;

/** The comment and page JSON of workflow/reporter/report-discussion-api-contract.md, shared by every role. */
public final class ReportDiscussionJson {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ReportDiscussionJson() {}

    public static String comment(Comment comment) {
        String name = comment.author().displayName();
        return "{\"id\":" + StrictJson.quote(comment.id().toString())
                + ",\"body\":" + StrictJson.quote(comment.body())
                + ",\"author\":{\"id\":" + StrictJson.quote(comment.author().id().toString())
                + ",\"displayName\":" + (name == null ? "null" : StrictJson.quote(name))
                + ",\"role\":" + StrictJson.quote(comment.author().role()) + "}"
                + ",\"createdAt\":" + timestamp(comment.createdAt()) + "}";
    }

    public static String page(Page page) {
        var result = new StringBuilder("{\"items\":[");
        for (int i = 0; i < page.items().size(); i++) {
            if (i > 0) result.append(',');
            result.append(comment(page.items().get(i)));
        }
        return result.append("],\"page\":").append(page.page())
                .append(",\"pageSize\":").append(page.pageSize())
                .append(",\"totalItems\":").append(page.totalItems()).append('}').toString();
    }

    /** ISO-8601 UTC with milliseconds; sub-millisecond digits are truncated, as for Reporter reports. */
    private static String timestamp(Instant instant) {
        return StrictJson.quote(TIMESTAMP.format(instant.truncatedTo(ChronoUnit.MILLIS)));
    }
}
