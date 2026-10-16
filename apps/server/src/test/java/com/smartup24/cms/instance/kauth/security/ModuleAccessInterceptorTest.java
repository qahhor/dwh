package com.smartup24.cms.instance.kauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.analytics.controller.AnalyticsController;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifests;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.controller.MdReportWidgetController;
import com.smartup24.cms.instance.mf.controller.MfFileController;
import com.smartup24.cms.instance.ms.task.controller.MsProjectController;
import com.smartup24.cms.instance.ms.task.controller.MsTaskCommentController;
import com.smartup24.cms.instance.ms.task.controller.MsTaskController;
import com.smartup24.cms.instance.ms.task.controller.MsTaskFileController;
import com.smartup24.cms.instance.report.controller.ReportController;
import com.smartup24.cms.instance.upl.api.UplOverviewController;
import com.smartup24.cms.instance.upl.api.UplPackageController;
import com.smartup24.cms.instance.upl.api.UplSourceController;
import com.smartup24.cms.instance.upl.api.UplUnitController;
import com.smartup24.cms.platform.api.PlatformVersion;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** FR-MOD-01: a module switch closes custom APIs, including rights published to another module. */
class ModuleAccessInterceptorTest {

    private final Set<String> disabled = new HashSet<>();
    private final RequiresPermissionInterceptor interceptor = interceptor();

    private RequiresPermissionInterceptor interceptor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    ModuleCatalog.class,
                    () -> new ModuleCatalog(
                            PlatformVersion.current(),
                            ModuleManifests.checked(getClass().getClassLoader(), PlatformVersion.current())));
            context.registerBean(InstalledModules.class, () -> code -> !disabled.contains(code));
            context.register(RequiresPermissionInterceptor.class);
            context.refresh();
            return context.getBean(RequiresPermissionInterceptor.class);
        }
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContext.clear();
    }

    @ParameterizedTest
    @MethodSource("optionalHandlers")
    void disabledModuleRejectsItsCustomHandlerEvenForAnAdministrator(Method method) {
        disabled.addAll(Set.of("tasks", "upl"));
        principal(Set.of("*.*"));

        assertThatThrownBy(() -> check(method))
                .as(method.toGenericString())
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @ParameterizedTest
    @MethodSource("optionalHandlers")
    void enabledModuleKeepsItsCustomHandlerAccessible(Method method) {
        principal(Set.of("*.*"));
        assertThat(check(method)).isTrue();
    }

    @Test
    void genericWidgetsRemainAccessibleWithoutBusinessModules() throws Exception {
        disabled.addAll(Set.of("tasks", "notes", "upl"));
        principal(Set.of("md.profile.view"));
        assertThat(check(MdReportWidgetController.class.getMethod("list"))).isTrue();
    }

    @Test
    void coreFilesRemainAccessibleWithoutBusinessModules() {
        disabled.addAll(Set.of("tasks", "notes", "upl"));
        principal(Set.of("*.*"));
        Arrays.stream(MfFileController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RequiresPermission.class))
                .forEach(method ->
                        assertThat(check(method)).as(method.toGenericString()).isTrue());
    }

    private boolean check(Method method) {
        return interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new HandlerMethod(mock(method.getDeclaringClass()), method));
    }

    private static void principal(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                10L, "module-test", "module@example.invalid", 20L, false, permissions, 1L, false, 0, null));
    }

    static Stream<Method> optionalHandlers() {
        return Stream.of(
                        MsTaskController.class,
                        MsProjectController.class,
                        MsTaskCommentController.class,
                        MsTaskFileController.class,
                        AnalyticsController.class,
                        ReportController.class,
                        UplSourceController.class,
                        UplPackageController.class,
                        UplOverviewController.class,
                        UplUnitController.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(RequiresPermission.class));
    }
}
