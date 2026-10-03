package com.smartup24.cms.instance.kauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.kauth.repository.KauthPasswordResetRepository;
import com.smartup24.cms.instance.kauth.service.KauthInvitationIssued;
import com.smartup24.cms.instance.kauth.service.KauthInvitationService;
import com.smartup24.cms.instance.kauth.service.KauthOtpSender;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.spi.mail.MailProvider;
import com.smartup24.cms.spi.messenger.MessengerProvider;
import com.smartup24.cms.spi.sms.SmsProvider;
import com.smartup24.cms.spi.storage.StorageProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * An invitation sets the first password: while delivery is enforced it refuses a stub mail provider, which would only
 * write the link to the server log (plan 10/10, item 0.8; ADR-0032, 8).
 */
class KauthInvitationDeliveryTest {

    private static final long USER = 42L;

    private final MdUserService users = mock(MdUserService.class);
    private final KauthPasswordResetRepository links = mock(KauthPasswordResetRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    @Test
    @DisplayName("A stub mail provider refuses the invitation while delivery is enforced; no link is issued")
    void stubMailRefusesTheInvitation() {
        when(users.findAuthUserById(USER)).thenReturn(Optional.of(newUser()));

        assertThatThrownBy(() -> service("console_mail", true).invite(USER))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.auth.channel_not_deliverable")
                .hasFieldOrPropertyWithValue("params", Map.of("channel", "email"));
        verifyNoInteractions(links, events);
    }

    @Test
    @DisplayName("A real mail provider, or delivery not enforced, issues the invitation")
    void deliverableMailInvites() {
        when(users.findAuthUserById(USER)).thenReturn(Optional.of(newUser()));

        service("smtp", true).invite(USER);
        service("console_mail", false).invite(USER);

        verify(events, times(2)).publishEvent(any(KauthInvitationIssued.class));
        assertThat(mockingDetails(links).getInvocations()).isNotEmpty();
    }

    private KauthInvitationService service(String mail, boolean enforced) {
        return new KauthInvitationService(users, links, mock(AuditLogService.class), events, sender(mail, enforced));
    }

    private static KauthOtpSender sender(String mail, boolean enforced) {
        MailProvider mailProvider = mock(MailProvider.class);
        when(mailProvider.getProviderCode()).thenReturn(mail);
        MessengerProvider messenger = mock(MessengerProvider.class);
        when(messenger.getProviderCode()).thenReturn("console_messenger");
        SmsProvider sms = mock(SmsProvider.class);
        when(sms.getProviderCode()).thenReturn("console_sms");
        StorageProvider storage = mock(StorageProvider.class);
        when(storage.getProviderCode()).thenReturn("local");
        ProviderRegistry registry = new ProviderRegistry(
                List.of(storage),
                List.of(mailProvider),
                List.of(sms),
                List.of(messenger),
                "local",
                mail,
                "console_sms",
                "console_messenger");
        return new KauthOtpSender(registry, null, enforced);
    }

    private static MdUserService.AuthUser newUser() {
        Instant now = Instant.now();
        return new MdUserService.AuthUser(
                USER,
                "Invited",
                "invited",
                "invited@example.test",
                null,
                null,
                "A",
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                1L,
                now,
                now,
                1L);
    }
}
