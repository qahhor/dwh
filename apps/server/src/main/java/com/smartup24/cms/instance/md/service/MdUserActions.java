package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.hook.EntityActionCall;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import com.smartup24.cms.instance.common.entity.hook.EntityValues;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.pref.MdPref;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The record actions of a user account (ADR-0032, 6.7 and 8): each changes the account's values, and the runtime
 * writes, audits and publishes the change from the revision of {@code If-Match}; the security effects of each — a new
 * authentication generation, closed sessions — are {@link MdUserHooks#afterSave} in the same transaction.
 *
 * <ul>
 *   <li>{@code block} and {@code unblock} — the state; the system administrator {@code admin} is never blocked;
 *   <li>{@code reset_2fa} and {@code enable_2fa} — the second factor at sign-in, off or on;
 *   <li>{@code force_password_change} — a new password at the next sign-in;
 *   <li>{@code anonymize} — in place of a delete (FR-USR-8): the personal data are replaced, the account is blocked
 *       and kept for the audit; never the system administrator.
 * </ul>
 */
@Configuration
public class MdUserActions {

    /** The login of the system administrator, whom nobody blocks or anonymises. */
    static final String ADMIN_LOGIN = "admin";

    @Bean
    EntityActionHandler mdUserBlock() {
        return handler(MdUserEntity.BLOCK, call -> {
            refuseAdmin(call.values(), "error.md.admin_block_forbidden");
            call.values().set(MdUserEntity.STATE, MdPref.STATE_PASSIVE);
        });
    }

    @Bean
    EntityActionHandler mdUserUnblock() {
        return handler(MdUserEntity.UNBLOCK, call -> call.values().set(MdUserEntity.STATE, MdPref.STATE_ACTIVE));
    }

    @Bean
    EntityActionHandler mdUserReset2fa() {
        return handler(MdUserEntity.RESET_2FA, call -> call.values().set(MdUserEntity.TWO_FACTOR, false));
    }

    @Bean
    EntityActionHandler mdUserEnable2fa() {
        return handler(MdUserEntity.ENABLE_2FA, call -> call.values().set(MdUserEntity.TWO_FACTOR, true));
    }

    @Bean
    EntityActionHandler mdUserForcePasswordChange() {
        return handler(MdUserEntity.FORCE_PASSWORD_CHANGE, call -> call.values().set(MdUserEntity.FORCE_CHANGE, true));
    }

    @Bean
    EntityActionHandler mdUserAnonymize() {
        return handler(MdUserEntity.ANONYMIZE, call -> {
            EntityValues values = call.values();
            refuseAdmin(values, "error.md.admin_delete_forbidden");
            long id = Objects.requireNonNull(call.id(), "an action names its record");
            values.set(MdUserEntity.NAME, "Deleted User " + id);
            values.set(MdUserEntity.LOGIN, "deleted_" + id);
            values.set(MdUserEntity.EMAIL, "deleted_" + id + "@anonymized.local");
            values.set(MdUserEntity.PHONE, null);
            values.set(MdUserEntity.MANAGER, null);
            values.set(MdUserEntity.STATE, MdPref.STATE_PASSIVE);
        });
    }

    private static void refuseAdmin(EntityValues values, String messageKey) {
        if (ADMIN_LOGIN.equalsIgnoreCase(values.text(MdUserEntity.LOGIN))) {
            throw ApiException.conflict(ErrorCode.SUPERADMIN_IMMUTABLE, messageKey);
        }
    }

    private static EntityActionHandler handler(String action, Consumer<EntityActionCall> run) {
        return new EntityActionHandler() {
            @Override
            public String entity() {
                return MdUserEntity.CODE;
            }

            @Override
            public String action() {
                return action;
            }

            @Override
            public void run(EntityActionCall call) {
                run.accept(call);
            }
        };
    }
}
