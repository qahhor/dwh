package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.provider.ProviderRegistry;
import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.kauth.repository.KauthChannelRepository;
import com.greenwhite.dwh.spi.mail.MailMessage;
import com.greenwhite.dwh.spi.messenger.MessengerMessage;
import com.greenwhite.dwh.spi.sms.SmsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Доставка одноразового кода в канал пользователя (FR-AUTH-5).
 *
 * До пересмотра M3 30.08 код второго фактора создавался в базе и **никуда не
 * отправлялся**: между `otpCodeRepository.create(...)` и возвратом токена не
 * было ни одного вызова провайдера. Вход по второму фактору был неработоспособен
 * целиком, и это не всплывало, потому что 2FA ни у кого не была включена.
 *
 * Отправка синхронная, а не через outbox оповещений: код живёт пять минут, и
 * очередь с повторами здесь работает против пользователя. Провал отправки —
 * это отказ входа, а не «доставим позже»: иначе клиент получает токен, которым
 * невозможно воспользоваться.
 */
@Service
public class KauthOtpSender {

    private static final Logger log = LoggerFactory.getLogger(KauthOtpSender.class);

    private final ProviderRegistry providerRegistry;

    public KauthOtpSender(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    /**
     * @param channel        запись канала пользователя
     * @param text           текст с кодом открытым текстом — только для отправки,
     *                       в базе и журнале живёт лишь SHA-256 кода
     * @param idempotencyKey ключ идемпотентности для провайдера
     */
    public void send(KauthChannelRepository.ChannelRecord channel, String subject, String text, String idempotencyKey) {
        boolean delivered = switch (channel.channel()) {
            case KauthPref.CHANNEL_TELEGRAM -> providerRegistry.getActiveMessengerProvider()
                    .send(new MessengerMessage(channel.address(), text, null, null, idempotencyKey))
                    .isSuccess();
            case KauthPref.CHANNEL_SMS -> providerRegistry.getActiveSmsProvider()
                    .send(new SmsMessage(channel.address(), text, null, idempotencyKey))
                    .isSuccess();
            case KauthPref.CHANNEL_EMAIL -> providerRegistry.getActiveMailProvider()
                    .send(new MailMessage(channel.address(), subject, null, text, List.of(), idempotencyKey))
                    .isSuccess();
            default -> throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "Неизвестный канал доставки: " + channel.channel());
        };

        if (!delivered) {
            // Адрес получателя — персональные данные, в журнал не пишем.
            log.warn("Код не доставлен в канал {}", channel.channel());
            throw new ApiException(ErrorCode.OTP_SEND_FAILED,
                    "Не удалось отправить код в канал " + channel.channel() + ". Обратитесь к администратору");
        }
    }

    public void sendLoginCode(KauthChannelRepository.ChannelRecord channel, String code) {
        send(channel, "Код входа",
                "Код входа: " + code + ". Действует 5 минут. "
                        + "Если вы не входили в систему, смените пароль.",
                "login-" + KauthPasswordHasher.sha256(code));
    }

    public void sendVerificationCode(KauthChannelRepository.ChannelRecord channel, String code) {
        send(channel, "Подтверждение канала",
                "Код подтверждения канала: " + code + ". Действует 15 минут.",
                "verify-" + KauthPasswordHasher.sha256(code));
    }
}
