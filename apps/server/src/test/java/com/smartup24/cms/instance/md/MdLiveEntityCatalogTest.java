package com.smartup24.cms.instance.md;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MdLiveEntityCatalogTest {
    static final Map<String, String> RU = Map.of(
            "studio.alpha.rights.form",
            "Alpha",
            "studio.alpha.rights.view",
            "View",
            "studio.alpha.rights.create",
            "Create",
            "studio.alpha.rights.update",
            "Update",
            "studio.alpha.rights.delete",
            "Delete");

    @Test
    void startupSynchronizationIncludesLiveDefinitionsUnderTheCatalogLock() {
        var repository = mock(MdPermissionRepository.class);
        var registry = mock(EntityRegistry.class);
        var catalog = mock(MdI18nCatalog.class);
        when(catalog.bundled("ru")).thenReturn(RU);
        when(registry.all()).thenReturn(List.of(entity()));
        when(registry.rights("studio.alpha")).thenReturn(java.util.Optional.of(entity().rights()));
        var service = new MdPermissionService(repository, registry, catalog);
        var result = service.syncFormCatalog(Set.of("md.profile.view"));
        verify(repository)
                .deprecateMissing(Set.of(
                        "md.profile.view",
                        "studio.alpha.view",
                        "studio.alpha.create",
                        "studio.alpha.update",
                        "studio.alpha.delete"));
        assertThat(result.declared()).isEqualTo(5);
        assertThat(mockingDetails(repository)
                        .getInvocations()
                        .iterator()
                        .next()
                        .getMethod()
                        .getName())
                .isEqualTo("lockCatalog");
    }

    @Test
    void activationRegistersItsOwnNamesAndAdministrativeGrants() throws Exception {
        var repository = mock(MdPermissionRepository.class);
        var service = new MdPermissionService(repository);
        MdPermissionService.class
                .getMethod("registerEntityDefinition", EntityDefinition.class, Map.class)
                .invoke(service, entity(), RU);
        verify(repository).registerForm("studio.alpha", "studio", "Alpha");
        verify(repository).registerFormAction("studio.alpha", "view", "View");
        verify(repository).registerFormAction("studio.alpha", "create", "Create");
        assertThat(mockingDetails(repository).getInvocations())
                .extracting(call -> call.getMethod().getName())
                .contains("lockCatalog", "grantEntityToAdministrativeRoles");
    }

    static EntityDefinition entity() {
        return Entity.define("studio.alpha", "studio.alpha")
                .table("studio_records", "s")
                .scope(EntityScope.all())
                .rights(
                        "studio",
                        "studio.alpha.rights.form",
                        Map.of(
                                "view",
                                "studio.alpha.rights.view",
                                "create",
                                "studio.alpha.rights.create",
                                "update",
                                "studio.alpha.rights.update",
                                "delete",
                                "studio.alpha.rights.delete"))
                .field(text("name", "studio.alpha.name").column("name").required())
                .section("main", "entity.section.main", "name")
                .actions("create", "update", "delete")
                .defaultSort("name", Entity.Sort.ASC)
                .build();
    }
}
