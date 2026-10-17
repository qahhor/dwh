package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository.Version;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.Head;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.VersionRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How the publisher sees a form that fails to load, an unknown one and a page of forms (ADR-0041). */
class StudioFormViewsTest {
    private final StudioFormVersionRepository versions = mock(StudioFormVersionRepository.class);
    private final StudioDefinitions definitions = mock(StudioDefinitions.class);
    private final StudioFormViews views = new StudioFormViews(versions, definitions);

    @BeforeEach
    void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "views", null, null, false, Set.of("studio.forms.publish"), 1L, false, 1L, null));
        when(definitions.current())
                .thenReturn(new StudioDefinitions.Catalog(
                        new Version(1, 1, 0, 0),
                        Map.of(),
                        Map.of(),
                        List.of(new StudioDefinitions.Broken("studio.broken", "declaration")),
                        0));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void aFormThatNoLongerReadsIsListedByItsCodeAndReportedBroken() {
        Head broken = head("studio.broken", "{}");
        Head unreadable = head("studio.unread", "{}");
        when(versions.declaration(any())).thenThrow(new IllegalStateException("unreadable"));
        when(versions.page(any())).thenReturn(List.of(broken, unreadable));
        var page = views.list(null, null);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).broken()).isEqualTo("declaration");
        assertThat(page.items().get(0).title().ru()).isEqualTo("studio.broken");
        assertThat(page.items().get(1).broken()).isEqualTo("declaration");
        assertThat(page.hasMore()).isFalse();
        when(versions.head("studio.broken")).thenReturn(Optional.of(broken));
        assertThatThrownBy(() -> views.get("studio.broken"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.broken"));
    }

    @Test
    void anUnknownFormOrAValueThatIsNoCodeIsNotFoundAndTheRightIsNeeded() {
        when(versions.head("studio.missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> views.get("studio.missing"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.form_not_found"));
        assertThatThrownBy(() -> views.get("md.users"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.common.entity_not_found"));
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "views", null, null, false, Set.of("md.settings.update"), 1L, false, 1L, null));
        assertThatThrownBy(() -> views.list(null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> views.get("studio.missing")).isInstanceOf(ApiException.class);
    }

    @Test
    void aReadableFormGivesItsTitleAndVersions() {
        Head head = head("studio.visits", "{\"x\":1}");
        when(versions.head("studio.visits")).thenReturn(Optional.of(head));
        when(versions.declaration("{\"x\":1}"))
                .thenReturn(new Declaration(
                        1,
                        "simple-form",
                        new Module("studio", new Labels("М", "M", "M")),
                        new Entity("visits", new Labels("Визиты", "Tashriflar", "Visits")),
                        List.of()));
        when(versions.versions(7)).thenReturn(List.of(new VersionRow(1, "a".repeat(64), Instant.EPOCH, 1)));
        var detail = views.get("studio.visits");
        assertThat(detail.form().title().en()).isEqualTo("Visits");
        assertThat(detail.form().broken()).isNull();
        assertThat(detail.versions()).hasSize(1);
    }

    private static Head head(String code, String declaration) {
        return new Head(
                7, code, Instant.EPOCH, 1, "active", 1, Instant.EPOCH, declaration, "a".repeat(64), 1, 0, false);
    }
}
