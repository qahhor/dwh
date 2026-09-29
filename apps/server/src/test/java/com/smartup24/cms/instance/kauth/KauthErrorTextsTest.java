package com.smartup24.cms.instance.kauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.kauth.repository.KauthOtpCodeRepository;
import com.smartup24.cms.instance.kauth.service.KauthChannelService;
import com.smartup24.cms.instance.kauth.service.KauthCredentialGuard;
import com.smartup24.cms.instance.kauth.service.KauthOtpSender;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Plan 10/10, item 3.1: moving the sign-in, reset and webhook errors to catalog keys keeps the Russian words users
 * and clients saw before, parameters included.
 */
class KauthErrorTextsTest {

    private static String russian(String key, Map<String, ?> params) {
        return PackagedProblemMessages.russian().render(new MockHttpServletRequest(), key, params);
    }

    private static String english(String key, Map<String, ?> params) {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en");
        return new PackagedProblemMessages().render(request, key, params);
    }

    @Test
    @DisplayName("Sign-in refusals read as before in Russian")
    void signInTextsAreUnchanged() {
        assertThat(russian("error.invalid_credentials", Map.of())).isEqualTo("Неверный логин или пароль");
        assertThat(russian("error.auth.not_signed_in", Map.of())).isEqualTo("Пользователь не авторизован");
        assertThat(russian("error.auth.account_temporarily_locked", Map.of()))
                .isEqualTo("Учётная запись временно заблокирована из-за частых ошибок ввода пароля");
        assertThat(russian("error.auth.otp_token_invalid", Map.of())).isEqualTo("Некорректный OTP токен");
        assertThat(russian("error.otp_invalid", Map.of())).isEqualTo("Неверный код подтверждения");
        assertThat(russian("error.auth.reset_link_invalid", Map.of()))
                .isEqualTo("Ссылка недействительна: она устарела или уже использована. Запросите новую");
    }

    @Test
    @DisplayName("Channel errors fill their parameters in every language")
    void channelTextsCarryTheirParameters() {
        assertThat(russian("error.auth.channel_unknown", Map.of("channel", "fax", "allowed", "email, sms, telegram")))
                .isEqualTo("Неизвестный канал: fax. Допустимо: email, sms, telegram");
        assertThat(russian("error.auth.channel_not_deliverable", Map.of("channel", "email")))
                .isEqualTo("Канал email не настроен на сервере: сообщения туда не доставляются. "
                        + "Обратитесь к администратору");
        assertThat(english("error.auth.otp_send_failed", Map.of("channel", "sms")))
                .isEqualTo("Could not send the code to the sms channel. Contact the administrator");
    }

    @Test
    @DisplayName("An unknown channel names the caller's input and the allowed channels in a stable order")
    void unknownChannelCarriesItsParameters() {
        var service = new KauthChannelService(
                mock(KauthChannelRepository.class),
                mock(KauthOtpCodeRepository.class),
                mock(KauthOtpSender.class),
                mock(AuditLogService.class),
                mock(KauthCredentialGuard.class));

        assertThatThrownBy(() -> service.unbindChannel(1L, "fax")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getMessageKey()).isEqualTo("error.auth.channel_unknown");
            assertThat(e.getParams()).isEqualTo(Map.of("channel", "fax", "allowed", "email, sms, telegram"));
        });
    }

    @Test
    @DisplayName("Audit and webhook texts read as before in Russian")
    void auditAndWebhookTextsAreUnchanged() {
        assertThat(russian("error.audit.history_source_not_found", Map.of("key", "md_user")))
                .isEqualTo("Нет истории для записей вида «md_user»");
        assertThat(russian("error.webhook.url_scheme", Map.of()))
                .isEqualTo("URL вебхука должен начинаться с http:// или https://");
    }
}
