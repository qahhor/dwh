package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import com.smartup24.cms.instance.kauth.service.KauthCredentialGuard;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Long-running external work must not extend an obsolete session or grant (ADR-0039). */
@Component
public class StudioAssistantAccess {
    private final InstalledModules modules;
    private final MdPermissionService permissions;
    private final KauthCredentialGuard credentials;

    public StudioAssistantAccess(
            InstalledModules modules, MdPermissionService permissions, KauthCredentialGuard credentials) {
        this.modules = modules;
        this.permissions = permissions;
        this.credentials = credentials;
    }

    public void requireAccess() {
        if (!modules.active("studio")) throw new ApiException(ErrorCode.NOT_FOUND);
        for (String pair : Set.of("studio.forms.assist", "md.settings.update")) {
            int at = pair.lastIndexOf('.');
            if (!SecurityContext.hasPermission(pair.substring(0, at), pair.substring(at + 1)))
                throw ApiException.permissionDenied(pair.substring(0, at), pair.substring(at + 1));
        }
    }

    public KauthPrincipal current() {
        requireAccess();
        var principal = credentials.requireCurrent(SecurityContext.getPrincipal());
        Set<String> current = permissions.getEffectivePermissions(principal.userId());
        if (!current.contains("*.*") && !current.containsAll(Set.of("studio.forms.assist", "md.settings.update")))
            throw ApiException.permissionDenied("studio.forms", "assist");
        return principal;
    }
}
