package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.repository.MdSettingRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdSettingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MdSettingServiceTest {

    private final MdSettingRepository repository = Mockito.mock(MdSettingRepository.class);
    private final MdUserRepository userRepository = Mockito.mock(MdUserRepository.class);
    private final MdI18nService i18nService = Mockito.mock(MdI18nService.class);
    private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);
    private final MdSettingService service =
            new MdSettingService(repository, userRepository, i18nService, auditLogService);

    @Test
    @DisplayName("Системные настройки должны возвращать дефолтные значения при пустой базе")
    void shouldReturnDefaultInstanceSettings() {
        when(repository.getAllInstanceSettings()).thenReturn(Map.of());

        var settings = service.getInstanceSettings();

        assertThat(settings.get("system.company_name")).isEqualTo("SmartupCMS");
        assertThat(settings.get("system.default_language")).isEqualTo("ru");
        assertThat(settings.get("security.idle_lock_minutes")).isEqualTo("30");
        assertThat(settings.get("storage.default_user_quota_mb")).isEqualTo("1024");
    }

    @Test
    @DisplayName("Эффективные настройки должны правильно применять иерархию: Defaults -> Instance -> User")
    void shouldMergeSettingsHierarchically() {
        when(repository.getAllInstanceSettings())
                .thenReturn(Map.of(
                        "system.company_name", "Acme Corporation",
                        "ui.theme", "light"));
        when(repository.getAllUserSettings(10L))
                .thenReturn(Map.of(
                        "ui.theme", "dark",
                        "user.language", "uz"));

        var effective = service.getEffectiveSettings(10L);

        // Inherited from Defaults
        assertThat(effective.get("security.idle_lock_minutes")).isEqualTo("30");
        // Overridden by Instance
        assertThat(effective.get("system.company_name")).isEqualTo("Acme Corporation");
        // Overridden by User
        assertThat(effective.get("ui.theme")).isEqualTo("dark");
        assertThat(effective.get("user.language")).isEqualTo("uz");
    }

    @Test
    @DisplayName("Обновление системных настроек должно фиксироваться в журнале аудита")
    void shouldAuditSystemSettingsChange() {
        when(repository.getAllInstanceSettings()).thenReturn(Map.of("system.company_name", "Old Company Name"));
        when(repository.nextInstanceRevision(4L)).thenReturn(5L);

        assertThat(service.updateInstanceSettings(Map.of("system.company_name", "New Company Name"), 4L))
                .isEqualTo(5L);

        verify(repository, times(1)).setInstanceSetting("system.company_name", "New Company Name");
        verify(auditLogService, times(1))
                .logChange(
                        eq("md_settings"),
                        eq("system.company_name"),
                        eq("U"),
                        eq(List.of("value")),
                        eq(Map.of("key", "system.company_name", "value", "Old Company Name")),
                        eq(Map.of("key", "system.company_name", "value", "New Company Name")));
    }

    @Test
    @DisplayName("3.6: system settings carry the revision of the set; a stale save writes nothing")
    void systemSettingsAreSavedFromTheirRevision() {
        when(repository.getAllInstanceSettings()).thenReturn(Map.of());
        when(repository.instanceRevision()).thenReturn(3L);
        when(repository.nextInstanceRevision(2L)).thenThrow(Revisions.conflict());

        assertThat(service.getSystemSettings().revision()).isEqualTo(3L);
        assertThat(service.getSystemSettings().values()).containsEntry("system.default_language", "ru");
        // An empty save changes nothing and keeps the revision, but is refused from an older one.
        assertThat(service.updateInstanceSettings(Map.of(), 3L)).isEqualTo(3L);
        assertThatThrownBy(() -> service.updateInstanceSettings(Map.of(), 2L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.updateInstanceSettings(Map.of("system.company_name", "Late"), 2L))
                .isInstanceOf(ApiException.class);

        verify(repository, never()).nextInstanceRevision(3L);
        verify(repository, never()).setInstanceSetting(anyString(), anyString());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("Язык пользователя должен сохраняться и в настройках, и в каноническом профиле")
    void shouldSynchronizeLanguageWithCanonicalUserProfile() {
        when(i18nService.requireActiveLanguageCode("DE")).thenReturn("de");

        service.updateUserSettings(
                10L,
                Map.of(
                        "user.language", "DE",
                        "ui.theme", "light"));

        verify(repository).setUserSetting(10L, "user.language", "de");
        verify(repository).setUserSetting(10L, "ui.theme", "light");
        verify(userRepository).updateLanguage(10L, "de", 10L);
    }

    @Test
    @DisplayName("Свои настройки — только user.* и ui.*: безопасность экземпляра пользователь не переопределит")
    void personalSettingsCannotShadowInstanceOnes() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateUserSettings(10L, Map.of("security.idle_lock_minutes", "0")))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(repository, userRepository);

        when(repository.getAllInstanceSettings()).thenReturn(Map.of());
        when(repository.getAllUserSettings(10L))
                .thenReturn(Map.of("security.idle_lock_minutes", "0", "ui.theme", "light"));
        var effective = service.getEffectiveSettings(10L);
        org.assertj.core.api.Assertions.assertThat(effective)
                .containsEntry("security.idle_lock_minutes", "30")
                .containsEntry("ui.theme", "light");
        org.assertj.core.api.Assertions.assertThat(service.getUserSettings(10L)).containsOnlyKeys("ui.theme");
    }

    @Test
    @DisplayName("Блокировка по бездействию: от 0 до 1440 минут, иначе 422; испорченное значение — по умолчанию")
    void idleLockMinutesAreBounded() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateInstanceSettings(Map.of("security.idle_lock_minutes", "2000"), 1L))
                .isInstanceOf(ApiException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateInstanceSettings(Map.of("security.idle_lock_minutes", "soon"), 1L))
                .isInstanceOf(ApiException.class);

        when(repository.getAllInstanceSettings()).thenReturn(Map.of("security.idle_lock_minutes", "15"));
        org.assertj.core.api.Assertions.assertThat(service.idleLockMinutes()).isEqualTo(15);
        when(repository.getAllInstanceSettings()).thenReturn(Map.of("security.idle_lock_minutes", "broken"));
        org.assertj.core.api.Assertions.assertThat(service.idleLockMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("Некорректный язык должен отклоняться до записи любых настроек")
    void shouldRejectInvalidLanguageBeforeWritingSettings() {
        var error = new IllegalArgumentException("inactive language");
        when(i18nService.requireActiveLanguageCode("xx")).thenThrow(error);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateUserSettings(
                        10L,
                        Map.of(
                                "ui.theme", "light",
                                "user.language", "xx")))
                .isSameAs(error);

        verifyNoInteractions(repository, userRepository);
    }
}
