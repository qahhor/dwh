package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kauth.repository.KauthLoginAttemptRepository;
import com.greenwhite.dwh.instance.kauth.repository.KauthOtpCodeRepository;
import com.greenwhite.dwh.instance.kauth.repository.KauthPasswordResetRepository;
import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class KauthAuthService {

    private static final int MAX_FAILED_ATTEMPTS_PER_IP = 10;
    private static final int MAX_FAILED_ATTEMPTS_PER_USER = 5;

    private final MdUserRepository userRepository;
    private final KauthSessionRepository sessionRepository;
    private final KauthLoginAttemptRepository loginAttemptRepository;
    private final KauthOtpCodeRepository otpCodeRepository;
    private final KauthPasswordResetRepository passwordResetRepository;
    private final KauthPasswordHasher passwordHasher;
    private final com.greenwhite.dwh.instance.md.service.PasswordValidator passwordValidator;
    private final SecureRandom secureRandom = new SecureRandom();

    public KauthAuthService(
            MdUserRepository userRepository,
            KauthSessionRepository sessionRepository,
            KauthLoginAttemptRepository loginAttemptRepository,
            KauthOtpCodeRepository otpCodeRepository,
            KauthPasswordResetRepository passwordResetRepository,
            KauthPasswordHasher passwordHasher,
            com.greenwhite.dwh.instance.md.service.PasswordValidator passwordValidator) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.loginAttemptRepository = loginAttemptRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.passwordHasher = passwordHasher;
        this.passwordValidator = passwordValidator;
    }


    @Transactional
    public LoginResult login(String login, String password, String ip, String userAgent, String deviceInfo) {
        Instant tenMinutesAgo = Instant.now().minusSeconds(600);

        int failedIp = loginAttemptRepository.countFailedAttemptsForIpSince(ip, tenMinutesAgo);
        if (failedIp >= MAX_FAILED_ATTEMPTS_PER_IP) {
            loginAttemptRepository.recordAttempt(login, ip, false, "IP_RATE_LIMITED");
            throw ApiException.locked(ErrorCode.RATE_LIMITED, "Слишком много неудачных попыток входа с вашего IP");
        }

        int failedUser = loginAttemptRepository.countFailedAttemptsForLoginSince(login, tenMinutesAgo);
        if (failedUser >= MAX_FAILED_ATTEMPTS_PER_USER) {
            loginAttemptRepository.recordAttempt(login, ip, false, "USER_LOCKED");
            throw ApiException.locked(ErrorCode.LOGIN_LOCKED, "Учётная запись временно заблокирована из-за частых ошибок ввода пароля");
        }

        var userOpt = userRepository.findByLogin(login);
        if (userOpt.isEmpty()) {
            loginAttemptRepository.recordAttempt(login, ip, false, "USER_NOT_FOUND");
            throw ApiException.invalidCredentials();
        }

        var user = userOpt.get();
        if (MdPref.STATE_PASSIVE.equals(user.state())) {
            loginAttemptRepository.recordAttempt(login, ip, false, "USER_BLOCKED");
            throw ApiException.conflict(ErrorCode.USER_BLOCKED, "Учётная запись заблокирована");
        }

        if (!passwordHasher.verifyPassword(password, user.passwordHash())) {
            loginAttemptRepository.recordAttempt(login, ip, false, "INVALID_PASSWORD");
            throw ApiException.invalidCredentials();
        }

        loginAttemptRepository.recordAttempt(login, ip, true, null);

        // Check 2FA
        if (user.is2faEnabled()) {
            String otpToken = generateSecureToken();
            String otpCode = String.format("%06d", secureRandom.nextInt(1000000));
            String codeHash = KauthPasswordHasher.sha256(otpCode);

            otpCodeRepository.create(user.id(), "telegram", codeHash, Instant.now().plusSeconds(300));
            return LoginResult.requires2fa(otpToken, user.id());
        }

        // Issue Session
        String sessionToken = generateSecureToken();
        String sessionTokenHash = KauthPasswordHasher.sha256(sessionToken);
        var session = sessionRepository.create(
                user.id(), sessionTokenHash, ip, userAgent, deviceInfo
        );

        return LoginResult.success(sessionToken, user, session);
    }

    @Transactional
    public LoginResult verifyOtp(String otpToken, String code, String ip, String userAgent, String deviceInfo) {
        Long userId = extractUserIdFromOtpToken(otpToken);

        var otpOpt = otpCodeRepository.findLatestActiveByUserId(userId);
        if (otpOpt.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен");
        }

        var otp = otpOpt.get();
        if (otp.expiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest(ErrorCode.OTP_EXPIRED, "Срок действия OTP-кода истёк");
        }

        String inputHash = KauthPasswordHasher.sha256(code);
        if (!inputHash.equals(otp.codeHash())) {
            otpCodeRepository.decrementAttempts(otp.id());
            if (otp.attemptsLeft() <= 1) {
                throw ApiException.locked(ErrorCode.OTP_ATTEMPTS_EXCEEDED, "Превышено количество попыток ввода OTP");
            }
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Неверный код подтверждения");
        }

        otpCodeRepository.markAsUsed(otp.id());

        var user = userRepository.findById(userId)
                .orElseThrow(ApiException::invalidCredentials);

        String sessionToken = generateSecureToken();
        String sessionTokenHash = KauthPasswordHasher.sha256(sessionToken);
        var session = sessionRepository.create(
                user.id(), sessionTokenHash, ip, userAgent, deviceInfo
        );

        return LoginResult.success(sessionToken, user, session);
    }

    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String code = String.format("%06d", secureRandom.nextInt(1000000));
            String codeHash = KauthPasswordHasher.sha256(code);
            passwordResetRepository.create(user.id(), codeHash, Instant.now().plusSeconds(900));
        });
    }

    @Transactional
    public void confirmPasswordReset(String code, String newPassword) {
        String codeHash = KauthPasswordHasher.sha256(code);
        var reset = passwordResetRepository.findActiveByCodeHash(codeHash)
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.RESET_CODE_INVALID, "Неверный или просроченный код сброса пароля"));

        var user = userRepository.findById(reset.userId())
                .orElseThrow(() -> ApiException.notFound(ErrorCode.USER_NOT_FOUND, "Пользователь не найден"));

        passwordValidator.validate(newPassword, user.login());

        passwordResetRepository.markAsUsed(reset.id());

        String newHash = passwordHasher.hashPassword(newPassword);
        userRepository.updatePassword(reset.userId(), newHash);
        sessionRepository.closeAllUserSessions(reset.userId());
    }


    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Long extractUserIdFromOtpToken(String otpToken) {
        if (otpToken == null || otpToken.isBlank()) {
            throw ApiException.badRequest(ErrorCode.OTP_INVALID, "Некорректный OTP токен");
        }
        return 1L;
    }

    public record LoginResult(
            boolean isOtpRequired,
            String otpToken,
            String rawSessionCookie,
            MdUserRepository.UserRecord user,
            KauthSessionRepository.SessionRecord session
    ) {
        public static LoginResult requires2fa(String otpToken, Long userId) {
            return new LoginResult(true, otpToken, null, null, null);
        }

        public static LoginResult success(String rawSessionCookie, MdUserRepository.UserRecord user, KauthSessionRepository.SessionRecord session) {
            return new LoginResult(false, null, rawSessionCookie, user, session);
        }
    }
}
