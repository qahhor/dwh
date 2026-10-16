package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.example.service.ExampleProductsEntity;
import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import com.smartup24.cms.instance.support.TestModules;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class FormStudioControllerTest {
    private static final String PROTOTYPE = "/api/v1/form-studio/prototype";
    private static final String PREVIEW = "/api/v1/form-studio/preview";

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    private MockMvc mvc(EntityDefinition entity, InstalledModules modules) {
        var gate = new EntityGate(
                new EntityRegistry(
                        entity == ExampleProductsEntity.DEFINITION
                                ? List.of(entity, ExampleRequestsEntity.DEFINITION)
                                : List.of(entity)),
                modules,
                TestModules.catalog());
        var studio =
                new FormStudioService(gate, mock(EntityEnums.class), new FormLayoutDeclarations(JsonMapper.shared()));
        return MockMvcBuilders.standaloneSetup(new FormStudioController(studio))
                .addInterceptors(TestModules.permissions())
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();
    }

    @Test
    void anonymousAndViewOnlyUsersCannotAuthorLayouts() throws Exception {
        MockMvc mvc = mvc(ExampleProductsEntity.DEFINITION, code -> true);
        mvc.perform(get(PROTOTYPE)).andExpect(status().isUnauthorized());
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        FormLayoutDeclarationsTest.signIn(Set.of("md.settings.view", "example.products.view"));
        mvc.perform(get(PROTOTYPE)).andExpect(status().isForbidden());
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void entityRightIsRequiredOnEveryRequestIncludingAfterRevocation() throws Exception {
        MockMvc mvc = mvc(ExampleProductsEntity.DEFINITION, code -> true);
        FormLayoutDeclarationsTest.signIn(Set.of("md.settings.update", "example.products.view"));
        mvc.perform(get(PROTOTYPE)).andExpect(status().isOk());
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content(FormLayoutDeclarationsTest.EDITED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaration.sections[0].id").value("pricing"))
                .andExpect(jsonPath("$.meta.fields[0].key").value("code"))
                .andExpect(jsonPath("$.meta.fields[0].readonlyOnUpdate").value(true));
        FormLayoutDeclarationsTest.signIn(Set.of("md.settings.update"));
        mvc.perform(get(PROTOTYPE)).andExpect(status().isNotFound());
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content(FormLayoutDeclarationsTest.INITIAL))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledExampleModuleCannotBePreviewed() throws Exception {
        MockMvc mvc = mvc(ExampleProductsEntity.DEFINITION, code -> !"example".equals(code));
        FormLayoutDeclarationsTest.signIn(Set.of("*.*"));
        mvc.perform(get(PROTOTYPE)).andExpect(status().isNotFound());
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content(FormLayoutDeclarationsTest.INITIAL))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationAnswersProblemDetailsAndDoesNotChangeNormalFormMetadata() throws Exception {
        MockMvc mvc = mvc(ExampleProductsEntity.DEFINITION, code -> true);
        FormLayoutDeclarationsTest.signIn(Set.of("*.*"));
        String before = JsonMapper.shared().writeValueAsString(FormMetaController.of(ExampleProductsEntity.DEFINITION));
        mvc.perform(post(PREVIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FormLayoutDeclarationsTest.INITIAL.replace(
                                "\"fieldId\":\"code\"", "\"fieldId\":\"costNote\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.messageKey").value("error.common.form_layout_invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("sections[0].fields[0].fieldId"));
        assertThat(JsonMapper.shared().writeValueAsString(FormMetaController.of(ExampleProductsEntity.DEFINITION)))
                .isEqualTo(before);
    }

    @Test
    void hiddenAndReadonlyFieldRulesComeFromFreshMetadata() throws Exception {
        EntityDefinition entity = Entity.define("example.products", "example.products")
                .table("ex_products", "p")
                .scope(EntityScope.all())
                .field(text("code", "code")
                        .column("code")
                        .required()
                        .readonlyOnUpdate()
                        .list(sortable()))
                .field(text("costNote", "costNote").column("cost_note").requires("example.products", "cost"))
                .section("main", "main", "code", "costNote")
                .defaultSort("code", Entity.Sort.ASC)
                .actions("create", "update")
                .build();
        MockMvc mvc = mvc(entity, code -> true);
        FormLayoutDeclarationsTest.signIn(Set.of("md.settings.update", "example.products.view"));
        mvc.perform(get(PROTOTYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.fields.length()").value(1))
                .andExpect(jsonPath("$.meta.fields[0].required").value(true))
                .andExpect(jsonPath("$.meta.fields[0].readonlyOnUpdate").value(true))
                .andExpect(jsonPath("$.declaration.sections[0].fields.length()").value(1));
        String declaration = FormLayoutDeclarationsTest.INITIAL.replace(
                ",{\"fieldId\":\"name\"},{\"fieldId\":\"unit\"},{\"fieldId\":\"price\"}", "");
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content(declaration))
                .andExpect(status().isOk());
        mvc.perform(post(PREVIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declaration.replace("\"fieldId\":\"code\"", "\"fieldId\":\"costNote\"")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rawBodyLimitIsMeasuredInBytesBeforeParsing() throws Exception {
        MockMvc mvc = mvc(ExampleProductsEntity.DEFINITION, code -> true);
        FormLayoutDeclarationsTest.signIn(Set.of("*.*"));
        byte[] body = "я".repeat(FormLayoutDeclarations.MAX_BODY_BYTES / 2 + 1).getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("payload_too_large"));
    }
}
