package com.smartup24.cms.instance.kauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.kauth.repository.KauthLoginAttemptRepository;
import com.smartup24.cms.instance.kauth.repository.KauthPasswordResetRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.kauth.service.KauthChannelTexts;
import com.smartup24.cms.instance.kauth.service.KauthOtpSender;
import com.smartup24.cms.instance.kauth.service.KauthPasswordHasher;
import com.smartup24.cms.instance.kauth.service.KauthPasswordResetLinkIssued;
import com.smartup24.cms.instance.kauth.service.KauthPasswordResetLinkSender;
import com.smartup24.cms.instance.kauth.service.KauthPasswordResetService;
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
import com.smartup24.cms.instance.md.service.MdUserSecurityService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.mail.MailProvider;
import com.smartup24.cms.spi.mail.MailSendResult;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.messenger.MessengerProvider;
import com.smartup24.cms.spi.messenger.MessengerSendResult;
import com.smartup24.cms.spi.sms.SmsProvider;
import com.smartup24.cms.spi.storage.StorageProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 0.1: password reset by a one-time link, on the real migrated schema.
 *
 * <p>Before the fix the repository wrote to a table that does not exist: a known email answered 5xx, an unknown
 * one 204, and no code was ever delivered.
 */
class KauthPasswordResetIntegrationTest {

    private static final String PASSWORD = "ResetProbe-Old-2026"; // gitleaks:allow -- isolated test credential
    private static final String NEW_PASSWORD = "ResetProbe-New-2026"; // gitleaks:allow -- isolated test credential
    private static final Pattern TOKEN = Pattern.compile("/reset-password#token=([A-Za-z0-9_-]+)");

    static JdbcClient jdbc;
    static KauthPasswordResetService resetService;
    static KauthChannelRepository channelRepository;
    static KauthSessionRepository sessionRepository;
    static CapturingMail mail;
    static CapturingMessenger messenger;

    @BeforeAll
    static void setUp() {
        var ds = TestDatabases.migratedCopy("smc_password_reset");
        jdbc = JdbcClient.create(ds);

        var mapper = new ObjectMapper();
        var auditLogService = new AuditLogService(new AuditLogRepository(jdbc, mapper), null, new AuditDataRedactor());
        channelRepository = new KauthChannelRepository(jdbc);
        sessionRepository = new KauthSessionRepository(jdbc);
        mail = new CapturingMail();
        messenger = new CapturingMessenger();

        var registry = new ProviderRegistry(
                List.of(storageStub()),
                List.of(mail),
                List.of(smsStub()),
                List.of(messenger),
                "local",
                "smtp",
                "console_sms",
                "telegram");

        var scopes = new MdScopeService(
                new MdScopeRepository(jdbc),
                new MdOrgUnitRepository(jdbc),
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                auditLogService);
        var userService = new MdUserService(
                new MdUserRepository(jdbc, mapper),
                new MdRoleRepository(jdbc),
                new MdCustomFieldService(new MdCustomFieldRepository(jdbc, mapper), auditLogService),
                new KauthPasswordHasher(),
                new PasswordValidator(),
                Mockito.mock(SearchChangePublisher.class),
                auditLogService,
                scopes);
        var userSecurityService = new MdUserSecurityService(
                new MdUserRepository(jdbc, mapper),
                new KauthPasswordHasher(),
                new PasswordValidator(),
                new KauthUserSessionInvalidator(sessionRepository, new KauthApiTokenRepository(jdbc)),
                Mockito.mock(SearchChangePublisher.class),
                auditLogService);
        var i18n = new MdI18nService(new MdI18nRepository(jdbc, mapper), new MdI18nCatalog(mapper), auditLogService);
        var texts = new KauthChannelTexts(
                i18n,
                new MdSettingService(
                        new MdSettingRepository(jdbc), new MdUserRepository(jdbc, mapper), i18n, auditLogService),
                userService);
        var linkSender =
                new KauthPasswordResetLinkSender(new KauthOtpSender(registry, texts), "https://cms.example.test/");

        resetService = new KauthPasswordResetService(
                userService,
                userSecurityService,
                channelRepository,
                new KauthPasswordResetRepository(jdbc),
                new KauthLoginAttemptRepository(jdbc),
                new KauthPasswordHasher(),
                new PasswordValidator(),
                auditLogService,
                // In the application the link leaves after the commit, on another thread; here it leaves at once.
                event -> linkSender.onLinkIssued((KauthPasswordResetLinkIssued) event),
                new DataSourceTransactionManager(ds));
    }

    @BeforeEach
    void clearOutbox() {
        mail.sent.clear();
        messenger.sent.clear();
    }

