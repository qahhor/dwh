package com.smartup24.cms.instance.md;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.md.controller.MdSimpleFormController;
import com.smartup24.cms.instance.md.controller.MdSimpleFormDraftController;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.md.service.MdSimpleFormDrafts;
import com.smartup24.cms.instance.support.TestModules;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class MdSimpleFormDraftTest {
    private static final String URL = "/api/v1/form-studio/new-forms/check-draft";
    private static final String DRAFT = """
            {"formatVersion":1,"kind":"simple-form","module":{"code":"","title":{"ru":"","uz":"","en":""}},
            "entity":{"name":"unfinished name","title":{"ru":"Черновик ","uz":"","en":""}},
            "fields":[{"id":"","type":"text","labels":{"ru":"","uz":"","en":""},
            "required":true,"listed":false,"maxLength":null}]}
            """;
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new MdSimpleFormController(new MdSimpleFormDeclarations(JsonMapper.shared())),
                    new MdSimpleFormDraftController(
                            new MdSimpleFormDrafts(new MdSimpleFormDeclarations(JsonMapper.shared()))))
            .addInterceptors(TestModules.permissions())
            .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
            .build();

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void restoresIncompleteTypedInputWithoutPreparingAPackage() throws Exception {
        signIn();
        mvc.perform(post(URL).contentType("application/json").content(DRAFT))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.module.code").value(""))
                .andExpect(jsonPath("$.entity.name").value("unfinished name"))
                .andExpect(jsonPath("$.fields[0].id").value(""))
                .andExpect(jsonPath("$.checksum").doesNotExist());
        mvc.perform(post("/api/v1/form-studio/new-forms/prepare")
                        .contentType("application/json")
                        .content(DRAFT))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesDuplicateUnknownAndWrongTypedInputAndChecksAuthority() throws Exception {
        signIn();
        for (String invalid : new String[] {
            DRAFT.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            DRAFT.replace("\"kind\":", "\"sql\":\"x\",\"kind\":"),
            DRAFT.replace("\"type\":\"text\"", "\"type\":\"script\""),
            DRAFT.replace("\"listed\":false", "\"listed\":123")
        }) {
            mvc.perform(post(URL).contentType("application/json").content(invalid))
                    .andExpect(status().isUnprocessableEntity());
        }
        SecurityContext.clear();
        mvc.perform(post(URL).contentType("application/json").content(DRAFT)).andExpect(status().isUnauthorized());
    }

    @Test
    void incompleteNumericValuesRoundTripButCannotPrepare() throws Exception {
        signIn();
        for (String number : new String[] {"1.5", "-1", "10000"}) {
            String body = DRAFT.replace("\"maxLength\":null", "\"maxLength\":" + number);
            String recovered = mvc.perform(
                            post(URL).contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            mvc.perform(post(URL).contentType("application/json").content(recovered))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/form-studio/new-forms/prepare")
                            .contentType("application/json")
                            .content(recovered))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void ordinaryFieldDraftRoundTripDoesNotInventUnsupportedNullOptions() throws Exception {
        signIn();
        String body = DRAFT.replace("\"type\":\"text\"", "\"type\":\"bool\"").replace(",\"maxLength\":null", "");
        String recovered = mvc.perform(post(URL).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mvc.perform(post(URL).contentType("application/json").content(recovered))
                .andExpect(status().isOk());
    }

    private static void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "author", null, null, false, Set.of("md.settings.update"), 1L, false, 1L, null));
    }
}
