package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** User declarations cannot reach runtime-only secret-key refusals as an internal error (ADR-0038). */
class MdSimpleFormIdentifiersSafetyTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "oldpassword",
                "passwd",
                "secret",
                "apikey",
                "myprivatekey",
                "accesstoken",
                "refreshtoken"
            })
    void refusesEveryRuntimeSecretFragmentBeforePreparingAPackage(String id) throws Exception {
        var json = JsonMapper.shared();
        ObjectNode declaration =
                (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
        ((ObjectNode) declaration.path("fields").get(0)).put("id", id);
        assertThatThrownBy(() -> new MdSimpleFormDeclarations(json).prepare(json.writeValueAsBytes(declaration)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getFieldErrors())
                                .anySatisfy(field -> org.assertj.core.api.Assertions.assertThat(field.field())
                                        .isEqualTo("fields[0].id")));
    }
}
