package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.sms.SmsMessage;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Delivers a one-time code to the user's channel (FR-AUTH-5).
 *
 * Originally the second-factor code was created in the database and **never
 * sent**: between `otpCodeRepository.create(...)` and returning the token there
 * was not a single provider call. Second-factor sign-in was entirely broken,
 * and nobody noticed because no one had 2FA enabled.
 *
 * Sending is synchronous, not through the notification outbox: the code lives five minutes,
 * and a retry queue works against the user here. A failed send
 * means the sign-in is refused, not "delivered later"; otherwise the client gets a token
 * that cannot be used.
 */
@Service
public class KauthOtpSender {

    private static final Logger log = LoggerFactory.getLogger(KauthOtpSender.class);

    private static final String STUB_PREFIX = "console_";

    private final ProviderRegistry providerRegistry;
    private final KauthChannelTexts texts;
    private final boolean deliveryEnforced;

    @Autowired
    public KauthOtpSender(
            ProviderRegistry providerRegistry,
            KauthChannelTexts texts,
            @Value("${smc.delivery.enforce:true}") boolean deliveryEnforced) {
        this.providerRegistry = providerRegistry;
        this.texts = texts;
        this.deliveryEnforced = deliveryEnforced;
    }

    /** Without enforcement: tests and tools that deliver to stubs on purpose. */
    public KauthOtpSender(ProviderRegistry providerRegistry, KauthChannelTexts texts) {
        this(providerRegistry, texts, false);
    }

    /** Code of the provider behind a channel; {@code console_*} is a stub that only writes to the log. */
    public String providerCode(String channel) {
        return switch (channel) {
            case KauthPref.CHANNEL_TELEGRAM ->
                providerRegistry.getActiveMessengerProvider().getProviderCode();
            case KauthPref.CHANNEL_SMS ->
                providerRegistry.getActiveSmsProvider().getProviderCode();
            case KauthPref.CHANNEL_EMAIL ->
                providerRegistry.getActiveMailProvider().getProviderCode();
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
            throw ApiException.conflict(
                    ErrorCode.DELIVERY_CHANNEL_NOT_CONFIGURED,
                    "error.auth.channel_not_deliverable",
                    Map.of("channel", channel));
        }
    }

    /**
     * @param channel        the user's channel record
     * @param text           the text with the code in clear, only for sending;
     *                       the database and log keep only the code's SHA-256
     * @param idempotencyKey the idempotency key for the provider
     */
    public void send(KauthChannelRepository.ChannelRecord channel, String subject, String text, String idempotencyKey) {
        boolean delivered = switch (channel.channel()) {
            case KauthPref.CHANNEL_TELEGRAM ->
                providerRegistry
                        .getActiveMessengerProvider()
                        .send(new MessengerMessage(channel.address(), text, null, null, idempotencyKey))
                        .isSuccess();
            case KauthPref.CHANNEL_SMS ->
                providerRegistry
                        .getActiveSmsProvider()
                        .send(new SmsMessage(channel.address(), text, null, idempotencyKey))
                        .isSuccess();
            case KauthPref.CHANNEL_EMAIL ->
                providerRegistry
                        .getActiveMailProvider()
                        .send(new MailMessage(channel.address(), subject, null, text, List.of(), idempotencyKey))
                        .isSuccess();
            default ->
                throw ApiException.badRequest(
                        ErrorCode.VALIDATION_FAILED,
                        "error.auth.delivery_channel_unknown",
                        Map.of("channel", channel.channel()));
        };

        if (!delivered) {
            // The recipient address is personal data and is not written to the log.
            log.warn("Код не доставлен в канал {}", channel.channel());
            throw new ApiException(
                    ErrorCode.OTP_SEND_FAILED, "error.auth.otp_send_failed", Map.of("channel", channel.channel()));
        }
    }

    public void sendLoginCode(KauthChannelRepository.ChannelRecord channel, String code) {
        sendText(
                channel,
                "login_code",
                Map.of("code", code, "minutes", "5"),
                "login-" + KauthPasswordHasher.sha256(code));
    }

    public void sendVerificationCode(KauthChannelRepository.ChannelRecord channel, String code) {
        sendText(
                channel,
                "channel_verify",
                Map.of("code", code, "minutes", "15"),
                "verify-" + KauthPasswordHasher.sha256(code));
    }

    /** A password reset link; {@code minutes} is what the message promises, rounded up. */
    public void sendResetLink(KauthChannelRepository.ChannelRecord channel, String link, long minutes, String token) {
        sendText(
                channel,
                "password_reset",
                Map.of("link", link, "minutes", Long.toString(minutes)),
                "reset-" + KauthPasswordHasher.sha256(token));
    }

    /** The invitation of a new user: the login and the link that sets the first password (ADR-0032, 8). */
    public void sendInvitation(
            KauthChannelRepository.ChannelRecord channel, String login, String link, long hours, String token) {
        sendText(
                channel,
                "invitation",
                Map.of("login", login, "link", link, "hours", Long.toString(hours)),
                "invite-" + KauthPasswordHasher.sha256(token));
    }

    /** A catalog text in the recipient's language ({@link KauthChannelTexts}). */
    private void sendText(
            KauthChannelRepository.ChannelRecord channel,
            String name,
            Map<String, String> params,
            String idempotencyKey) {
        KauthChannelTexts.Text text = texts.render(channel.userId(), name, params);
        send(channel, text.subject(), text.body(), idempotencyKey);
    }
}
