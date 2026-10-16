package com.smartup24.cms.instance.config.idempotency;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.md.controller.MdFormStudioController;
import com.smartup24.cms.instance.md.service.MdFormStudioService;
import com.smartup24.cms.instance.support.TestModules;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

class MdFormStudioIdempotencyTest {
    @ParameterizedTest
    @CsvSource({
        "POST,/api/v1/form-studio/drafts/example.products",
        "PUT,/api/v1/form-studio/drafts/example.products",
        "POST,/api/v1/form-studio/%64rafts/example.products",
        "PUT,/api/v1/form-studio/drafts/example.products;x=1",
        "POST,/api/v1/form-studio/drafts/example.products/versions",
        "POST,/api/v1/form-studio/drafts/example.products/%76ersions",
        "POST,/api/v1/form-studio/drafts/example.products/versions;x=1",
        "POST,/api/v1/form-studio/drafts/example.products/versions/1/verify",
        "POST,/api/v1/form-studio/drafts/example.products/versions/1/%76erify",
        "POST,/api/v1/form-studio/drafts/example.products/versions/1/verify;x=1"
    })
    void lifecycleAlwaysReauthorizesInsteadOfReplaying(String method, String path) throws Exception {
        var studio = mock(MdFormStudioService.class);
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
        var mvc = MockMvcBuilders.standaloneSetup(new MdFormStudioController(studio))
                .setCustomHandlerMapping(() -> mapping)
                .addFilters(filter)
                .build();
        mvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
                        .contentType("application/json")
                        .content("{}")
                        .header(
                                IdempotencyFilter.HEADER_IDEMPOTENCY_KEY,
                                UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency_not_supported"));
        verifyNoInteractions(studio, idempotency);
    }
}
