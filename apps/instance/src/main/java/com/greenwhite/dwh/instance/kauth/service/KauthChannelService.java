package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.kauth.repository.KauthChannelRepository;
import com.greenwhite.dwh.instance.kauth.repository.KauthOtpCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Каналы связи пользователя: привязка, подтверждение, выбор для второго фактора
 * (FR-AUTH-5).
 *
 * Таблица {@code kauth_user_channels} существовала с V001, но ни одного
 * эндпоинта над ней не было: право {@code iam.profile:manage_channels} висело в
 * каталоге мёртвым, привязать канал было нечем, а вход по второму фактору
 * отправлял код в никуда.
 *
 * Владение адресом доказывается кодом: канал становится подтверждённым только
 * после того, как пользователь вернул код, пришедший на этот адрес. Неподтверждённый
 * канал для второго фактора не используется — иначе опечатка в адресе означала бы
 * отправку кода входа постороннему.
 */
@Service
public class KauthChannelService {

    /** Порядок предпочтения канала для кода входа. */
    private static final List<String> OTP_CHANNEL_PRIORITY =
            List.of(KauthPref.CHANNEL_TELEGRAM, KauthPref.CHANNEL_SMS, KauthPref.CHANNEL_EMAIL);

    private static final Set<String> SUPPORTED_CHANNELS =
            Set.of(KauthPref.CHANNEL_TELEGRAM, KauthPref.CHANNEL_SMS, KauthPref.CHANNEL_EMAIL);

    private static final int VERIFICATION_TTL_MINUTES = 15;

    private final KauthChannelRepository channelRepository;
    private final KauthOtpCodeRepository otpCodeRepository;
    private final KauthOtpSender otpSender;
    private final AuditLogService auditLogService;
    private final SecureRandom secureRandom = new SecureRandom();

    public KauthChannelService(KauthChannelRepository channelRepository,
                               KauthOtpCodeRepository otpCodeRepository,
                               KauthOtpSender otpSender,
                               AuditLogService auditLogService) {
        this.channelRepository = channelRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.otpSender = otpSender;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<KauthChannelRepository.ChannelRecord> listChannels(Long userId) {
        return channelRepository.findByUserId(userId);
    }

    /**
     * Привязка канала: запись создаётся неподтверждённой, на адрес уходит код.
     *
     * @return токен, с которым надо прийти в {@link #confirmChannel}
     */
    @Transactional
    public String bindChannel(Long userId, String channel, String address) {
        String normalized = normalizeChannel(channel);
        if (address == null || address.isBlank()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Адрес канала не может быть пустым");
        }

        var record = channelRepository.bindOrUpdate(userId, normalized, address.trim(), false);

        String verifyToken = randomToken();
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        otpCodeRepository.create(userId, normalized,
                KauthPasswordHasher.sha256(code), KauthPasswordHasher.sha256(verifyToken),
                "channel_verify", Instant.now().plusSeconds(VERIFICATION_TTL_MINUTES * 60L));

        otpSender.sendVerificationCode(record, code);

        // Адрес — персональные данные, в журнал идёт только факт и канал.
        auditLogService.logChange("kauth_user_channels", userId + ":" + normalized, "U",
                List.of("channel", "is_verified"),
                null,
                Map.of("channel", normalized, "is_verified", false));

        return verifyToken;
    }

    /** Подтверждение владения адресом. Пока не подтверждён — код входа туда не уйдёт. */
    @Transactional
    public void confirmChannel(Long userId, String verifyToken, String code) {
        var otp = otpCodeRepository.findActiveByTokenHash(
                        KauthPasswordHasher.sha256(verifyToken), "channel_verify")
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный токен подтверждения"));

        if (!otp.userId().equals(userId)) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный токен подтверждения");
        }
        if (otp.expiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest(ErrorCode.OTP_EXPIRED, "Срок действия кода подтверждения истёк");
        }
        if (!KauthPasswordHasher.sha256(code).equals(otp.codeHash())) {
            otpCodeRepository.decrementAttempts(otp.id());
            if (otp.attemptsLeft() <= 1) {
                throw ApiException.locked(ErrorCode.OTP_ATTEMPTS_EXCEEDED, "Превышено количество попыток");
            }
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Неверный код подтверждения");
        }

        otpCodeRepository.markAsUsed(otp.id());
        var channel = channelRepository.findByUserIdAndChannel(userId, otp.channel())
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Канал не найден"));
        channelRepository.bindOrUpdate(userId, channel.channel(), channel.address(), true);

        auditLogService.logChange("kauth_user_channels", userId + ":" + channel.channel(), "U",
                List.of("is_verified"),
                Map.of("channel", channel.channel(), "is_verified", false),
                Map.of("channel", channel.channel(), "is_verified", true));
    }

    @Transactional
    public void unbindChannel(Long userId, String channel) {
        String normalized = normalizeChannel(channel);
        channelRepository.delete(userId, normalized);

        auditLogService.logChange("kauth_user_channels", userId + ":" + normalized, "D",
                List.of("channel"),
                Map.of("channel", normalized),
                null);
    }

    /**
     * Канал для кода входа: подтверждённый, по порядку предпочтения.
     * Отсутствие такого канала — отказ входа с внятной причиной, а не код,
     * отправленный в никуда.
     */
    @Transactional(readOnly = true)
    public KauthChannelRepository.ChannelRecord resolveOtpChannel(Long userId) {
        var channels = channelRepository.findByUserId(userId);
        for (String preferred : OTP_CHANNEL_PRIORITY) {
            for (var candidate : channels) {
                if (candidate.channel().equals(preferred) && candidate.isVerified()) {
                    return candidate;
                }
            }
        }
        throw ApiException.conflict(ErrorCode.OTP_CHANNEL_MISSING,
                "Двухфакторный вход включён, но подтверждённого канала связи нет. "
                        + "Привяжите канал в профиле или обратитесь к администратору");
    }

    private static String normalizeChannel(String channel) {
        String normalized = channel != null ? channel.trim().toLowerCase() : "";
        if (!SUPPORTED_CHANNELS.contains(normalized)) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "Неизвестный канал: " + channel + ". Допустимо: " + SUPPORTED_CHANNELS);
        }
        return normalized;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
