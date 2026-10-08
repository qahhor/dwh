package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.md.api.MdPref;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntityOperation;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What the user accounts need beyond their declaration ({@link MdUserEntity}; ADR-0032, 6.5 and 8):
 *
 * <ul>
 *   <li>before a save — the login and the e-mail in lower case, each taken by one account only, a phone taken by one
 *       active account only (422 on the field), a time zone that exists, and a home unit in the author's data scope
 *       (ADR-0013);
 *   <li>after a create — the default role {@code user}, the effective scope and rights and the
 *       invitation that lets the new user set a password (ADR-0032, 19, question 10: an invitation, not a password
 *       an administrator types);
 *   <li>after an action — what takes access away also starts a new authentication generation and closes every
 *       session and API token in the same transaction (FR-USR-4): blocking, a reset of the second factor, a forced
 *       password change and anonymisation, which also wipes the password hash, the avatar and the custom values.
 * </ul>
 */
@Component
public class MdUserHooks implements EntityHooks {

    /** The field problem of a login, e-mail or phone another account has. */
    static final String TAKEN = "already_exists";

    private final MdUserRepository users;
    private final MdRoleRepository roles;
    private final MdScopeService scopes;
    private final MdUserSecurityService security;
    private final ObjectProvider<UserInvitations> invitations;

    public MdUserHooks(
            MdUserRepository users,
            MdRoleRepository roles,
            MdScopeService scopes,
            MdUserSecurityService security,
            ObjectProvider<UserInvitations> invitations) {
        this.users = users;
        this.roles = roles;
        this.scopes = scopes;
        this.security = security;
        this.invitations = invitations;
    }

    @Override
    public String entity() {
        return MdUserEntity.CODE;
    }

    @Override
    public void beforeSave(EntitySave save) {
        EntityValues values = save.values();
        boolean create = save.operation() == EntityOperation.CREATE;
        if (create) {
            // Creating a user changes who sees whom: one at a time with every change of the data scope.
            scopes.acquireMutationLock();
            lowerCase(values, MdUserEntity.LOGIN);
            lowerCase(values, MdUserEntity.EMAIL);
            String login = values.text(MdUserEntity.LOGIN);
            if (login != null && users.existsByLogin(login)) {
                save.reject(MdUserEntity.LOGIN, TAKEN, "error.md.user_login_exists", Map.of());
            }
            String email = values.text(MdUserEntity.EMAIL);
            if (email != null && users.existsByEmail(email)) {
                save.reject(MdUserEntity.EMAIL, TAKEN, "error.md.user_email_exists", Map.of());
            }
        }
        String phone = values.text(MdUserEntity.PHONE);
        if (phone != null && (create || save.changed(MdUserEntity.PHONE)) && users.phoneTaken(phone, save.id())) {
            save.reject(MdUserEntity.PHONE, TAKEN, "error.md.user_phone_exists", Map.of());
        }
        String zone = values.text(MdUserEntity.TIMEZONE);
        if (zone != null && (create || save.changed(MdUserEntity.TIMEZONE)) && !knownZone(zone)) {
            save.reject(
                    MdUserEntity.TIMEZONE,
                    EntityValidator.INVALID,
                    "error.md.user_timezone_unknown",
                    Map.of("zone", zone));
        }
        if (!create && save.changed(MdUserEntity.ORG_UNIT)) {
            // A new home unit changes who sees the user: one at a time with every change of the data scope.
            scopes.acquireMutationLock();
        }
        Long unit = values.ref(MdUserEntity.ORG_UNIT);
        if (unit != null
                && (create || save.changed(MdUserEntity.ORG_UNIT))
                && !scopes.unitVisible(save.actor().userId(), unit)) {
            save.reject(MdUserEntity.ORG_UNIT, EntityScopes.OUT_OF_SCOPE, "error.field.out_of_scope", Map.of());
        }
    }

    @Override
    public void afterSave(EntitySave save) {
        long id = Objects.requireNonNull(save.id(), "a saved user has its id");
        switch (save.operation()) {
            case CREATE -> created(id);
            case UPDATE -> {
                if (save.changed(MdUserEntity.ORG_UNIT)) {
                    scopes.recalculateFor(id);
                }
            }
            case ACTION -> acted(id, Objects.requireNonNull(save.action(), "an action names itself"));
        }
    }

    private void created(long id) {
        roles.findByPcode(MdPref.ROLE_USER).ifPresent(role -> roles.assignRolesToUser(id, List.of(role.id())));
        scopes.recalculateFor(id);
        invitations.ifAvailable(invitation -> invitation.invite(id));
    }

    private void acted(long id, String action) {
        switch (action) {
            case MdUserEntity.BLOCK, MdUserEntity.RESET_2FA, MdUserEntity.FORCE_PASSWORD_CHANGE ->
                security.revokeAccess(id);
            case MdUserEntity.ANONYMIZE -> security.anonymizeCredentials(id);
            default -> {
                // Unblocking and requiring the second factor give no access away to revoke.
            }
        }
    }

    private static void lowerCase(EntityValues values, String key) {
        String value = values.text(key);
        if (value != null) {
            String lower = value.strip().toLowerCase(Locale.ROOT);
            if (!lower.equals(value)) values.set(key, lower);
        }
    }

    /** A zone of the time-zone database, or UTC; the pattern of the field already refused offsets and other forms. */
    private static boolean knownZone(String zone) {
        return "UTC".equals(zone) || ZoneId.getAvailableZoneIds().contains(zone);
    }
}
