package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.kauth.service.KauthCredentialGuard;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StudioAssistantAccessTest {
    private final InstalledModules modules = mock(InstalledModules.class);
    private final MdPermissionService permissions = mock(MdPermissionService.class);
    private final KauthCredentialGuard credentials = mock(KauthCredentialGuard.class);
    private final StudioAssistantAccess access = new StudioAssistantAccess(modules, permissions, credentials);

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void requiresActiveModuleBothFreshGrantsAndOriginalCredential() {
        var principal =
                new SecurityContext.KauthPrincipal(1L, "test", "", 1L, false, Set.of("*.*"), 1L, false, 0L, null);
        SecurityContext.setPrincipal(principal);
        assertThatThrownBy(access::requireAccess).isInstanceOf(ApiException.class);
        when(modules.active("studio")).thenReturn(true);
        when(credentials.requireCurrent(principal)).thenReturn(principal);
        when(permissions.getEffectivePermissions(1L)).thenReturn(Set.of("*.*"));
        assertThat(access.current()).isSameAs(principal);
        when(permissions.getEffectivePermissions(1L)).thenReturn(Set.of("md.settings.update"));
        assertThatThrownBy(access::current).isInstanceOf(ApiException.class);
        when(permissions.getEffectivePermissions(1L)).thenReturn(Set.of("studio.forms.assist", "md.settings.update"));
        assertThat(access.current()).isSameAs(principal);
        when(credentials.requireCurrent(principal)).thenThrow(ApiException.invalidCredentials());
        assertThatThrownBy(access::current).isInstanceOf(ApiException.class);
    }

    @Test
    void missingCallerPermissionStopsBeforeCredentialOrDatabaseReads() {
        when(modules.active("studio")).thenReturn(true);
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "test", "", 1L, false, Set.of("studio.forms.assist"), 1L, false, 0L, null));
        assertThatThrownBy(access::current).isInstanceOf(ApiException.class);
        verifyNoInteractions(credentials, permissions);
    }
}
