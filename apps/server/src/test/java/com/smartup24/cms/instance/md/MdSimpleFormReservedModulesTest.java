package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A package never names a platform module; the live namespace is accepted for activation only (ADR-0037, ADR-0038). */
class MdSimpleFormReservedModulesTest {
    private final JsonMapper json = JsonMapper.shared();

    @ParameterizedTest
    @ValueSource(strings = {"md", "ms", "kauth", "audit", "report", "search", "example", "webhook", "iam", "tasks"})
    void refusesAPlatformModuleBeforePreparingAPackage(String module) throws Exception {
        assertThatThrownBy(() -> new MdSimpleFormDeclarations(json).prepare(declaration(module)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .anySatisfy(field -> assertThat(field.field()).isEqualTo("module.code")));
    }

    @Test
    void acceptsTheLiveNamespaceAndAnOrdinaryModule() throws Exception {
        assertThat(new MdSimpleFormDeclarations(json)
                        .prepare(declaration("studio"))
                        .declaration()
                        .module()
                        .code())
                .isEqualTo("studio");
        assertThat(new MdSimpleFormDeclarations(json)
                        .prepare(declaration("inventory"))
                        .declaration()
                        .module()
                        .code())
                .isEqualTo("inventory");
    }

    private byte[] declaration(String module) throws Exception {
        ObjectNode declaration =
                (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
        ((ObjectNode) declaration.path("module")).put("code", module);
        return json.writeValueAsBytes(declaration);
    }
}
