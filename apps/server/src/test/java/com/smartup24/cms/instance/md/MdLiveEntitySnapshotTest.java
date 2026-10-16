package com.smartup24.cms.instance.md;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.EntityRecordStore;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class MdLiveEntitySnapshotTest {
    @Test
    void startupCatalogForOneHundredLiveFormsLoadsTwoSnapshotsInsteadOfOnePerRight() {
        Map<String, EntityDefinitionSource.Declaration> declarations = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            EntityDefinition entity = entity("f" + i);
            declarations.put(entity.code(), new EntityDefinitionSource.Declaration(entity, Map.of()));
        }
        AtomicInteger loads = new AtomicInteger();
        var live = new LiveEntities(() -> List.of(() -> {
            loads.incrementAndGet();
            return declarations;
        }));
        var beans = new StaticListableBeanFactory(Map.of("live", live));
        var registry = new EntityRegistry(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                beans.getBeanProvider(EntityRecordStore.class),
                beans.getBeanProvider(LiveEntities.class));
        var catalog = mock(MdI18nCatalog.class);
        when(catalog.bundled("ru")).thenReturn(Map.of());
        var service = new MdPermissionService(mock(MdPermissionRepository.class), registry, catalog, live);

        assertThat(service.syncFormCatalog(Set.of()).declared()).isEqualTo(400);

        assertThat(loads.get()).isEqualTo(2);
    }

    private static EntityDefinition entity(String name) {
        String code = "studio." + name;
        return Entity.define(code, code)
                .table("studio_records", "s")
                .scope(EntityScope.all())
                .rights(
                        "studio",
                        code + ".rights.form",
                        Map.of(
                                "view",
                                code + ".rights.view",
                                "create",
                                code + ".rights.create",
                                "update",
                                code + ".rights.update",
                                "delete",
                                code + ".rights.delete"))
                .field(text("name", code + ".name").column("name").required())
                .section("main", "entity.section.main", "name")
                .actions("create", "update", "delete")
                .defaultSort("name", Entity.Sort.ASC)
                .build();
    }
}
