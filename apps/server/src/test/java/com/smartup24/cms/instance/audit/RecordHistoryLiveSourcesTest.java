package com.smartup24.cms.instance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.audit.service.RecordHistoryService;
import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.EntityRecordStore;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The history of a live form follows the live catalog, never a copy taken at the start: a form that is quarantined,
 * stops loading or whose module is switched off answers 404 and is not offered (ADR-0038).
 */
class RecordHistoryLiveSourcesTest {

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void aLiveFormServedAtTheStartLosesItsHistoryWhenItStopsBeingServed() {
        var served = new AtomicReference<Map<String, EntityDefinitionSource.Declaration>>(
                Map.of("studio.visits", form("visits")));
        EntityRegistry registry = registry(served);
        var history =
                new RecordHistoryService(mock(AuditLogService.class), List.of(), registry, EntityGate.of(registry));
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "viewer", null, null, false, Set.of("studio.visits.view"), 1L, false, 1L, null));
        assertThat(history.availableKinds()).containsExactly("studio.visits");

        served.set(Map.of());
        assertThat(history.availableKinds()).isEmpty();
        assertThatThrownBy(() -> history.history("studio.visits", "1", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getMessageKey())
                        .isEqualTo(EntityGate.unknownEntity().getMessageKey()));
    }

    static EntityDefinitionSource.Declaration form(String name) {
        Labels title = new Labels("Визиты", "Tashriflar", "Visits");
        return new StudioEntityCompiler()
                .compile(
                        7,
                        new Declaration(
                                1, "simple-form", new Module("studio", title), new Entity(name, title), List.of()));
    }

    static EntityRegistry registry(AtomicReference<Map<String, EntityDefinitionSource.Declaration>> served) {
        var live = new LiveEntities(() -> List.of(served::get));
        var beans = new StaticListableBeanFactory(Map.of("live", live));
        return new EntityRegistry(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                beans.getBeanProvider(EntityRecordStore.class),
                beans.getBeanProvider(LiveEntities.class));
    }
}
