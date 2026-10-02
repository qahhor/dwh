package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdUserIdentity;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of user accounts that authentication and the other modules use, and the creation of an account by the
 * system itself (seeds, provisioning, tests) with an initial password. The API serves the accounts through the user
 * entity ({@link MdUserEntity}): a user created there gets an invitation instead of a password; the password and the
 * access a change takes away live in {@link MdUserSecurityService}.
 */
@Service
public class MdUserService {

    private final MdUserRepository userRepository;
    private final MdRoleRepository roleRepository;
    private final MdCustomFieldService customFieldService;
    private final PasswordHasher passwordHasher;
    private final PasswordValidator passwordValidator;
    private final SearchChangePublisher searchChangePublisher;
    private final AuditLogService auditLogService;
    private final MdScopeService scopeService;

    public MdUserService(
            MdUserRepository userRepository,
            MdRoleRepository roleRepository,
            MdCustomFieldService customFieldService,
            PasswordHasher passwordHasher,
            PasswordValidator passwordValidator,
            SearchChangePublisher searchChangePublisher,
            AuditLogService auditLogService,
            MdScopeService scopeService) {
        this.scopeService = scopeService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.customFieldService = customFieldService;
        this.passwordHasher = passwordHasher;
        this.passwordValidator = passwordValidator;
        this.searchChangePublisher = searchChangePublisher;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public MdUserRepository.UserRecord createUser(
            String name,
            String login,
            String email,
            String phone,
            String rawPassword,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            boolean is2faEnabled,
            boolean forcePasswordChange,
            List<Long> roleIds,
            Long createdBy) {

        scopeService.acquireMutationLock();
        if (userRepository.existsByLogin(login)) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "error.md.user_login_exists");
        }
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "error.md.user_email_exists");
        }
        String normalizedPhone = (phone != null && !phone.isBlank()) ? phone.trim() : null;
        if (normalizedPhone != null && userRepository.existsByPhone(normalizedPhone)) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "error.md.user_phone_exists");
        }

        // FR-USR-2: Password complexity & dictionary check
        if (rawPassword != null && !rawPassword.isBlank()) {
            passwordValidator.validate(rawPassword, login);
        }

        // Validate custom dynamic fields
        Map<String, Object> storedAttributes = customFieldService.checkedAttributes("USER", attributes);

        String passwordHash =
                rawPassword != null && !rawPassword.isBlank() ? passwordHasher.hashPassword(rawPassword) : null;

        var user = userRepository.create(
                new MdUserRepository.UserCreateData(
                        name,
                        login,
                        email,
                        normalizedPhone,
                        passwordHash,
                        MdPref.STATE_ACTIVE,
                        managerId,
                        language,
                        timezone,
                        avatarFileId,
                        storedAttributes,
                        is2faEnabled,
                        forcePasswordChange),
                createdBy);

        if (roleIds != null && !roleIds.isEmpty()) {
            roleRepository.assignRolesToUser(user.id(), roleIds);
        } else {
            // Assign default 'user' role
            roleRepository
                    .findByPcode(MdPref.ROLE_USER)
                    .ifPresent(r -> roleRepository.assignRolesToUser(user.id(), List.of(r.id())));
        }

        scopeService.recalculateFor(user.id());

        // Created outside the entity runtime: no change event names the user, so the search hears of it here.
        searchChangePublisher.changed(MdUserEntity.CODE, user.id());

        auditLogService.logChange(
                "md_users",
                String.valueOf(user.id()),
                "I",
                List.of("name", "login", "email", "phone"),
                null,
                Map.of("id", user.id(), "name", name, "login", login, "email", email));

        return user;
    }

    @Transactional
    public MdUserRepository.UserRecord createUser(
            String name,
            String login,
            String email,
            String phone,
            String rawPassword,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            boolean is2faEnabled,
            List<Long> roleIds,
            Long createdBy) {
        return createUser(
                name,
                login,
                email,
                phone,
                rawPassword,
                managerId,
                language,
                timezone,
                avatarFileId,
                attributes,
                is2faEnabled,
                false,
                roleIds,
                createdBy);
    }

    /**
     * Every call that names a user by id checks this first: a user outside the viewer's data scope answers 404
     * like a missing one (ADR-0013); {@code viewerId} null is a system call.
     */
    @Transactional(readOnly = true)
    public void requireVisible(Long viewerId, Long userId) {
        scopeService.requireUserVisible(viewerId, userId);
    }

    /** The signed-in user as {@code GET /api/v1/auth/me} answers it: without role ids, the rights come apart. */
    @Transactional(readOnly = true)
    public MdUserView getSignedInUserView(Long userId) {
        return MdUserView.from(getUserById(userId));
    }

    @Transactional(readOnly = true)
    public MdUserRepository.UserRecord getUserById(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    /** The user as other modules see it when they act on the user's behalf (plan 10/10, item 1.3). */
    @Transactional(readOnly = true)
    public MdUserIdentity getUserIdentity(Long userId) {
        var user = getUserById(userId);
        return new MdUserIdentity(
                user.id(),
                user.login(),
                user.email(),
                user.state(),
                user.forcePasswordChange(),
                user.authenticationVersion());
    }

    @Transactional(readOnly = true)
    public List<Long> getUserRoleIds(Long userId) {
        return roleRepository.getUserRoleIds(userId);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Long>> getUsersRoleIds(List<Long> userIds) {
        return roleRepository.getUsersRoleIds(userIds);
    }

    public record AuthUser(
            Long id,
            String name,
            String login,
            String email,
            String phone,
            String passwordHash,
            String state,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            boolean is2faEnabled,
            boolean forcePasswordChange,
            long authenticationVersion,
            Instant createdAt,
            Instant modifiedAt,
            long revision) {
        public static AuthUser from(MdUserRepository.UserRecord u) {
            return new AuthUser(
                    u.id(),
                    u.name(),
                    u.login(),
                    u.email(),
                    u.phone(),
                    u.passwordHash(),
                    u.state(),
                    u.managerId(),
                    u.language(),
                    u.timezone(),
                    u.avatarFileId(),
                    u.attributes(),
                    u.is2faEnabled(),
                    u.forcePasswordChange(),
                    u.authenticationVersion(),
                    u.createdAt(),
                    u.modifiedAt(),
                    u.revision());
        }

        public MdUserView toView(List<Long> roleIds) {
            return new MdUserView(
                    id,
                    name,
                    login,
                    email,
                    phone,
                    state,
                    managerId,
                    language,
                    timezone,
                    avatarFileId,
                    attributes,
                    is2faEnabled,
                    forcePasswordChange,
                    roleIds != null ? roleIds : List.of(),
                    createdAt,
                    modifiedAt,
                    revision);
        }

        public MdUserView toView() {
            return toView(List.of());
        }
    }

    @Transactional(readOnly = true)
    public Optional<AuthUser> findAuthUserByLogin(String login) {
        return userRepository.findByLogin(login).map(AuthUser::from);
    }

    @Transactional(readOnly = true)
    public Optional<AuthUser> findAuthUserById(Long userId) {
        return userRepository.findById(userId).map(AuthUser::from);
    }

    @Transactional(readOnly = true)
    public Optional<AuthUser> findAuthUserByEmail(String email) {
        return userRepository.findByEmail(email).map(AuthUser::from);
    }
}
