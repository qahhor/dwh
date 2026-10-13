package com.smartup24.cms.instance.support;

import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifests;
import com.smartup24.cms.instance.config.idempotency.IdempotencyModuleAccess;
import com.smartup24.cms.instance.kauth.security.RequiresPermissionInterceptor;
import com.smartup24.cms.platform.api.PlatformVersion;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Module contracts for standalone tests whose installation has every module switched on. */
public final class TestModules {

    private TestModules() {}

    public static ModuleCatalog catalog() {
        return new ModuleCatalog(
                PlatformVersion.current(),
                ModuleManifests.checked(TestModules.class.getClassLoader(), PlatformVersion.current()));
    }

    public static RequiresPermissionInterceptor permissions() {
        return new RequiresPermissionInterceptor(catalog(), code -> true);
    }

    /** Synthetic filter chains have no mapped module; their tests isolate reservation and transaction behavior. */
    public static IdempotencyModuleAccess replayAccess() {
        return mock(IdempotencyModuleAccess.class);
    }

    /** Explicit module dependencies of MVC slices that exercise authentication with all modules enabled. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Enabled {
        @Bean
        ModuleCatalog moduleCatalog() {
            return catalog();
        }

        @Bean
        InstalledModules installedModules() {
            return code -> true;
        }

        @Bean
        IdempotencyModuleAccess idempotencyModuleAccess() {
            return replayAccess();
        }
    }
}
