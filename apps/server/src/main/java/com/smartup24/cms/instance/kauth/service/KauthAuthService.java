package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.kauth.repository.KauthLoginAttemptRepository;
import com.smartup24.cms.instance.kauth.repository.KauthOtpCodeRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KauthAuthService {

    private static final int MAX_FAILED_ATTEMPTS_PER_IP = 10;
    private static final int MAX_FAILED_ATTEMPTS_PER_USER = 5;

    private final MdUserService userService;
    private final KauthSessionRepository sessionRepository;
    private final KauthLoginAttemptRepository loginAttemptRepository;
    private final KauthOtpCodeRepository otpCodeRepository;
    private final KauthPasswordHasher passwordHasher;
    private final PasswordValidator passwordValidator;
    private final AuditLogService auditLogService;
    private final KauthChannelService channelService;
    private final KauthOtpSender otpSender;
    private final SecureRandom secureRandom = new SecureRandom();
    /** Hash an unknown login is checked against, so that it costs one Argon2 verification like a known one. */
    private volatile String dummyPasswordHash;

    public KauthAuthService(
            MdUserService userService,
            KauthSessionRepository sessionRepository,
            KauthLoginAttemptRepository loginAttemptRepository,
            KauthOtpCodeRepository otpCodeRepository,
            KauthPasswordHasher passwordHasher,
            PasswordValidator passwordValidator,
            AuditLogService auditLogService,
            KauthChannelService channelService,
            KauthOtpSender otpSender) {
        this.userService = userService;
        this.sessionRepository = sessionRepository;
        this.loginAttemptRepository = loginAttemptRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.passwordHasher = passwordHasher;
        this.passwordValidator = passwordValidator;
        this.auditLogService = auditLogService;
        this.channelService = channelService;
        this.otpSender = otpSender;
    }

    /**
     * The answer does not tell which logins exist (plan 10/10, item 0.6): an unknown login costs one Argon2
     * verification like a wrong password, both answer "invalid credentials", and a blocked account shows its state
     * only after the right password.
     *
     * <p>A refusal ({@link ApiException}) commits: the rollback it used to cause erased the attempt the lockout counts
     * and the security event, so five wrong passwords never locked anything. Only refused credentials are counted;
     * a refusal by the lockout itself is logged, not counted, otherwise every try during the lock would renew it.
     * Other exceptions still roll everything back.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String login, String password, String ip, String userAgent, String deviceInfo) {
        Instant tenMinutesAgo = Instant.now().minusSeconds(600);

        int failedIp = loginAttemptRepository.countFailedAttemptsForIpSince(ip, tenMinutesAgo);
        if (failedIp >= MAX_FAILED_ATTEMPTS_PER_IP) {
            auditLogService.logSecurityEvent("IP_RATE_LIMITED", null, ip, userAgent, Map.of("login", login));
            throw ApiException.locked(ErrorCode.RATE_LIMITED, "Слишком много неудачных попыток входа с вашего IP");
        }

        int failedUser = loginAttemptRepository.countFailedAttemptsForLoginSince(login, tenMinutesAgo);
        if (failedUser >= MAX_FAILED_ATTEMPTS_PER_USER) {
            auditLogService.logSecurityEvent("LOGIN_LOCKED", null, ip, userAgent, Map.of("login", login));
            throw ApiException.locked(
                    ErrorCode.LOGIN_LOCKED, "Учётная запись временно заблокирована из-за частых ошибок ввода пароля");
        }

        var userOpt = userService.findAuthUserByLogin(login);
        String storedHash = userOpt.map(MdUserService.AuthUser::passwordHash).orElse(null);
        boolean passwordMatches =
                passwordHasher.verifyPassword(password, storedHash != null ? storedHash : dummyPasswordHash());
        if (userOpt.isEmpty() || storedHash == null || !passwordMatches) {
            refuse(
                    login,
                    ip,
                    userAgent,
                    userOpt.map(MdUserService.AuthUser::id).orElse(null),
                    userOpt.isEmpty() ? "USER_NOT_FOUND" : "INVALID_PASSWORD");
            throw ApiException.invalidCredentials();
        }

        var user = userOpt.get();
        if (MdPref.STATE_PASSIVE.equals(user.state())) {
            refuse(login, ip, userAgent, user.id(), "USER_BLOCKED");
            throw ApiException.conflict(ErrorCode.USER_BLOCKED, "Учётная запись заблокирована");
        }

        loginAttemptRepository.recordAttempt(login, ip, true, null);
        auditLogService.logSecurityEvent(
                "LOGIN_SUCCESS",
                user.id(),
                ip,
                userAgent,
                Map.of("login", login, "deviceInfo", deviceInfo != null ? deviceInfo : "web"));

        // FR-AUTH-5: второй фактор. Канал выбирает не код, а пользователь —
        // берём подтверждённый по порядку предпочтения. Нет канала — отказ со
        // внятной причиной, а не токен, которым нельзя воспользоваться.
        if (user.is2faEnabled()) {
            var channel = channelService.resolveOtpChannel(user.id());

            String otpToken = generateSecureToken();
            String otpCode = String.format("%06d", secureRandom.nextInt(1000000));

            otpCodeRepository.create(
                    user.id(),
                    user.authenticationVersion(),
                    channel.channel(),
                    KauthPasswordHasher.sha256(otpCode),
                    KauthPasswordHasher.sha256(otpToken),
                    "login",
                    Instant.now().plusSeconds(300));

            // Отправка синхронная: код живёт пять минут, очередь с повторами
            // здесь работает против пользователя. Провал — отказ входа.
            otpSender.sendLoginCode(channel, otpCode);

            auditLogService.logSecurityEvent(
                    "OTP_SENT", user.id(), ip, userAgent, Map.of("channel", channel.channel()));

            return LoginResult.requires2fa(otpToken, user.id());
        }

        // Issue Session
        String sessionToken = generateSecureToken();
        String sessionTokenHash = KauthPasswordHasher.sha256(sessionToken);
        var session = sessionRepository.create(
                user.id(), user.authenticationVersion(), sessionTokenHash, ip, userAgent, deviceInfo);

        return LoginResult.success(sessionToken, user, session);
    }

    /**
     * Like {@link #login}, a refusal commits: the attempt a wrong code used stays used. The attempt is taken before
     * the comparison ({@link KauthOtpCodeRepository#claimAttempt}); its row lock makes parallel guesses of one code
     * wait for each other, so a code gets exactly as many comparisons as it has attempts.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult verifyOtp(String otpToken, String code, String ip, String userAgent, String deviceInfo) {
        if (otpToken == null || otpToken.isBlank()) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен");
        }

        // Код ищется по хешу выданного токена и только по нему. До V015 здесь
        // стоял extractUserIdFromOtpToken(), возвращавший захардкоженную 1L:
        // любой непустой токен приводил к коду администратора.
        var otp = otpCodeRepository
                .findActiveByTokenHash(KauthPasswordHasher.sha256(otpToken), "login")
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен"));
        Long userId = otp.userId();
        if (otp.expiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest(ErrorCode.OTP_EXPIRED, "Срок действия OTP-кода истёк");
        }

        // No attempt to claim: another request used the code, or took its last attempt, while this one waited.
        if (!otpCodeRepository.claimAttempt(otp.id())) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен");
        }
        String inputHash = KauthPasswordHasher.sha256(code);
        if (!inputHash.equals(otp.codeHash())) {
            if (otp.attemptsLeft() <= 1) {
                throw ApiException.locked(ErrorCode.OTP_ATTEMPTS_EXCEEDED, "Превышено количество попыток ввода OTP");
            }
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Неверный код подтверждения");
        }

        var user = userService
                .findAuthUserById(userId)
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен"));
        if (!MdPref.STATE_ACTIVE.equals(user.state())
                || user.authenticationVersion() != otp.authenticationVersion()
                || !otpCodeRepository.consume(otp.id(), userId, otp.authenticationVersion(), "login")) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен");
        }

        String sessionToken = generateSecureToken();
        String sessionTokenHash = KauthPasswordHasher.sha256(sessionToken);
        KauthSessionRepository.SessionRecord session;
        try {
            session = sessionRepository.create(
                    user.id(), otp.authenticationVersion(), sessionTokenHash, ip, userAgent, deviceInfo);
        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_CREDENTIALS) {
                throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен");
            }
            throw e;
        }

        return LoginResult.success(sessionToken, user, session);
    }

    private void refuse(String login, String ip, String userAgent, Long userId, String reason) {
        loginAttemptRepository.recordAttempt(login, ip, false, reason);
        auditLogService.logSecurityEvent(
                "LOGIN_FAILED", userId, ip, userAgent, Map.of("login", login, "reason", reason));
    }

    private String dummyPasswordHash() {
        String hash = dummyPasswordHash;
        if (hash == null) {
            hash = passwordHasher.hashPassword(generateSecureToken());
            dummyPasswordHash = hash;
        }
        return hash;
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record LoginResult(
            boolean isOtpRequired,
            String otpToken,
            String rawSessionCookie,
            MdUserService.AuthUser user,
            KauthSessionRepository.SessionRecord session) {
        public static LoginResult requires2fa(String otpToken, Long userId) {
            return new LoginResult(true, otpToken, null, null, null);
        }

        public static LoginResult success(
                String rawSessionCookie, MdUserService.AuthUser user, KauthSessionRepository.SessionRecord session) {
            return new LoginResult(false, null, rawSessionCookie, user, session);
        }
    }
}
