package com.smartup24.cms.instance.kauth;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.kauth.service.KauthChannelTexts;
import com.smartup24.cms.instance.kauth.service.KauthPasswordHasher;
import com.smartup24.cms.instance.kauth.service.KauthUserSessionInvalidator;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.repository.MdI18nRepository;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.repository.MdSettingRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdSettingService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision of 2026-09-27: a code or a link reaches the user in the user's language; when that language is not active,
 * in the system language from the settings; when neither is active, in Russian.
 */
class KauthChannelTextsTest {

    static JdbcClient jdbc;
    static MdSettingService settings;
    static KauthChannelTexts texts;

    @BeforeAll
    static void setUp() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("dwh_channel_texts"));
        var mapper = new ObjectMapper();
        var audit = new AuditLogService(new AuditLogRepository(jdbc, mapper), null, new AuditDataRedactor());
        var i18n = new MdI18nService(new MdI18nRepository(jdbc, mapper), new MdI18nCatalog(mapper), audit);
        var userRepository = new MdUserRepository(jdbc, mapper);
        settings = new MdSettingService(new MdSettingRepository(jdbc), userRepository, i18n, audit);
        var scopes = new MdScopeService(new MdScopeRepository(jdbc), new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)), audit);
        var users = new MdUserService(userRepository, new MdRoleRepository(jdbc),
                new MdCustomFieldService(new MdCustomFieldRepository(jdbc, mapper), audit),
                new KauthPasswordHasher(), new PasswordValidator(),
                new KauthUserSessionInvalidator(new KauthSessionRepository(jdbc), new KauthApiTokenRepository(jdbc)),
                Mockito.mock(SearchChangePublisher.class), audit, scopes);
        texts = new KauthChannelTexts(i18n, settings, users);
    }

    @BeforeEach
    void russianSystem() {
        settings.updateInstanceSettings(Map.of("system.default_language", "ru"));
    }

    @Test
    @DisplayName("The user's own active language wins")
    void usersLanguage() {
        var text = texts.render(user("en"), "login_code", Map.of("code", "123456", "minutes", "5"));

        assertThat(text.subject()).isEqualTo("Sign-in code");
        assertThat(text.body()).isEqualTo(
                "Sign-in code: 123456. Valid for 5 min. If you did not try to sign in, change your password.");
    }

    @Test
    @DisplayName("An inactive user language gives way to the system language from the settings")
    void systemLanguage() {
        settings.updateInstanceSettings(Map.of("system.default_language", "uz"));

        var text = texts.render(user("kk"), "channel_verify", Map.of("code", "654321", "minutes", "15"));

        assertThat(text.subject()).isEqualTo("Kanalni tasdiqlash");
        assertThat(text.body()).isEqualTo("Kanalni tasdiqlash kodi: 654321. 15 daqiqa amal qiladi.");
    }

    @Test
    @DisplayName("With neither language active the text is Russian")
    void russianLast() {
        settings.updateInstanceSettings(Map.of("system.default_language", "kk"));

        var text = texts.render(user("kk"), "password_reset", Map.of("link", "https://cms.test/x", "minutes", "15"));

        assertThat(text.subject()).isEqualTo("Сброс пароля");
        assertThat(text.body()).startsWith("Ссылка для смены пароля:\nhttps://cms.test/x").contains("15 мин.");
    }

    private static Long user(String language) {
        String login = "texts_" + UUID.randomUUID();
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language)
                        values (:login, :login, :login || '@test.local', 'hash', 'A', :language)
                        returning id
                        """)
                .param("login", login).param("language", language).query(Long.class).single();
    }
}
