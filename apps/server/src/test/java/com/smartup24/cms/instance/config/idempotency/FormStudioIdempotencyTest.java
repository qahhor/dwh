package com.smartup24.cms.instance.config.idempotency;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.entity.FormStudioController;
import com.smartup24.cms.instance.common.entity.FormStudioService;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.md.controller.MdSimpleFormController;
import com.smartup24.cms.instance.md.controller.MdSimpleFormDraftController;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.md.service.MdSimpleFormDrafts;
import com.smartup24.cms.instance.support.TestModules;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

class FormStudioIdempotencyTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/form-studio/preview",
                "/api/v1/form-studio/%70review",
                "/api/v1/form-studio/preview;x=1",
                "/api/v1/form-studio/new-forms/prepare",
                "/api/v1/form-studio/new-forms/%70repare",
                "/api/v1/form-studio/new-forms/prepare;x=1",
                "/api/v1/form-studio/new-forms/check-draft",
                "/api/v1/form-studio/new-forms/%63heck-draft",
                "/api/v1/form-studio/new-forms/check-draft;x=1"
            })
    void declarationIsNeverReservedOrStoredEvenForAnAlternativeMappedPath(String path) throws Exception {
        var studio = mock(FormStudioService.class);
        var simple = mock(MdSimpleFormDeclarations.class);
        var drafts = mock(MdSimpleFormDrafts.class);
        var idempotency = mock(IdempotencyService.class);
        var mapping = new RequestMappingHandlerMapping();
        var beans = new StaticListableBeanFactory();
        beans.addBean("mapping", mapping);
        var filter = new IdempotencyFilter(
                idempotency,
                JsonMapper.shared(),
                PackagedProblemMessages.russian(),
                beans.getBeanProvider(RequestMappingHandlerMapping.class),
                TestModules.replayAccess());
        var mvc = MockMvcBuilders.standaloneSetup(
                        (path.contains("check-draft") || path.contains("%63heck"))
                                ? new MdSimpleFormDraftController(drafts)
                                : path.contains("new-forms")
                                        ? new MdSimpleFormController(simple)
                                        : new FormStudioController(studio))
                .setCustomHandlerMapping(() -> mapping)
                .addFilters(filter)
                .build();
        when(idempotency.computeRequestHash(anyString(), anyString(), any(), any()))
                .thenReturn("fixture");
        when(idempotency.claim(any(), any(), any()))
                .thenReturn(
                        new IdempotencyService.Claim(IdempotencyService.ClaimState.ACQUIRED, UUID.randomUUID(), null));
        mvc.perform(post(URI.create(path))
                        .contentType("application/json")
                        .content("{}")
                        .header(
                                IdempotencyFilter.HEADER_IDEMPOTENCY_KEY,
                                UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency_not_supported"));
        verifyNoInteractions(studio, simple, drafts, idempotency);
    }
}
