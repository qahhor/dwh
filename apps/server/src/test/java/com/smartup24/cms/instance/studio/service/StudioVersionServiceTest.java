package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.Availability;
import com.smartup24.cms.instance.studio.automation.AutomationCompatibility;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.Head;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The refusals of a new version and of the lifecycle that need no database (ADR-0041). */
class StudioVersionServiceTest {
    private static final String CODE = "studio.unit_form";

    private final JsonMapper json = JsonMapper.shared();
    private final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);
    private final StudioFormRepository forms = mock(StudioFormRepository.class);
    private final StudioFormVersionRepository versions = mock(StudioFormVersionRepository.class);
    private final StudioDefinitions definitions = mock(StudioDefinitions.class);
    private final StudioActivationService activation = mock(StudioActivationService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private DeliveryPackage pack;
    private StudioVersionService service;

    @BeforeEach
    void setUp() throws Exception {
        signIn(Set.of("studio.forms.publish"));
        pack = pack();
        when(activation.availability()).thenReturn(new Availability(true, "studio", 100));
        when(versions.declaration(any()))
                .thenAnswer(call -> json.readValue(
                        (String) call.getArgument(0),
                        com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration.class));
        service = new StudioVersionService(
                new StudioPackages(json, declarations),
                forms,
                versions,
                new StudioEntityCompiler(),
                definitions,
                new StudioFormViews(versions, definitions),
                activation,
                mock(MdPermissionService.class),
                audit,
                mock(StudioTargets.class),
                mock(AutomationCompatibility.class));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void aRestoreBeyondTheLimitOfActiveFormsIsRefused() {
        head(1, "archived", 1, json.writeValueAsString(pack.declaration()));
        when(forms.count()).thenReturn((long) StudioActivationService.MAX_FORMS);
        refused(() -> service.restore(CODE, "\"1\""), "error.studio.quota");
        verify(versions, never()).saveState(any(), anyInt(), anyBoolean(), anyLong());
    }

    @Test
    void aNewVersionBeyondTheLastIsRefusedAndSoIsADisabledActivation() {
        head(1, "active", StudioFormVersionRepository.MAX_VERSIONS, json.writeValueAsString(pack.declaration()));
        refused(() -> service.newVersion(CODE, "\"1\"", body(changed())), "error.studio.version_limit");
        when(activation.availability()).thenReturn(new Availability(false, "studio", 100));
        refused(() -> service.newVersion(CODE, "\"1\"", body(changed())), "error.studio.disabled");
    }

    @Test
    void anActiveDeclarationThatNoLongerReadsAndLabelsThatClashAreRefused() {
        head(1, "active", 1, "{\"formatVersion\":1}");
        doThrow(new IllegalStateException("unreadable")).when(versions).declaration("{\"formatVersion\":1}");
        refused(() -> service.newVersion(CODE, "\"1\"", body(changed())), "error.studio.broken");

        head(1, "active", 1, json.writeValueAsString(pack.declaration()));
        when(definitions.refusal(eq(CODE), any())).thenReturn("translation_key");
        refused(() -> service.newVersion(CODE, "\"1\"", body(changed())), "error.studio.translation_key");
        when(definitions.refusal(eq(CODE), any())).thenReturn("declared_code");
        refused(() -> service.newVersion(CODE, "\"1\"", body(changed())), "error.studio.exists");
        verify(versions, never()).insertVersion(anyLong(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void aPublisherRightIsNeededForEveryChange() {
        signIn(Set.of("md.settings.update"));
        assertThatThrownBy(() -> service.archive(CODE, "\"1\"")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.restore(CODE, "\"1\"")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.newVersion(CODE, "\"1\"", new byte[0])).isInstanceOf(ApiException.class);
        verify(forms, never()).lockCatalog();
    }

    private void head(long revision, String status, int count, String declaration) {
        when(versions.head(CODE))
                .thenReturn(Optional.of(new Head(
                        7,
                        CODE,
                        Instant.EPOCH,
                        1,
                        status,
                        revision,
                        Instant.EPOCH,
                        declaration,
                        pack.checksum(),
                        count,
                        0,
                        false)));
    }

    private static void refused(Runnable action, String key) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> org.assertj.core.api.Assertions.assertThat(refused.getMessageKey())
                                .isEqualTo(key));
    }

    private DeliveryPackage changed() {
        ObjectNode declaration = (ObjectNode) json.valueToTree(pack.declaration());
        ((ObjectNode) declaration.path("entity").path("title")).put("ru", "Другое название");
        return declarations.prepare(json.writeValueAsBytes(declaration));
    }

    private byte[] body(DeliveryPackage value) {
        return json.writeValueAsBytes(value);
    }

    private DeliveryPackage pack() throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/studio/simple-form.json"))) {
            ObjectNode declaration = (ObjectNode) json.readTree(input.readAllBytes());
            ((ObjectNode) declaration.path("module")).put("code", "studio");
            ((ObjectNode) declaration.path("entity")).put("name", "unit_form");
            return declarations.prepare(json.writeValueAsBytes(declaration));
        }
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "studio-unit", null, null, false, permissions, 1L, false, 1L, null));
    }
}