    @Test
    @DisplayName("Email: request → link → new password → every session closed")
    void emailLinkSetsPasswordAndClosesSessions() {
        Long userId = createUser("reset_mail");
        channelRepository.bindOrUpdate(userId, "email", "reset_mail@mailbox.test", true);
        openSession(userId);

        resetService.requestReset("reset_mail@test.local", "10.1.0.1", "ua");

        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.getFirst().recipientEmail()).isEqualTo("reset_mail@mailbox.test");
        String token = token(mail.sent.getFirst().textBody());
        assertThat(mail.sent.getFirst().subject()).isEqualTo("Сброс пароля");
        assertThat(mail.sent.getFirst().textBody())
                .startsWith("Ссылка для смены пароля:\nhttps://cms.example.test/reset-password#token=");

        resetService.confirmReset(token, NEW_PASSWORD, "10.1.0.1", "ua");

        assertThat(new KauthPasswordHasher().verifyPassword(NEW_PASSWORD, passwordHash(userId)))
                .isTrue();
        assertThat(openSessions(userId))
                .as("a reset ends every session of the user")
                .isZero();
    }

    @Test
    @DisplayName("Telegram: the link reaches the confirmed chat when there is no confirmed email")
    void telegramLinkSetsPassword() {
        Long userId = createUser("reset_tg");
        channelRepository.bindOrUpdate(userId, "email", "reset_tg@mailbox.test", false);
        channelRepository.bindOrUpdate(userId, "telegram", "chat-reset", true);

        resetService.requestReset("reset_tg@test.local", "10.1.0.2", "ua");

        assertThat(mail.sent).as("an unconfirmed address gets nothing").isEmpty();
        assertThat(messenger.sent).hasSize(1);
        assertThat(messenger.sent.getFirst().recipientChatId()).isEqualTo("chat-reset");

        resetService.confirmReset(token(messenger.sent.getFirst().textMarkdown()), NEW_PASSWORD, "10.1.0.2", "ua");

        assertThat(new KauthPasswordHasher().verifyPassword(NEW_PASSWORD, passwordHash(userId)))
                .isTrue();
    }

    @Test
    @DisplayName("The message is in the user's language")
    void messageIsInTheUsersLanguage() {
        Long userId = createUser("reset_en");
        jdbc.sql("update md_users set language = 'en' where id = :id")
                .param("id", userId)
                .update();
        channelRepository.bindOrUpdate(userId, "email", "reset_en@mailbox.test", true);

        resetService.requestReset("reset_en@test.local", "10.1.0.8", "ua");

        assertThat(mail.sent.getFirst().subject()).isEqualTo("Password reset");
        assertThat(mail.sent.getFirst().textBody())
                .startsWith("Link to set a new password:\nhttps://cms.example.test/reset-password#token=")
                .contains("15 min");
    }

    @Test
    @DisplayName("A used link and an expired link are rejected")
    void usedAndExpiredLinksAreRejected() {
        Long userId = createUser("reset_once");
        channelRepository.bindOrUpdate(userId, "email", "reset_once@mailbox.test", true);

        resetService.requestReset("reset_once@test.local", "10.1.0.3", "ua");
        String used = token(mail.sent.getFirst().textBody());
        resetService.confirmReset(used, NEW_PASSWORD, "10.1.0.3", "ua");

        assertRejected(() -> resetService.confirmReset(used, "ResetProbe-Again26", "10.1.0.3", "ua"));

        mail.sent.clear();
        resetService.requestReset("reset_once@test.local", "10.1.0.3", "ua");
        String expired = token(mail.sent.getFirst().textBody());
        jdbc.sql("update kauth_password_reset_codes set expires_at = now() - interval '1 minute' where user_id = :id")
                .param("id", userId)
                .update();

        assertRejected(() -> resetService.confirmReset(expired, "ResetProbe-Again26", "10.1.0.3", "ua"));
    }

    @Test
    @DisplayName("A new link voids the previous one")
    void newLinkVoidsThePreviousOne() {
        Long userId = createUser("reset_twice");
        channelRepository.bindOrUpdate(userId, "email", "reset_twice@mailbox.test", true);

        resetService.requestReset("reset_twice@test.local", "10.1.0.4", "ua");
        resetService.requestReset("reset_twice@test.local", "10.1.0.4", "ua");
        String first = token(mail.sent.get(0).textBody());
        String second = token(mail.sent.get(1).textBody());

        assertRejected(() -> resetService.confirmReset(first, NEW_PASSWORD, "10.1.0.4", "ua"));
        resetService.confirmReset(second, NEW_PASSWORD, "10.1.0.4", "ua");
    }

    @Test
    @DisplayName("After five rejected links the address is locked, valid links included")
    void fiveRejectedLinksLockTheAddress() {
        Long userId = createUser("reset_lock");
        channelRepository.bindOrUpdate(userId, "email", "reset_lock@mailbox.test", true);
        resetService.requestReset("reset_lock@test.local", "10.1.0.5", "ua");
        String valid = token(mail.sent.getFirst().textBody());

        for (int i = 0; i < 5; i++) {
            assertRejected(
                    () -> resetService.confirmReset("guess-" + UUID.randomUUID(), NEW_PASSWORD, "10.1.0.5", "ua"));
        }

        assertThatThrownBy(() -> resetService.confirmReset(valid, NEW_PASSWORD, "10.1.0.5", "ua"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        resetService.confirmReset(valid, NEW_PASSWORD, "10.1.0.6", "ua");
    }

    @Test
    @DisplayName("Known and unknown emails answer alike: no error either way, p95 apart by less than 50 ms")
    void knownAndUnknownEmailsLookTheSame() {
        Long userId = createUser("reset_timing");
        channelRepository.bindOrUpdate(userId, "email", "reset_timing@mailbox.test", true);
        for (int i = 0; i < 5; i++) { // warm-up
            resetService.requestReset("reset_timing@test.local", "10.1.0.7", "ua");
            resetService.requestReset("nobody-" + i + "@test.local", "10.1.0.7", "ua");
        }

        long[] known = new long[40];
        long[] unknown = new long[40];
        for (int i = 0; i < known.length; i++) {
            jdbc.sql("delete from kauth_password_reset_codes where user_id = :id")
                    .param("id", userId)
                    .update();
            known[i] = timed(() -> resetService.requestReset("reset_timing@test.local", "10.1.0.7", "ua"));
            unknown[i] = timed(() -> resetService.requestReset("nobody@test.local", "10.1.0.7", "ua"));
        }

        assertThat(Math.abs(p95(known) - p95(unknown)) / 1_000_000).isLessThan(50);
    }

    private static void assertRejected(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESET_CODE_INVALID));
    }

    private static String token(String text) {
        Matcher m = TOKEN.matcher(text);
        assertThat(m.find()).as("the message carries the link").isTrue();
        assertThat(m.group(1)).as("256-bit token").hasSize(43);
        return m.group(1);
    }

    private static long timed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return System.nanoTime() - start;
    }

    private static long p95(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.95) - 1];
    }

    private static Long createUser(String login) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change)
                        values (:login, :login, :login || '@test.local', :hash, 'A', 'ru', 'UTC',
                                '{}'::jsonb, false, false)
                        returning id
                        """)
                .param("login", login)
                .param("hash", new KauthPasswordHasher().hashPassword(PASSWORD))
                .query(Long.class)
                .single();
    }

    private static void openSession(Long userId) {
        sessionRepository.create(userId, 0, UUID.randomUUID().toString(), "127.0.0.1", "test", "test");
    }

    private static long openSessions(Long userId) {
        return jdbc.sql("select count(*) from kauth_sessions where user_id = :id and closed_at is null")
                .param("id", userId)
                .query(Long.class)
                .single();
    }

    private static String passwordHash(Long userId) {
        return jdbc.sql("select password_hash from md_users where id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    static class CapturingMail implements MailProvider {
        final List<MailMessage> sent = new ArrayList<>();

        @Override
        public String getProviderCode() {
            return "smtp";
        }

        @Override
        public MailSendResult send(MailMessage message) {
            sent.add(message);
            return MailSendResult.success("captured", 1);
        }

        @Override
        public ProviderHealth checkHealth() {
            return ProviderHealth.healthy(getProviderCode(), 1);
        }
    }

    static class CapturingMessenger implements MessengerProvider {
        final List<MessengerMessage> sent = new ArrayList<>();

        @Override
        public String getProviderCode() {
            return "telegram";
        }

        @Override
        public MessengerSendResult send(MessengerMessage message) {
            sent.add(message);
            return MessengerSendResult.success("captured", 1);
        }

        @Override
        public ProviderHealth checkHealth() {
            return ProviderHealth.healthy(getProviderCode(), 1);
        }
    }

    private static StorageProvider storageStub() {
        StorageProvider stub = Mockito.mock(StorageProvider.class);
        when(stub.getProviderCode()).thenReturn("local");
        return stub;
    }

    private static SmsProvider smsStub() {
        SmsProvider stub = Mockito.mock(SmsProvider.class);
        when(stub.getProviderCode()).thenReturn("console_sms");
        return stub;
    }
}
