package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.FormLayoutDeclarations;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.entity.FormStudioService;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.example.service.ExampleProductsEntity;
import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVersionPackage;
import com.smartup24.cms.instance.md.service.MdFormStudioPackages;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class MdFormStudioPackagesTest {
    private final JsonMapper json = JsonMapper.shared();
    private final FormLayoutDeclarations declarations = new FormLayoutDeclarations(json);
    private final MdFormStudioPackages packages = new MdFormStudioPackages(json, declarations);
    private FormMeta meta;
    private FormStudioVersionPackage prepared;
    private String source;

    @BeforeEach
    void setup() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                7L, "studio", "studio@example.test", null, false, Set.of("*.*"), 1, false, 1, null));
        meta = new FormStudioService(
                        EntityGate.of(new EntityRegistry(
                                List.of(ExampleProductsEntity.DEFINITION, ExampleRequestsEntity.DEFINITION))),
                        mock(EntityEnums.class),
                        declarations)
                .metadata("example.products");
        prepared = packages.prepare(1, 1, declarations.initial(meta), meta);
        source = json.writeValueAsString(prepared);
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void roundTripAndCanonicalObjectOrderingPreserveThePackage() {
        assertThat(packages.validate(bytes(source), meta)).isEqualTo(prepared);
        assertThat(packages.digest(json.readTree("{\"a\":1,\"b\":2}")))
                .isEqualTo(packages.digest(json.readTree("{\"b\":2,\"a\":1}")));
        assertThat(packages.digest(json.readTree("[1,2]"))).isNotEqualTo(packages.digest(json.readTree("[2,1]")));
        assertThat(packages.validate(
                        bytes(" ".repeat(MdFormStudioPackages.MAX_BYTES - bytes(source).length) + source), meta))
                .isEqualTo(prepared);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "true",
                "\"1\"",
                "1.0",
                "1e0",
                "0",
                "-1",
                "9007199254740992",
                "999999999999999999999999999"
            })
    void integerTokensAreStrictAndBounded(String value) {
        String changed = source.replace("\"version\":1", "\"version\":" + value);
        assertThatThrownBy(() -> packages.validate(bytes(changed), meta)).isInstanceOf(ApiException.class);
    }

    @Test
    void allEnvelopeLayersAreClosedAndRequireEveryProperty() {
        for (String body : List.of(
                "null",
                "[]",
                "{}",
                source + "{}",
                source.replaceFirst("\\{", "{\"unknown\":true,"),
                source.replace("\"package\":{", "\"package\":{\"execute\":true,"),
                source.replace("\"dependencies\":{", "\"dependencies\":{\"$ref\":\"https://invalid.test\","),
                source.replace("\"contractVersion\":1,", ""),
                source.replace("\"formatVersion\":1,", ""),
                source.replaceFirst("\\{", "{\"checksum\":\"duplicate\","),
                source.replace("\"kind\":\"form-layout-package\"", "\"kind\":\"javascript\""),
                source.replace("\"fieldId\":\"code\"", "\"fieldId\":\"hidden\""))) {
            assertThatThrownBy(() -> packages.validate(bytes(body), meta))
                    .as(body.substring(0, Math.min(60, body.length())))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void utf8DepthAndByteLimitsApplyBeforeTreeAllocation() {
        assertThatThrownBy(() -> packages.validate(new byte[] {(byte) 0xc3, (byte) 0x28}, meta))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> packages.validate(bytes("[".repeat(11) + "0" + "]".repeat(11)), meta))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> packages.validate(bytes(" ".repeat(65537)), meta))
                .isInstanceOf(ApiException.class);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
