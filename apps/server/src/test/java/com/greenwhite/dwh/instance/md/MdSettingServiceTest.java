package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdI18nService;
import com.greenwhite.dwh.instance.md.repository.MdSettingRepository;
import com.greenwhite.dwh.instance.md.service.MdSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MdSettingServiceTest {

    private final MdSettingRepository repository = Mockito.mock(MdSettingRepository.class);
    private final MdUserRepository userRepository = Mockito.mock(MdUserRepository.class);
    private final MdI18nService i18nService = Mockito.mock(MdI18nService.class);
    private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);
    private final MdSettingService service = new MdSettingService(
            repository, userRepository, i18nService, auditLogService);

    @Test
    @DisplayName("Системные настройки должны возвращать дефолтные значения при пустой базе")
    void shouldReturnDefaultInstanceSettings() {
        when(repository.getAllInstanceSettings()).thenReturn(Map.of());

        var settings = service.getInstanceSettings();

        assertThat(settings.get("system.company_name")).isEqualTo("Smartup DWH Platform");
        assertThat(settings.get("system.default_language")).isEqualTo("ru");
        assertThat(settings.get("security.min_password_length")).isEqualTo("10");
        assertThat(settings.get("storage.default_user_quota_mb")).isEqualTo("1024");
    }

    @Test
    @DisplayName("Эффективные настройки должны правильно применять иерархию: Defaults -> Instance -> User")
    void shouldMergeSettingsHierarchically() {
        when(repository.getAllInstanceSettings()).thenReturn(Map.of(
                "system.company_name", "Acme Corporation",
                "ui.theme", "light"
        ));
        when(repository.getAllUserSettings(10L)).thenReturn(Map.of(
                "ui.theme", "dark",
                "user.language", "uz"
        ));

        var effective = service.getEffectiveSettings(10L);

        // Inherited from Defaults
        assertThat(effective.get("security.min_password_length")).isEqualTo("10");
        // Overridden by Instance
        assertThat(effective.get("system.company_name")).isEqualTo("Acme Corporation");
        // Overridden by User
        assertThat(effective.get("ui.theme")).isEqualTo("dark");
        assertThat(effective.get("user.language")).isEqualTo("uz");
    }

    @Test
    @DisplayName("Обновление системных настроек должно фиксироваться в журнале аудита")
    void shouldAuditSystemSettingsChange() {
        when(repository.getAllInstanceSettings()).thenReturn(Map.of(
                "system.company_name", "Old Company Name"
        ));

        service.updateInstanceSettings(Map.of("system.company_name", "New Company Name"));

        verify(repository, times(1)).setInstanceSetting("system.company_name", "New Company Name");
        verify(auditLogService, times(1)).logChange(
                eq("md_settings"), eq("system.company_name"), eq("U"),
                eq(java.util.List.of("value")),
                eq(Map.of("key", "system.company_name", "value", "Old Company Name")),
                eq(Map.of("key", "system.company_name", "value", "New Company Name"))
        );
    }

    @Test
    @DisplayName("Язык пользователя должен сохраняться и в настройках, и в каноническом профиле")
    void shouldSynchronizeLanguageWithCanonicalUserProfile() {
        when(i18nService.requireActiveLanguageCode("DE")).thenReturn("de");

        service.updateUserSettings(10L, Map.of(
                "user.language", "DE",
                "ui.theme", "light"
        ));

        verify(repository).setUserSetting(10L, "user.language", "de");
        verify(repository).setUserSetting(10L, "ui.theme", "light");
        verify(userRepository).updateLanguage(10L, "de", 10L);
    }

    @Test
    @DisplayName("Некорректный язык должен отклоняться до записи любых настроек")
    void shouldRejectInvalidLanguageBeforeWritingSettings() {
        var error = new IllegalArgumentException("inactive language");
        when(i18nService.requireActiveLanguageCode("xx")).thenThrow(error);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.updateUserSettings(10L, Map.of(
                        "ui.theme", "light",
                        "user.language", "xx"
                )))
                .isSameAs(error);

        verifyNoInteractions(repository, userRepository);
    }
}
