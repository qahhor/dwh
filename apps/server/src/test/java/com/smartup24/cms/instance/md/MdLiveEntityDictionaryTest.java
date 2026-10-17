package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.i18n.I18nModels.LanguageRecord;
import com.smartup24.cms.instance.md.repository.MdI18nRepository;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdI18nService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MdLiveEntityDictionaryTest {
    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void newLabelsAndRevocationAreVisibleWithoutInvalidatingTheBaseCache() throws Exception {
        var declarations = new AtomicReference<Map<String, EntityDefinitionSource.Declaration>>(Map.of());
        var live = new LiveEntities(() -> List.of(declarations::get));
        var service = service(live);
        principal(Set.of("studio.alpha.view"));
        assertThat(service.effectiveDictionary("en"))
                .containsEntry("common.save", "Save")
                .doesNotContainKey("studio.alpha.name");
        declarations.set(Map.of(
                "studio.alpha",
                new EntityDefinitionSource.Declaration(
                        MdLiveEntityCatalogTest.entity(),
                        Map.of(
                                "ru",
                                Map.of("studio.alpha.name", "Alpha RU"),
                                "en",
                                Map.of("studio.alpha.name", "Alpha EN")))));
        assertThat(service.effectiveDictionary("en")).containsEntry("studio.alpha.name", "Alpha EN");
        assertThat(service.effectiveDictionary("ru")).containsEntry("studio.alpha.name", "Alpha RU");
        principal(Set.of());
        assertThat(service.effectiveDictionary("en")).doesNotContainKey("studio.alpha.name");
        principal(Set.of("md.roles.view"));
        assertThat(service.effectiveDictionary("en")).containsEntry("studio.alpha.name", "Alpha EN");
    }

    @Test
    void liveLabelsCannotOverwriteBundledOrOverriddenKeys() throws Exception {
        var live = new LiveEntities(() -> List.of(() -> Map.of(
                "studio.alpha",
                new EntityDefinitionSource.Declaration(
                        MdLiveEntityCatalogTest.entity(), Map.of("ru", Map.of("common.save", "Injected"))))));
        principal(Set.of("studio.alpha.view"));
        // The clashing label is left out and logged; the dictionary itself keeps serving (ADR-0038).
        Logger logger = (Logger) LoggerFactory.getLogger(MdI18nService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var service = service(live);
            for (int request = 0; request < 3; request++) {
                assertThat(service.effectiveDictionary("ru")).containsEntry("common.save", "Save RU");
            }
        } finally {
            logger.detachAppender(appender);
        }
        // Said once at WARN, not at ERROR on every dictionary request.
        assertThat(appender.list)
                .filteredOn(event -> event.getFormattedMessage().contains("live_label_shadows_base_key"))
                .singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    private static MdI18nService service(LiveEntities live) throws Exception {
        var repository = mock(MdI18nRepository.class);
        var catalog = mock(MdI18nCatalog.class);
        when(repository.findLanguage("ru")).thenReturn(Optional.of(language("ru")));
        when(repository.findLanguage("en")).thenReturn(Optional.of(language("en")));
        when(catalog.bundled("ru")).thenReturn(Map.of("common.save", "Save RU"));
        when(catalog.bundled("en")).thenReturn(Map.of("common.save", "Save"));
        return MdI18nService.class
                .getConstructor(MdI18nRepository.class, MdI18nCatalog.class, AuditLogService.class, LiveEntities.class)
                .newInstance(repository, catalog, mock(AuditLogService.class), live);
    }

    private static LanguageRecord language(String code) {
        return new LanguageRecord(code, code, true, true, 1, 1L, 1L, Instant.EPOCH, Instant.EPOCH);
    }

    private static void principal(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "dictionary", "dictionary@test.local", 1L, false, permissions, 1L, false, 0, null));
    }
}
