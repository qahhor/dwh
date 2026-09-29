package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The security actions on a user account: password, state, forced password change, 2FA reset and anonymisation.
 * Every action that takes access away also starts a new authentication generation and closes the sessions and
 * API tokens in the same transaction.
 */
@Service
public class MdUserSecurityService {

    private final MdUserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final PasswordValidator passwordValidator;
    private final UserSessionInvalidator sessionInvalidator;
    private final SearchChangePublisher searchChangePublisher;
    private final AuditLogService auditLogService;

    public MdUserSecurityService(
            MdUserRepository userRepository,
            PasswordHasher passwordHasher,
            PasswordValidator passwordValidator,
            UserSessionInvalidator sessionInvalidator,
            SearchChangePublisher searchChangePublisher,
            AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.passwordValidator = passwordValidator;
        this.sessionInvalidator = sessionInvalidator;
        this.searchChangePublisher = searchChangePublisher;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void changePassword(Long userId, long authenticatedVersion, String oldPassword, String newPassword) {
        var user = getUserById(userId);

        if (!MdPref.STATE_ACTIVE.equals(user.state()) || user.authenticationVersion() != authenticatedVersion) {
            throw ApiException.invalidCredentials();
        }

        if (user.passwordHash() != null && !passwordHasher.verifyPassword(oldPassword, user.passwordHash())) {
            throw ApiException.badRequest(ErrorCode.INVALID_CREDENTIALS, "error.md.current_password_invalid");
        }

        passwordValidator.validate(newPassword, user.login());

        String newHash = passwordHasher.hashPassword(newPassword);
        if (!userRepository.compareAndSetPassword(userId, authenticatedVersion, user.passwordHash(), newHash)) {
            throw ApiException.invalidCredentials();
        }
        userRepository.incrementAuthenticationVersion(userId);
        sessionInvalidator.invalidateAllAccess(userId);

        auditLogService.logSecurityEvent("PASSWORD_CHANGED", userId, null, null, Map.of("login", user.login()));
    }

    @Transactional
    public void setUserState(Long targetUserId, String newState, Long currentUserId) {
        var targetUser = getUserById(targetUserId);

        // Immutable Superadmin Protection: Admin user cannot be blocked (TRD-01 / I-IAM-1)
        if (targetUser.login().equalsIgnoreCase("admin") && MdPref.STATE_PASSIVE.equals(newState)) {
            throw ApiException.conflict(ErrorCode.SUPERADMIN_IMMUTABLE, "error.md.admin_block_forbidden");
        }

        userRepository.setState(targetUserId, newState, currentUserId);

        // I-U1 (FR-USR-4): блокировка атомарно закрывает сессии и отзывает токены —
        // в ТОЙ ЖЕ транзакции, никаких «окон», когда state=P, а сессия жива.
        if (MdPref.STATE_PASSIVE.equals(newState)) {
            userRepository.incrementAuthenticationVersion(targetUserId);
            sessionInvalidator.invalidateAllAccess(targetUserId);
        }

        searchChangePublisher.changed("USER", targetUserId);

        auditLogService.logChange(
                "md_users",
                String.valueOf(targetUserId),
                "U",
                List.of("state"),
                Map.of("state", targetUser.state()),
                Map.of("state", newState));
    }

    @Transactional
    public void setForcePasswordChange(Long targetUserId, boolean force, Long currentUserId) {
        var targetUser = getUserById(targetUserId);
        userRepository.setForcePasswordChange(targetUserId, force, currentUserId);
        if (force) {
            userRepository.incrementAuthenticationVersion(targetUserId);
            sessionInvalidator.invalidateAllAccess(targetUserId);
        }
        searchChangePublisher.changed("USER", targetUserId);
        auditLogService.logChange(
                "md_users",
                String.valueOf(targetUserId),
                "U",
                List.of("force_password_change"),
                Map.of("force_password_change", targetUser.forcePasswordChange()),
                Map.of("force_password_change", force));
    }

    @Transactional
    public void reset2fa(Long targetUserId, Long currentUserId) {
        var targetUser = getUserById(targetUserId);
        userRepository.set2faEnabled(targetUserId, false, currentUserId);
        userRepository.incrementAuthenticationVersion(targetUserId);
        sessionInvalidator.invalidateAllAccess(targetUserId);
        searchChangePublisher.changed("USER", targetUserId);
        auditLogService.logChange(
                "md_users",
                String.valueOf(targetUserId),
                "U",
                List.of("is_2fa_enabled"),
                Map.of("is_2fa_enabled", targetUser.is2faEnabled()),
                Map.of("is_2fa_enabled", false));
    }

    @Transactional
    public void anonymizeUser(Long targetUserId, Long currentUserId) {
        var targetUser = getUserById(targetUserId);

        // I-IAM-1: Системный администратор не может быть удалён или анонимизирован
        if (targetUser.login().equalsIgnoreCase("admin")) {
            throw ApiException.conflict(ErrorCode.SUPERADMIN_IMMUTABLE, "error.md.admin_delete_forbidden");
        }

        // FR-USR-8: Анонимизация ПДн с сохранением реляционной целостности для аудита
        userRepository.anonymizeUser(targetUserId, currentUserId);

        // Закрытие всех сессий и отзыв токенов
        userRepository.incrementAuthenticationVersion(targetUserId);
        sessionInvalidator.invalidateAllAccess(targetUserId);

        searchChangePublisher.changed("USER", targetUserId);

        auditLogService.logChange(
                "md_users",
                String.valueOf(targetUserId),
                "D",
                List.of("state", "name", "email", "phone"),
                Map.of("name", targetUser.name(), "login", targetUser.login()),
                Map.of("name", "Deleted User " + targetUserId, "state", "P"));
    }

    /**
     * Sets a password by a reset link: the effects of a change (a new authentication generation, every session and
     * API token closed) without the old password.
     *
     * @return {@code false} when the user changed in the meantime: password, generation or state
     */
    @Transactional
    public boolean resetPassword(
            Long userId, long expectedAuthVersion, String expectedPasswordHash, String newPasswordHash) {
        if (!userRepository.compareAndSetPassword(userId, expectedAuthVersion, expectedPasswordHash, newPasswordHash)) {
            return false;
        }
        userRepository.incrementAuthenticationVersion(userId);
        sessionInvalidator.invalidateAllAccess(userId);
        return true;
    }

    @Transactional
    public void incrementAuthenticationVersion(Long userId) {
        userRepository.incrementAuthenticationVersion(userId);
    }

    private MdUserRepository.UserRecord getUserById(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }
}
