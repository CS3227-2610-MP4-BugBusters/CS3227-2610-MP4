package mp4.maintainer.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mp4.maintainer.workflow.MaintainerContracts.*;
import mp4.maintainer.summary.SummaryContracts.SummaryResult;

/** Explicit browser wire mappings; internal aggregate wrappers never cross HTTP. */
public final class MaintainerHttpDtos {
    private MaintainerHttpDtos() { }

    public static Map<String, Object> page(ReportPage page) {
        return Map.of("items", page.items().stream().map(MaintainerHttpDtos::summary).toList(), "total", page.total());
    }

    public static Map<String, Object> summary(ReportSummary value) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", value.id()); dto.put("title", value.title()); dto.put("status", value.status().wireLabel());
        dto.put("priority", value.priority().name()); dto.put("reporter", person(value.reporter()));
        dto.put("assignee", value.assignee() == null ? null : person(value.assignee())); dto.put("version", value.version());
        dto.put("createdAt", value.createdAt()); dto.put("updatedAt", value.updatedAt());
        return dto;
    }

    public static Map<String, Object> detail(ReportDetail value) {
        Map<String, Object> dto = new LinkedHashMap<>(summary(value.report()));
        dto.put("description", value.description()); dto.put("reproductionSteps", value.reproductionSteps());
        dto.put("expectedBehavior", value.expectedBehavior()); dto.put("actualBehavior", value.actualBehavior());
        dto.put("environment", value.environment()); dto.put("notes", value.notes().stream().map(MaintainerHttpDtos::entry).toList());
        dto.put("responses", value.responses().stream().map(MaintainerHttpDtos::entry).toList());
        dto.put("comments", value.comments().stream().map(MaintainerHttpDtos::entry).toList());
        dto.put("checklist", value.checklist().stream().map(item -> Map.of(
                "id", item.id(), "text", item.text(), "completed", item.completed())).toList());
        dto.put("currentProposal", value.currentProposal() == null ? null : proposal(value.currentProposal()));
        List<Map<String, Object>> history = new ArrayList<>();
        for (HistoryEvent event : value.history()) history.add(history(event));
        dto.put("history", history);
        return dto;
    }

    public static Map<String, Object> person(Person value) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", value.id());
        dto.put("displayName", value.displayName());
        return dto;
    }

    public static Map<String, Object> commentResult(CommentResult value) {
        return Map.of("comment", entry(value.comment()), "reportVersion", value.reportVersion());
    }

    public static Map<String, Object> summaryResult(SummaryResult value) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (value.filters().status() != null) filters.put("status", value.filters().status().wireLabel());
        if (value.filters().priority() != null) filters.put("priority", value.filters().priority().name());
        if (value.filters().assigneeId() != null) filters.put("assigneeId", value.filters().assigneeId());
        String mode = switch (value.providerMode()) { case NONE -> "none"; case DETERMINISTIC -> "deterministic"; case LIVE -> "soc"; };
        return Map.of("capturedAt", value.capturedAt(), "filters", filters, "included", value.included(),
                "excluded", value.excluded(), "findings", value.findings(), "requiresReview", value.requiresReview(),
                "providerMode", mode, "promptVersion", value.promptVersion());
    }

    private static Map<String, Object> entry(Evidence value) {
        return Map.of("id", value.id(), "author", person(value.author()), "text", value.text(), "createdAt", value.createdAt());
    }

    private static Map<String, Object> entry(Comment value) {
        return Map.of("id", value.id(), "author", person(value.author()), "text", value.text(), "createdAt", value.createdAt());
    }

    private static Map<String, Object> proposal(Proposal value) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", value.id()); dto.put("author", person(value.author())); dto.put("text", value.text());
        dto.put("createdAt", value.createdAt()); dto.put("version", value.version());
        return dto;
    }

    private static Map<String, Object> history(HistoryEvent value) {
        return Map.of("id", value.id(), "type", value.type(), "text", value.text(),
                "actor", person(value.actor()), "createdAt", value.createdAt());
    }
}
