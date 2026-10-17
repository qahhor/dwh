package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository.Version;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.json.JsonMapper;

/** The studioForms health component, its meters and the publisher check of the quarantine (ADR-0038). */
class StudioFormsHealthTest {
    private final StudioFormRepository repository = mock(StudioFormRepository.class);

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void aSwitchedOffModuleIsUpAndReadsNothing() {
        var definitions =
                new StudioDefinitions(repository, new StudioEntityCompiler(), module -> false, Set::of, Set::of, null);
        var health = new StudioFormsHealth(
                definitions, new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        var answer = health.health();
        assertThat(answer.getStatus()).isEqualTo(Status.UP);
        assertThat(answer.getDetails()).containsEntry("enabled", false);
        verifyNoInteractions(repository);
    }

    @Test
    void anEmptyCatalogIsUpAndTheMetersReadTheLastCompiledCatalog() {
        Version empty = new Version(0, 0, 0, 0);
        when(repository.version()).thenReturn(empty);
        when(repository.catalog()).thenReturn(new StudioFormRepository.Catalog(empty, List.of()));
        var meters = new SimpleMeterRegistry();
        var definitions = new StudioDefinitions(
                repository,
                new StudioEntityCompiler(),
                module -> true,
                new StaticListableBeanFactory().getBeanProvider(EntityDefinition.class),
                new StaticListableBeanFactory(Map.of("catalog", new MdI18nCatalog(JsonMapper.shared())))
                        .getBeanProvider(MdI18nCatalog.class),
                new StaticListableBeanFactory().getBeanProvider(ClusterNotices.class));
        var health = new StudioFormsHealth(
                definitions,
                new StaticListableBeanFactory(Map.of("meters", meters)).getBeanProvider(MeterRegistry.class));
        assertThat(meters.get("smc.studio.forms.broken").gauge().value()).isZero();
        var answer = health.health();
        assertThat(answer.getStatus()).isEqualTo(Status.UP);
        assertThat(answer.getDetails()).containsEntry("stored", 0L).doesNotContainKey("broken");
        assertThat(definitions.lastBroken()).isZero();
        assertThat(definitions.lastQuarantined()).isZero();
        assertThat(meters.get("smc.studio.forms.quarantined").gauge().value()).isZero();
        definitions.changed();
        assertThat(definitions.lastBroken()).isZero();
    }

    @Test
    void theQuarantineNeedsThePublisherRight() {
        var service = new StudioQuarantineService(
                repository, new StudioDefinitions(repository, new StudioEntityCompiler()), mock(AuditLogService.class));
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "author", null, null, false, Set.of("md.settings.update"), 1L, false, 1L, null));
        assertThatThrownBy(service::status).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.get("studio.any")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.quarantine("studio.any", "Reason")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.release("studio.any")).isInstanceOf(ApiException.class);
        verifyNoInteractions(repository);
    }
}
