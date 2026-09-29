package com.smartup24.cms.instance.kwh;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.kwh.controller.KwhSubscriptionController;
import com.smartup24.cms.instance.kwh.service.KwhWebhookService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Plan 10/10, item 3.6: a webhook is changed from the revision the administrator read. */
class KwhSubscriptionControllerTest {

    private final KwhWebhookService service = mock(KwhWebhookService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new KwhSubscriptionController(service))
            .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
            .build();

    @Test
    @DisplayName("3.6: the list carries each webhook's revision")
    void listCarriesRevisions() throws Exception {
        when(service.listSubscriptions())
                .thenReturn(List.of(new KwhWebhookService.SubscriptionView(
                        7L,
                        "Hook",
                        "https://hooks.example.invalid/***",
                        List.of("task.created"),
                        "A",
                        Instant.EPOCH,
                        1L,
                        4L)));

        mvc.perform(get("/api/v1/webhooks/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revision").value(4));
    }

    @Test
    @DisplayName("3.6: a change from a revision answers 204 with the new ETag")
    void changeFromARevision() throws Exception {
        when(service.updateSubscription(7L, null, null, null, "P", 4L)).thenReturn(5L);

        mvc.perform(patch("/api/v1/webhooks/subscriptions/7")
                        .header("If-Match", "\"4\"")
                        .contentType("application/json")
                        .content("{\"state\":\"P\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("ETag", "\"5\""));
        verify(service).updateSubscription(7L, null, null, null, "P", 4L);
    }

    @Test
    @DisplayName("3.6: a change without a revision is 428 and reaches no service")
    void changeWithoutARevision() throws Exception {
        mvc.perform(patch("/api/v1/webhooks/subscriptions/7")
                        .contentType("application/json")
                        .content("{\"state\":\"P\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("precondition_required"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("3.6: a stale revision is 409")
    void staleRevision() throws Exception {
        when(service.updateSubscription(any(), any(), any(), any(), any(), any(Long.class)))
                .thenThrow(com.smartup24.cms.instance.common.web.Revisions.conflict());

        mvc.perform(patch("/api/v1/webhooks/subscriptions/7")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"state\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revision_conflict"));
    }
}
