package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdUserDtos.CreateUserDto;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User accounts: creation, profile and role changes, and the reads that authentication and the API use. The
 * security actions on an account (password, state, 2FA, anonymisation) live in {@link MdUserSecurityService}.
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
        customFieldService.validateAttributes("USER", attributes);

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
                        attributes,
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

        searchChangePublisher.changed("USER", user.id());

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

    /** The create request as the API sends it; the answer is the safe view with the roles the user got. */
    @Transactional
    public MdUserView createUser(CreateUserDto body, Long createdBy) {
        var user = createUser(
                body.name(),
                body.login(),
                body.email(),
                body.phone(),
                body.password(),
                body.managerId(),
                body.language(),
                body.timezone(),
                body.avatarFileId(),
                body.attributes(),
                body.is2faEnabled(),
                Boolean.TRUE.equals(body.forcePasswordChange()),
                body.roleIds(),
                createdBy);
        return MdUserView.from(user, roleRepository.getUserRoleIds(user.id()));
    }

    @Transactional(readOnly = true)
    public MdUserView getUserView(Long userId) {
        return MdUserView.from(getUserById(userId), roleRepository.getUserRoleIds(userId));
    }

    @Transactional(readOnly = true)
    public MdUserRepository.UserRecord getUserById(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Long> getUserRoleIds(Long userId) {
        return roleRepository.getUserRoleIds(userId);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Long>> getUsersRoleIds(List<Long> userIds) {
        return roleRepository.getUsersRoleIds(userIds);
    }

    @Transactional
    public long updateUser(
            Long userId,
            String name,
            String phone,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            Boolean is2faEnabled,
            List<Long> roleIds,
            Long modifiedBy,
            long expectedRevision) {

        if (roleIds != null) {
            scopeService.acquireMutationLock();
        }
        var existingUser = getUserById(userId);

        String normalizedPhone = (phone != null && !phone.isBlank()) ? phone.trim() : null;
        if (normalizedPhone != null && !normalizedPhone.equals(existingUser.phone())) {
            if (userRepository.existsByPhone(normalizedPhone)) {
                throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "error.md.user_phone_exists");
            }
        }

        if (attributes != null) {
            customFieldService.validateAttributes("USER", attributes);
        }

        long revision = userRepository.update(
                userId,
                new MdUserRepository.UserUpdateData(
                        name, normalizedPhone, managerId, language, timezone, avatarFileId, attributes, is2faEnabled),
                modifiedBy,
                expectedRevision);

        if (roleIds != null) {
            // I-IAM-1: Нельзя снять роль администратора с системного администратора admin
            if (existingUser.login().equalsIgnoreCase("admin")) {
                roleRepository.findByPcode(MdPref.ROLE_ADMIN).ifPresent(adminRole -> {
                    if (!roleIds.contains(adminRole.id())) {
                        throw ApiException.conflict(
                                ErrorCode.SUPERADMIN_IMMUTABLE, "error.md.admin_role_remove_forbidden");
                    }
                });
            }
            roleRepository.assignRolesToUser(userId, roleIds);
            scopeService.recalculateFor(userId);
        }

        searchChangePublisher.changed("USER", userId);

        auditLogService.logChange(
                "md_users",
                String.valueOf(userId),
                "U",
                List.of("name", "phone", "language", "timezone"),
                Map.of("name", existingUser.name(), "phone", existingUser.phone() != null ? existingUser.phone() : ""),
                Map.of("name", name != null ? name : existingUser.name(), "phone", phone != null ? phone : ""));
        return revision;
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
