package com.smartup24.cms.instance.ms.notify.sse;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.kauth.service.KauthCredentialGuard;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import org.springframework.stereotype.Component;

/** Rechecks the original credential and current rights; cluster notices are never the authority for a live stream. */
@Component
public class MsSseAccess {
    private final KauthCredentialGuard credentials;
    private final MdPermissionService permissions;
    private final MdUserService users;

    public MsSseAccess(KauthCredentialGuard credentials, MdPermissionService permissions, MdUserService users) {
        this.credentials = credentials;
        this.permissions = permissions;
        this.users = users;
    }

    public void requireCurrent(KauthPrincipal principal) {
        credentials.requireCurrent(principal);
        if (users.getUserIdentity(principal.userId()).forcePasswordChange()) {
            throw ApiException.forbidden(
                    ErrorCode.MUST_CHANGE_PASSWORD, "error.auth.temporary_password_change_required");
        }
        var current = permissions.getEffectivePermissions(principal.userId());
        if (!current.contains("*.*") && !current.contains(MsNotifyPref.FORM_INBOX + ".view")) {
            throw ApiException.permissionDenied(MsNotifyPref.FORM_INBOX, "view");
        }
    }
}
