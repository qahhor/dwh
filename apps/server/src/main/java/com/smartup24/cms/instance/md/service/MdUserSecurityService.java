package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdPref;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The security of a user account: the password and the access that a change takes away. Every change that takes
 * access away starts a new authentication generation and closes the sessions and API tokens in the same transaction
 * (FR-USR-4); the record actions of the user entity (block, reset of the second factor, forced password change,
 * anonymisation; {@link MdUserActions}) reach it through {@link MdUserHooks}.
 */
@Service
public class MdUserSecurityService {

    private final MdUserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final PasswordValidator passwordValidator;
    private final UserSessionInvalidator sessionInvalidator;
    private final AuditLogService auditLogService;

    public MdUserSecurityService(
            MdUserRepository userRepository,
            PasswordHasher passwordHasher,
            PasswordValidator passwordValidator,
            UserSessionInvalidator sessionInvalidator,
            AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.passwordValidator = passwordValidator;
        this.sessionInvalidator = sessionInvalidator;
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
        revokeAccess(userId);

        auditLogService.logSecurityEvent("PASSWORD_CHANGED", userId, null, null, Map.of("login", user.login()));
    }

    /**
     * Takes every access of the user away: a new authentication generation, every session and API token closed, in
     * the caller's transaction (FR-USR-4). Blocking, a reset second factor and a forced password change start here,
     * so no session made before outlives the change.
     */
    @Transactional
    public void revokeAccess(long userId) {
        userRepository.incrementAuthenticationVersion(userId);
        sessionInvalidator.invalidateAllAccess(userId);
    }

    /**
     * The anonymisation of an account beyond its fields (FR-USR-8): the password hash, the avatar and the custom
     * values are wiped, then every access is taken away. The fields themselves are replaced by the action.
     */
    @Transactional
    public void anonymizeCredentials(long userId) {
        userRepository.wipeCredentials(userId);
        revokeAccess(userId);
    }

    /**
     * Sets a password by a reset link or an invitation: the effects of a change (a new authentication generation,
     * every session and API token closed) without the old password.
     *
     * @return {@code false} when the user changed in the meantime: password, generation or state
     */
    @Transactional
    public boolean resetPassword(
            Long userId, long expectedAuthVersion, String expectedPasswordHash, String newPasswordHash) {
        if (!userRepository.compareAndSetPassword(userId, expectedAuthVersion, expectedPasswordHash, newPasswordHash)) {
            return false;
        }
        revokeAccess(userId);
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
