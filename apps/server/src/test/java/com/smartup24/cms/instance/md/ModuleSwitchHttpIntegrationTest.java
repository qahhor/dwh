package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** FR-MOD-01: switching tasks through HTTP closes both runtime and custom APIs; the core remains usable. */
class ModuleSwitchHttpIntegrationTest extends EmbeddedPostgresTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ModuleRegistryService modules;

    @Test
    void everyPermissionProtectedHandlerBelongsToAnInstalledModule() {
        var catalog = context.getBean(ModuleCatalog.class);
        var mappings = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        for (var handler : mappings.getHandlerMethods().values()) {
            var permission = handler.getMethodAnnotation(RequiresPermission.class);
            if (permission == null) permission = handler.getBeanType().getAnnotation(RequiresPermission.class);
            if (permission == null) continue;
            assertThat(catalog.holdingForm(permission.form()))
                    .as("%s permission area %s must name an installed module", handler, permission.form())
                    .isPresent();
        }
    }

    @Test
    void disabledTasksHideTheirRuntimeAndAnalyticsButKeepProfileWidgets() throws Exception {
        TestUsers users = TestUsers.of(context);
        var admin = users.withRightsOf("admin", users.unit("module-switch"));
        TestSession session = TestSession.signIn(context, admin.login());
        var switched = session.send(put("/api/v1/modules/tasks/enabled"), Map.of("enabled", false));
        assertThat(switched.getStatus()).as(switched.getContentAsString()).isEqualTo(200);
        try {
            for (String path : List.of(
                    "/api/v1/entities/ms.tasks",
                    "/api/v1/entities/ms.projects",
                    "/api/v1/entities/ms.task_types",
                    "/api/v1/entities/ms.task_statuses",
                    "/api/v1/form-meta/ms.tasks",
                    "/api/v1/query-meta/ms.tasks",
                    "/api/v1/analytics/summary",
                    "/api/v1/analytics/trends",
                    "/api/v1/analytics/projects",
                    "/api/v1/analytics/workload",
                    "/api/v1/reports/tasks/export")) {
                var refused = session.send(get(path));
                assertThat(refused.getStatus())
                        .as(path + " " + refused.getContentAsString())
                        .isEqualTo(404);
            }
            var widgets = session.send(get("/api/v1/report-widgets"));
            assertThat(widgets.getStatus()).as(widgets.getContentAsString()).isEqualTo(200);
        } finally {
            modules.toggleModuleStatus("tasks", true);
        }
        var visible = session.send(get("/api/v1/entities/ms.tasks"));
        assertThat(visible.getStatus()).as(visible.getContentAsString()).isEqualTo(200);
    }
}
