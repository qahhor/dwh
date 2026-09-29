package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.kauth.repository.KauthLoginAttemptRepository;
import com.smartup24.cms.instance.kauth.repository.KauthPasswordResetRepository;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdUserSecurityService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password reset by a one-time link (plan 10/10, item 0.1).
 *
 * <ul>
 *   <li>The link goes to a confirmed channel of the user, email first, then Telegram. An address nobody confirmed is
 *       not used: a typo in it would send the link to a stranger.</li>
 *   <li>A request answers the same way whether the email is known or not, and delivery runs after the commit off the
 *       request thread, so neither the status nor the time tells who has an account.</li>
 *   <li>The token has 256 bits, lives {@link #LINK_TTL}, is stored as a hash, belongs to one user and one
 *       authentication generation (a later password change or reset voids it) and is used up by one atomic update.
 *       A new link voids the previous one.</li>
 *   <li>After {@link #MAX_FAILED_CONFIRMS} rejected links from one address within {@link #FAILURE_WINDOW} that
 *       address is refused, valid links included, until the window passes.</li>
 * </ul>
 */
@Service
public class KauthPasswordResetService {

    static final Duration LINK_TTL = Duration.ofMinutes(15);
    static final int MAX_FAILED_CONFIRMS = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    /** Links one user may receive per hour: a reset form must not become a way to flood somebody's inbox. */
    static final int MAX_LINKS_PER_HOUR = 3;

    static final String ATTEMPT_LOGIN = "password-reset";
    static final String ATTEMPT_REJECTED = "RESET_LINK_REJECTED";

    private static final List<String> CHANNEL_PRIORITY = List.of(KauthPref.CHANNEL_EMAIL, KauthPref.CHANNEL_TELEGRAM);

    private final MdUserService userService;
    private final MdUserSecurityService userSecurityService;
    private final KauthChannelRepository channelRepository;
    private final KauthPasswordResetRepository resetRepository;
    private final KauthLoginAttemptRepository attemptRepository;
    private final KauthPasswordHasher passwordHasher;
    private final PasswordValidator passwordValidator;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final SecureRandom secureRandom = new SecureRandom();

    public KauthPasswordResetService(
            MdUserService userService,
            MdUserSecurityService userSecurityService,
            KauthChannelRepository channelRepository,
            KauthPasswordResetRepository resetRepository,
            KauthLoginAttemptRepository attemptRepository,
            KauthPasswordHasher passwordHasher,
            PasswordValidator passwordValidator,
            AuditLogService auditLogService,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.userService = userService;
        this.userSecurityService = userSecurityService;
        this.channelRepository = channelRepository;
        this.resetRepository = resetRepository;
        this.attemptRepository = attemptRepository;
        this.passwordHasher = passwordHasher;
        this.passwordValidator = passwordValidator;
        this.auditLogService = auditLogService;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Issues a link when the email belongs to an active user with a confirmed channel; says nothing either way. */
    public void requestReset(String email, String ip, String userAgent) {
        transaction.executeWithoutResult(status -> {
            var user = userService.findAuthUserByEmail(email).filter(u -> MdPref.STATE_ACTIVE.equals(u.state()));
            if (user.isEmpty()) {
                auditLogService.logSecurityEvent(
                        "PASSWORD_RESET_REQUESTED", null, ip, userAgent, Map.of("result", "unknown_or_inactive"));
                return;
            }
            Long userId = user.get().id();
            var channel = confirmedChannel(userId);
            if (channel.isEmpty()) {
                auditLogService.logSecurityEvent(
                        "PASSWORD_RESET_REQUESTED", userId, ip, userAgent, Map.of("result", "no_confirmed_channel"));
                return;
            }
            // Two requests at once would each revoke nothing and both issue a link: one at a time per user.
            resetRepository.lockUser(userId);
            Instant now = Instant.now();
            if (resetRepository.countIssuedSince(userId, now.minus(Duration.ofHours(1))) >= MAX_LINKS_PER_HOUR) {
                auditLogService.logSecurityEvent(
                        "PASSWORD_RESET_REQUESTED", userId, ip, userAgent, Map.of("result", "throttled"));
                return;
            }

            String token = randomToken();
            Instant expiresAt = now.plus(LINK_TTL);
            resetRepository.revokeActive(userId);
            resetRepository.create(
                    userId,
                    user.get().authenticationVersion(),
                    channel.get().channel(),
                    KauthPasswordHasher.sha256(token),
                    expiresAt);
            events.publishEvent(new KauthPasswordResetLinkIssued(channel.get(), token, expiresAt));
            auditLogService.logSecurityEvent(
                    "PASSWORD_RESET_REQUESTED",
                    userId,
                    ip,
                    userAgent,
                    Map.of("result", "link_issued", "channel", channel.get().channel()));
        });
    }

    /**
     * Sets the new password by the link's token and closes every session and API token of the user.
     *
     * <p>A rejected link is recorded outside the transaction that fails, otherwise the rollback would erase the
     * very record that limits guessing.
     */
    public void confirmReset(String token, String newPassword, String ip, String userAgent) {
        Instant windowStart = Instant.now().minus(FAILURE_WINDOW);
        if (attemptRepository.countFailedAttemptsForIpSince(ip, ATTEMPT_REJECTED, windowStart) >= MAX_FAILED_CONFIRMS) {
            auditLogService.logSecurityEvent("PASSWORD_RESET_LOCKED", null, ip, userAgent, Map.of());
            throw ApiException.locked(ErrorCode.RATE_LIMITED, "error.auth.too_many_reset_links");
        }

        var reset = token == null || token.isBlank()
                ? Optional.<KauthPasswordResetRepository.ResetRecord>empty()
                : resetRepository.findActive(KauthPasswordHasher.sha256(token));
        var user = reset.flatMap(r -> userService.findAuthUserById(r.userId()))
                .filter(u -> MdPref.STATE_ACTIVE.equals(u.state())
                        && u.authenticationVersion() == reset.get().authVersion());
        if (user.isEmpty()) {
            attemptRepository.recordAttempt(ATTEMPT_LOGIN, ip, false, ATTEMPT_REJECTED);
            throw rejectedLink();
        }

        passwordValidator.validate(newPassword, user.get().login());
        String newHash = passwordHasher.hashPassword(newPassword);

        transaction.executeWithoutResult(status -> {
            if (!resetRepository.consume(reset.get().id())
                    || !userSecurityService.resetPassword(
                            user.get().id(),
                            user.get().authenticationVersion(),
                            user.get().passwordHash(),
                            newHash)) {
                throw rejectedLink();
            }
            auditLogService.logSecurityEvent(
                    "PASSWORD_RESET_COMPLETED",
                    user.get().id(),
                    ip,
                    userAgent,
                    Map.of("channel", reset.get().channel()));
        });
    }

    private static ApiException rejectedLink() {
        return ApiException.badRequest(ErrorCode.RESET_CODE_INVALID, "error.auth.reset_link_invalid");
    }

    private Optional<KauthChannelRepository.ChannelRecord> confirmedChannel(Long userId) {
        var channels = channelRepository.findByUserId(userId);
        for (String preferred : CHANNEL_PRIORITY) {
            for (var candidate : channels) {
                if (candidate.channel().equals(preferred) && candidate.isVerified()) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
