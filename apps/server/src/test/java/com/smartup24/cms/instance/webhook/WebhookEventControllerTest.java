package com.smartup24.cms.instance.webhook;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.webhook.controller.WebhookEventController;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Plan 10/10, item 5.4 (ADR-0032, 6.9): event catalog for webhook subscriptions.
 */
class WebhookEventControllerTest {

    private final WebhookService service = mock(WebhookService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WebhookEventController(service))
            .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
            .build();

    @Test
    @DisplayName("5.4: GET /api/v1/webhooks/events answers the event catalog")
    void listsAvailableEvents() throws Exception {
        when(service.listEvents())
                .thenReturn(List.of(
                        new WebhookService.WebhookEventView(
                                "*",
                                null,
                                null,
                                null,
                                "settings.webhooks.event_all",
                                "settings.webhooks.event_all_desc"),
                        new WebhookService.WebhookEventView(
                                "notes.created", "ms.notes", "notes", "created", null, null)));

        mvc.perform(get("/api/v1/webhooks/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("*"))
                .andExpect(jsonPath("$[1].code").value("notes.created"))
                .andExpect(jsonPath("$[1].entity").value("ms.notes"));
    }
}
