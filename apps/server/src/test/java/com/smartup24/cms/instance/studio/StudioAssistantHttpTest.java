package com.smartup24.cms.instance.studio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Disabled assistant has no network side effects and requires its own permission (ADR-0039). */
class StudioAssistantHttpTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        signIn(Set.of("*.*"));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void defaultIsUnavailableAndNeverCreatesAForm() throws Exception {
        mvc.perform(get("/api/v1/studio/assistant"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(post("/api/v1/studio/assistant/drafts")
                        .contentType("application/json")
                        .content("{\"prompt\":\"Create a catalogue\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void settingsPermissionDoesNotGrantAssistantAccess() throws Exception {
        signIn(Set.of("md.settings.update"));
        mvc.perform(get("/api/v1/studio/assistant")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/studio/assistant/drafts")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(
                new SecurityContext.KauthPrincipal(1L, "test", "", 1L, false, permissions, 1L, false, 0L, null));
    }
}
