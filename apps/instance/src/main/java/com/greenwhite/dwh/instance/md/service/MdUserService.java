package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.pagination.CursorUtils;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MdUserService {

    private final MdUserRepository userRepository;
    private final MdRoleRepository roleRepository;
    private final MdPermissionService permissionService;
    private final MdCustomFieldService customFieldService;
    private final PasswordHasher passwordHasher;
    private final PasswordValidator passwordValidator;
    private final UserSessionInvalidator sessionInvalidator;

    public MdUserService(
            MdUserRepository userRepository,
            MdRoleRepository roleRepository,
            MdPermissionService permissionService,
            MdCustomFieldService customFieldService,
            PasswordHasher passwordHasher,
            PasswordValidator passwordValidator,
            UserSessionInvalidator sessionInvalidator) {
        this.sessionInvalidator = sessionInvalidator;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionService = permissionService;
        this.customFieldService = customFieldService;
        this.passwordHasher = passwordHasher;
        this.passwordValidator = passwordValidator;
    }

    @Transactional
    public MdUserRepository.UserRecord createUser(
            String name, String login, String email, String phone, String rawPassword,
            Long managerId, String language, String timezone, UUID avatarFileId,
            Map<String, Object> attributes, boolean is2faEnabled, List<Long> roleIds, Long createdBy) {

        if (userRepository.existsByLogin(login)) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "Пользователь с таким логином уже существует");
        }
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "Пользователь с таким email уже существует");
        }
        if (phone != null && !phone.isBlank() && userRepository.existsByPhone(phone)) {
            throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "Активный пользователь с таким номером телефона уже существует");
        }

        // FR-USR-2: Password complexity & dictionary check
        if (rawPassword != null && !rawPassword.isBlank()) {
            passwordValidator.validate(rawPassword, login);
        }

        // Validate custom dynamic fields
        customFieldService.validateAttributes("USER", attributes);

        String passwordHash = rawPassword != null && !rawPassword.isBlank()
                ? passwordHasher.hashPassword(rawPassword)
                : null;

        var user = userRepository.create(new MdUserRepository.UserCreateData(
                name, login, email, phone, passwordHash, MdPref.STATE_ACTIVE,
                managerId, language, timezone, avatarFileId, attributes, is2faEnabled, false
        ), createdBy);

        if (roleIds != null && !roleIds.isEmpty()) {
            roleRepository.assignRolesToUser(user.id(), roleIds);
        } else {
            // Assign default 'user' role
            roleRepository.findByPcode(MdPref.ROLE_USER).ifPresent(r ->
                    roleRepository.assignRolesToUser(user.id(), List.of(r.id()))
            );
        }

        permissionService.recalculateEffectivePermissions(user.id());

        return user;
    }

    @Transactional(readOnly = true)
    public MdUserRepository.UserRecord getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.USER_NOT_FOUND, "Пользователь не найден"));
    }

    @Transactional(readOnly = true)
    public KeysetPage<MdUserRepository.UserRecord> listUsers(int limit, String cursor, String search, String state) {
        Long afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            String decoded = CursorUtils.decode(cursor);
            if (decoded != null) {
                try {
                    afterId = Long.parseLong(decoded);
                } catch (NumberFormatException ignored) {}
            }
        }

        int fetchLimit = limit + 1;
        List<MdUserRepository.UserRecord> users = userRepository.listUsers(fetchLimit, afterId, search, state);

        boolean hasMore = users.size() > limit;
        List<MdUserRepository.UserRecord> resultItems = hasMore ? users.subList(0, limit) : users;

        String nextCursor = null;
        if (hasMore && !resultItems.isEmpty()) {
            Long lastId = resultItems.get(resultItems.size() - 1).id();
            nextCursor = CursorUtils.encode(String.valueOf(lastId));
        }

        return KeysetPage.of(resultItems, nextCursor, hasMore, resultItems.size());
    }

    @Transactional
    public void updateUser(Long userId, String name, String phone, Long managerId,
                           String language, String timezone, UUID avatarFileId,
                           Map<String, Object> attributes, Boolean is2faEnabled, Long modifiedBy) {

        var existingUser = getUserById(userId);

        if (phone != null && !phone.isBlank() && !phone.equals(existingUser.phone())) {
            if (userRepository.existsByPhone(phone)) {
                throw ApiException.conflict(ErrorCode.CODE_ALREADY_EXISTS, "Активный пользователь с таким номером телефона уже существует");
            }
        }

        if (attributes != null) {
            customFieldService.validateAttributes("USER", attributes);
        }

        userRepository.update(userId, new MdUserRepository.UserUpdateData(
                name, phone, managerId, language, timezone, avatarFileId, attributes, is2faEnabled
        ), modifiedBy);
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        var user = getUserById(userId);

        if (user.passwordHash() != null && !passwordHasher.verifyPassword(oldPassword, user.passwordHash())) {
            throw ApiException.badRequest(ErrorCode.INVALID_CREDENTIALS, "Неверный текущий пароль");
        }

        passwordValidator.validate(newPassword, user.login());

        String newHash = passwordHasher.hashPassword(newPassword);
        userRepository.updatePassword(userId, newHash);
    }


    @Transactional
    public void setUserState(Long targetUserId, String newState, Long currentUserId) {
        var targetUser = getUserById(targetUserId);

        // Immutable Superadmin Protection: Admin user cannot be blocked (TRD-01 / I-IAM-1)
        if (targetUser.login().equalsIgnoreCase("admin") && MdPref.STATE_PASSIVE.equals(newState)) {
            throw ApiException.conflict(ErrorCode.SUPERADMIN_IMMUTABLE, "Системный администратор не может быть заблокирован");
        }

        userRepository.setState(targetUserId, newState, currentUserId);

        // I-U1 (FR-USR-4): блокировка атомарно закрывает сессии и отзывает токены —
        // в ТОЙ ЖЕ транзакции, никаких «окон», когда state=P, а сессия жива.
        if (MdPref.STATE_PASSIVE.equals(newState)) {
            sessionInvalidator.invalidateAllAccess(targetUserId);
        }
    }

    @Transactional
    public void anonymizeUser(Long targetUserId, Long currentUserId) {
        var targetUser = getUserById(targetUserId);

        // I-IAM-1: Системный администратор не может быть удалён или анонимизирован
        if (targetUser.login().equalsIgnoreCase("admin")) {
            throw ApiException.conflict(ErrorCode.SUPERADMIN_IMMUTABLE, "Системный администратор не может быть удалён");
        }

        // FR-USR-8: Анонимизация ПДн с сохранением реляционной целостности для аудита
        userRepository.anonymizeUser(targetUserId, currentUserId);

        // Закрытие всех сессий и отзыв токенов
        sessionInvalidator.invalidateAllAccess(targetUserId);
    }

}
