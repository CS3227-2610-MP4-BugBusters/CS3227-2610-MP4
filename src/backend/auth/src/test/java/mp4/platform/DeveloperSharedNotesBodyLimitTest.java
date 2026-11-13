package mp4.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeveloperSharedNotesBodyLimitTest {
    private final String reportId = UUID.randomUUID().toString();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new DeveloperSharedInvestigationNotesController(null)).build();

    @Test void oversizedDraftBodyIsRejectedBeforeJsonParsingOrStorage() throws Exception {
        mvc.perform(put("/api/developer/reports/{id}/shared-notes/draft", reportId)
                        .contentType("application/json").content("x".repeat(65 * 1024)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test void oversizedShareBodyIsRejectedBeforeJsonParsingOrStorage() throws Exception {
        mvc.perform(post("/api/developer/reports/{id}/shared-notes/share", reportId)
                        .contentType("application/json").content("x".repeat(65 * 1024)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
