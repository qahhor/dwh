package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.sms.SmsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private static final String STUB_PREFIX = "console_";

    private final ProviderRegistry providerRegistry;
    private final boolean deliveryEnforced;

    @Autowired
    public KauthOtpSender(ProviderRegistry providerRegistry,
                          @Value("${smc.delivery.enforce:true}") boolean deliveryEnforced) {
        this.providerRegistry = providerRegistry;
        this.deliveryEnforced = deliveryEnforced;
    }

    /** Without enforcement: tests and tools that deliver to stubs on purpose. */
    public KauthOtpSender(ProviderRegistry providerRegistry) {
        this(providerRegistry, false);
    }

    /** Code of the provider behind a channel; {@code console_*} is a stub that only writes to the log. */
    public String providerCode(String channel) {
        return switch (channel) {
            case KauthPref.CHANNEL_TELEGRAM -> providerRegistry.getActiveMessengerProvider().getProviderCode();
            case KauthPref.CHANNEL_SMS -> providerRegistry.getActiveSmsProvider().getProviderCode();
            case KauthPref.CHANNEL_EMAIL -> providerRegistry.getActiveMailProvider().getProviderCode();
            default -> STUB_PREFIX + channel;
        };
    }

    public static boolean isStub(String providerCode) {
        return providerCode.startsWith(STUB_PREFIX);
    }

    /**
     * Refuses a channel served by a stub while delivery is enforced (plan 10/10, item 0.8). Binding it would let a
     * user confirm it from the log and turn on two-factor sign-in on a channel that delivers nothing; the next
     * restart would then stop at {@link KauthDeliveryGuard}.
     */
    public void requireDeliverable(String channel) {
        if (deliveryEnforced && isStub(providerCode(channel))) {
            throw ApiException.conflict(ErrorCode.DELIVERY_CHANNEL_NOT_CONFIGURED,
                    "Канал " + channel + " не настроен на сервере: сообщения туда не доставляются. "
                            + "Обратитесь к администратору");
        }
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
