package com.smartup24.cms.instance.kauth;

import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.kauth.service.KauthDeliveryGuard;
import com.smartup24.cms.instance.kauth.service.KauthOtpSender;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.spi.mail.MailProvider;
import com.smartup24.cms.spi.messenger.MessengerProvider;
import com.smartup24.cms.spi.sms.SmsProvider;
import com.smartup24.cms.spi.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Plan 10/10, item 0.8: the instance does not start while two-factor users depend on a stub channel. */
class KauthDeliveryGuardTest {

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("dwh_delivery_guard_" + System.nanoTime()));
    }

    @Test
    @DisplayName("No two-factor users: the stubs do not matter")
    void noTwoFactorUsers() {
        assertThatCode(() -> guard("console_mail", "console_messenger", true).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A two-factor user whose code goes to a stub stops the start")
    void codeToAStubStopsTheStart() {
        twoFactorUser("guard_tg", "telegram", true);

        assertThatThrownBy(() -> guard("smtp", "console_messenger", true).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("telegram -> console_messenger (1 users)");
    }

    @Test
    @DisplayName("The channel is resolved as at sign-in: a real Telegram wins over a stubbed email")
    void channelIsResolvedAsAtSignIn() {
        Long id = twoFactorUser("guard_both", "telegram", true);
        new KauthChannelRepository(jdbc).bindOrUpdate(id, "email", "guard_both@mailbox.test", true);

        assertThatCode(() -> guard("console_mail", "telegram", true).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An unconfirmed channel is not a code channel; the switch turns the guard off")
    void unconfirmedChannelAndSwitch() {
        twoFactorUser("guard_unconfirmed", "email", false);
        assertThatCode(() -> guard("console_mail", "console_messenger", true).run(null)).doesNotThrowAnyException();

        twoFactorUser("guard_off", "email", true);
        assertThatCode(() -> guard("console_mail", "console_messenger", false).run(null)).doesNotThrowAnyException();
    }

    private KauthDeliveryGuard guard(String mail, String messenger, boolean enforced) {
        MailProvider mailProvider = mock(MailProvider.class);
        when(mailProvider.getProviderCode()).thenReturn(mail);
        MessengerProvider messengerProvider = mock(MessengerProvider.class);
        when(messengerProvider.getProviderCode()).thenReturn(messenger);
        SmsProvider sms = mock(SmsProvider.class);
        when(sms.getProviderCode()).thenReturn("console_sms");
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.getProviderCode()).thenReturn("local");
        var registry = new ProviderRegistry(List.of(storage), List.of(mailProvider), List.of(sms),
                List.of(messengerProvider), "local", mail, "console_sms", messenger);
        return new KauthDeliveryGuard(new KauthOtpSender(registry, null), jdbc, enforced);
    }

    private KauthOtpSender sender(String mail, String messenger, boolean enforced) {
        MailProvider mailProvider = mock(MailProvider.class);
        when(mailProvider.getProviderCode()).thenReturn(mail);
        MessengerProvider messengerProvider = mock(MessengerProvider.class);
        when(messengerProvider.getProviderCode()).thenReturn(messenger);
        SmsProvider sms = mock(SmsProvider.class);
        when(sms.getProviderCode()).thenReturn("console_sms");
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.getProviderCode()).thenReturn("local");
        return new KauthOtpSender(new ProviderRegistry(List.of(storage), List.of(mailProvider), List.of(sms),
                List.of(messengerProvider), "local", mail, "console_sms", messenger), null, enforced);
    }

    @Test
    @DisplayName("At run time a stubbed channel cannot be bound while delivery is enforced")
    void stubbedChannelCannotBeBound() {
        assertThatThrownBy(() -> sender("console_mail", "telegram", true).requireDeliverable("email"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("не настроен");
        assertThatCode(() -> sender("console_mail", "telegram", true).requireDeliverable("telegram"))
                .doesNotThrowAnyException();
        assertThatCode(() -> sender("console_mail", "telegram", false).requireDeliverable("email"))
                .doesNotThrowAnyException();
    }

    private Long twoFactorUser(String login, String channel, boolean verified) {
        Long id = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, is_2fa_enabled)
                        values (:login, :login, :login || '@test.local', 'hash', 'A', true)
                        returning id
                        """)
                .param("login", login).query(Long.class).single();
        new KauthChannelRepository(jdbc).bindOrUpdate(id, channel, login + "-address", verified);
        return id;
    }
}
