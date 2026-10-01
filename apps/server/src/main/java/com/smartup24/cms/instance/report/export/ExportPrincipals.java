package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import org.springframework.stereotype.Component;

/**
 * Runs background work as a person (ADR-0018): an export reads the list with
 * the rights its owner has when the job runs, not when it was asked for, so a
 * right taken away meanwhile is respected. A blocked person's work does not run.
 */
@Component
public class ExportPrincipals {

    private final MdUserService users;
    private final MdPermissionService permissions;

    public ExportPrincipals(MdUserService users, MdPermissionService permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    public void runAs(long userId, Runnable work) {
        var previous = SecurityContext.getPrincipal();
        var user = users.getUserIdentity(userId);
        if (!"A".equals(user.state())) {
            throw ApiException.permissionDenied("md.profile", "view");
        }
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                user.id(),
                user.login(),
                user.email(),
                null,
                false,
                permissions.getEffectivePermissions(userId),
                permissions.getPermissionVersion(userId),
                user.forcePasswordChange(),
                user.authenticationVersion(),
                null));
        try {
            work.run();
        } finally {
            if (previous == null) SecurityContext.clear();
            else SecurityContext.setPrincipal(previous);
        }
    }
}
