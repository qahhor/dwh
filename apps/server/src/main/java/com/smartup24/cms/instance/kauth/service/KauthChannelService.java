package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.kauth.api.ChannelView;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.kauth.repository.KauthOtpCodeRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's contact channels: linking, confirming, choosing one for the second factor
 * (FR-AUTH-5).
 *
 * The {@code kauth_user_channels} table existed since V001, but there was no
 * endpoint over it: the {@code md.profile:manage_channels} permission sat dead in
 * the catalog, nothing could link a channel, and second-factor sign-in
 * sent the code nowhere.
 *
 * Ownership of an address is proved by a code: a channel becomes confirmed only
 * after the user returns the code sent to that address. An unconfirmed
 * channel is not used for the second factor; otherwise a typo in the address would mean
 * sending a sign-in code to a stranger.
 */
@Service
public class KauthChannelService {

    /** Channel order of preference for the sign-in code. */
    static final List<String> OTP_CHANNEL_PRIORITY =
            List.of(KauthPref.CHANNEL_TELEGRAM, KauthPref.CHANNEL_SMS, KauthPref.CHANNEL_EMAIL);

    private static final Set<String> SUPPORTED_CHANNELS =
            Set.of(KauthPref.CHANNEL_TELEGRAM, KauthPref.CHANNEL_SMS, KauthPref.CHANNEL_EMAIL);

    /** Sorted: a Set prints in a different order on every run, and the text shows the list. */
    private static final String ALLOWED_CHANNELS =
            String.join(", ", SUPPORTED_CHANNELS.stream().sorted().toList());

    private static final int VERIFICATION_TTL_MINUTES = 15;

    private final KauthChannelRepository channelRepository;
    private final KauthOtpCodeRepository otpCodeRepository;
    private final KauthOtpSender otpSender;
    private final AuditLogService auditLogService;
    private final KauthCredentialGuard credentialGuard;
    private final SecureRandom secureRandom = new SecureRandom();

    public KauthChannelService(
            KauthChannelRepository channelRepository,
            KauthOtpCodeRepository otpCodeRepository,
            KauthOtpSender otpSender,
            AuditLogService auditLogService,
            KauthCredentialGuard credentialGuard) {
        this.channelRepository = channelRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.otpSender = otpSender;
        this.auditLogService = auditLogService;
        this.credentialGuard = credentialGuard;
    }

    @Transactional(readOnly = true)
    public List<ChannelView> listChannels(Long userId) {
        return channelRepository.findByUserId(userId).stream()
                .map(c -> new ChannelView(c.id(), c.userId(), c.channel(), c.address(), c.isVerified(), c.createdAt()))
                .toList();
    }

    /**
     * Links a channel: the record is created unconfirmed and a code is sent to the address.
     *
     * @return the token to bring to {@link #confirmChannel}
     */
    @Transactional
    public String bindChannel(KauthPrincipal principal, String channel, String address) {
        credentialGuard.requireCurrent(principal);
        Long userId = principal.userId();
        String normalized = normalizeChannel(channel);
        if (address == null || address.isBlank()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "error.auth.channel_address_required");
        }

        otpSender.requireDeliverable(normalized);
        var record = channelRepository.bindOrUpdate(userId, normalized, address.trim(), false);

        String verifyToken = randomToken();
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        otpCodeRepository.create(
                userId,
                principal.authenticationVersion(),
                normalized,
                KauthPasswordHasher.sha256(code),
                KauthPasswordHasher.sha256(verifyToken),
                "channel_verify",
                Instant.now().plusSeconds(VERIFICATION_TTL_MINUTES * 60L));

        otpSender.sendVerificationCode(record, code);

        // The address is personal data; only the fact and the channel go to the log.
        auditLogService.logChange(
                "kauth_user_channels",
                userId + ":" + normalized,
                "U",
                List.of("channel", "is_verified"),
                null,
                Map.of("channel", normalized, "is_verified", false));

        return verifyToken;
    }

    /**
     * Confirms ownership of an address. Until it is confirmed, no sign-in code goes there.
     *
     * <p>A refusal ({@link ApiException}) commits the transaction: a rollback used to restore the spent attempt
     * (plan 10/10, item 0.6).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void confirmChannel(KauthPrincipal principal, String verifyToken, String code) {
        credentialGuard.requireCurrent(principal);
        Long userId = principal.userId();
        var otp = otpCodeRepository
                .findActiveByTokenHash(KauthPasswordHasher.sha256(verifyToken), "channel_verify")
                .orElseThrow(
                        () -> ApiException.badRequest(ErrorCode.OTP_INVALID, "error.auth.verification_token_invalid"));

        if (!otp.userId().equals(userId) || otp.authenticationVersion() != principal.authenticationVersion()) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "error.auth.verification_token_invalid");
        }
        if (otp.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.OTP_EXPIRED);
        }
        // The attempt is spent before comparing: comparing first let parallel guessing through.
        if (!otpCodeRepository.claimAttempt(otp.id())) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "error.auth.verification_token_invalid");
        }
        if (!KauthPasswordHasher.sha256(code).equals(otp.codeHash())) {
            if (otp.attemptsLeft() <= 1) {
                throw ApiException.locked(ErrorCode.OTP_ATTEMPTS_EXCEEDED, "error.auth.attempts_exceeded");
            }
            throw new ApiException(ErrorCode.OTP_INVALID);
        }

        if (!otpCodeRepository.consume(otp.id(), userId, principal.authenticationVersion(), "channel_verify")) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "error.auth.verification_token_invalid");
        }
        var channel = channelRepository
                .findByUserIdAndChannel(userId, otp.channel())
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.auth.channel_not_found"));
        channelRepository.bindOrUpdate(userId, channel.channel(), channel.address(), true);

        auditLogService.logChange(
                "kauth_user_channels",
                userId + ":" + channel.channel(),
                "U",
                List.of("is_verified"),
                Map.of("channel", channel.channel(), "is_verified", false),
                Map.of("channel", channel.channel(), "is_verified", true));
    }

    @Transactional
    public void unbindChannel(Long userId, String channel) {
        String normalized = normalizeChannel(channel);
        channelRepository.delete(userId, normalized);

        auditLogService.logChange(
                "kauth_user_channels",
                userId + ":" + normalized,
                "D",
                List.of("channel"),
                Map.of("channel", normalized),
                null);
    }

    /**
     * The channel for the sign-in code: a confirmed one, in order of preference.
     * Without such a channel the sign-in is refused with a clear reason, rather than
     * a code being sent nowhere.
     */
    @Transactional(readOnly = true, noRollbackFor = ApiException.class)
    public KauthChannelRepository.ChannelRecord resolveOtpChannel(Long userId) {
        var channels = channelRepository.findByUserId(userId);
        for (String preferred : OTP_CHANNEL_PRIORITY) {
            for (var candidate : channels) {
                if (candidate.channel().equals(preferred) && candidate.isVerified()) {
                    return candidate;
                }
            }
        }
        throw ApiException.conflict(ErrorCode.OTP_CHANNEL_MISSING, "error.auth.otp_channel_missing");
    }

    private static String normalizeChannel(String channel) {
        String normalized = channel != null ? channel.trim().toLowerCase() : "";
        if (!SUPPORTED_CHANNELS.contains(normalized)) {
            // The old text echoed the caller's own input back to the same caller; the parameter keeps it.
            throw ApiException.badRequest(
                    ErrorCode.VALIDATION_FAILED,
                    "error.auth.channel_unknown",
                    Map.of("channel", String.valueOf(channel), "allowed", ALLOWED_CHANNELS));
        }
        return normalized;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
