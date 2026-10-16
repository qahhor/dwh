package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Executed declaration-access cases: shared definitions have no business-record org-unit scope (ADR-0035). */
final class ScopeFormStudioCases {
    private static final String CODE = "example.products";
    private static final String BASE = "/api/v1/form-studio/drafts/";
    private static final Map<String, Object> DECLARATION = Map.of(
            "formatVersion",
            1,
            "kind",
            "form-layout",
            "entityCode",
            CODE,
            "sections",
            List.of(Map.of(
                    "id",
                    "main",
                    "fields",
                    List.of(
                            Map.of("fieldId", "code"),
                            Map.of("fieldId", "name"),
                            Map.of("fieldId", "unit"),
                            Map.of("fieldId", "price")))));

    record Case(String handler, HttpMethod method, String suffix, int allowed) {}

    static final List<Case> CASES = List.of(
            new Case("MdFormStudioController#get", HttpMethod.GET, "", 200),
            new Case("MdFormStudioController#create", HttpMethod.POST, "", 201),
            new Case("MdFormStudioController#update", HttpMethod.PUT, "", 200),
            new Case("MdFormStudioController#prepare", HttpMethod.POST, "/versions", 201),
            new Case("MdFormStudioController#versions", HttpMethod.GET, "/versions", 200),
            new Case("MdFormStudioController#version", HttpMethod.GET, "/versions/{version}", 200),
            new Case("MdFormStudioController#verify", HttpMethod.POST, "/versions/{version}/verify", 200));

    private final ModuleRegistryService modules;
    private final TransactionTemplate transactions;
    private final TestSession author;
    private final TestSession withoutAuthorPermission;
    private final TestSession withoutEntityPermission;

    ScopeFormStudioCases(WebApplicationContext context) throws Exception {
        modules = context.getBean(ModuleRegistryService.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        TestUsers users = TestUsers.of(context);
        long unit = users.unit("studio-access");
        author = TestSession.signIn(
                context,
                users.withRights(Map.of("md.settings", List.of("update"), CODE, List.of("view")), unit)
                        .login());
        withoutAuthorPermission = TestSession.signIn(
                context, users.withRights(Map.of(CODE, List.of("view")), unit).login());
        withoutEntityPermission = TestSession.signIn(
                context,
                users.withRights(Map.of("md.settings", List.of("update")), unit).login());
    }

    void verify(Case c) {
        transactions.executeWithoutResult(transaction -> {
            boolean enabled = modules.isModuleActive("example");
            try {
                modules.toggleModuleStatus("example", true);
                exercise(c);
            } catch (Exception failure) {
                throw new AssertionError("Declaration access case failed: " + c.handler(), failure);
            } finally {
                try {
                    modules.toggleModuleStatus("example", enabled);
                } finally {
                    transaction.setRollbackOnly();
                }
            }
        });
    }

    private void exercise(Case c) throws Exception {
        boolean create = c.handler().equals("MdFormStudioController#create");
        Object body = DECLARATION;
        if (!create) {
            assertStatus(author.send(request(HttpMethod.POST, BASE + CODE), DECLARATION, null), 201, "seed draft");
            MockHttpServletResponse prepared = author.send(
                    request(HttpMethod.POST, BASE + CODE + "/versions").header("If-Match", "1"), Map.of(), null);
            assertStatus(prepared, 201, "seed version");
            if (c.handler().equals("MdFormStudioController#verify")) body = TestSession.object(prepared);
        }
        Map<String, Object> before = TestSession.object(author.send(request(HttpMethod.GET, BASE + CODE)));
        assertStatus(send(c, author, "ms.notes", 1, body), 404, "unsupported entity code");
        assertStatus(send(c, withoutAuthorPermission, CODE, 1, body), 403, "missing Studio permission");
        assertStatus(send(c, withoutEntityPermission, CODE, 1, body), 404, "missing form view permission");
        modules.toggleModuleStatus("example", false);
        assertStatus(send(c, author, CODE, 1, body), 404, "module disabled after preparing the version");
        modules.toggleModuleStatus("example", true);
        if (c.suffix().contains("{version}")) {
            assertStatus(send(c, author, CODE, 2, body), 404, "unknown immutable version");
        }
        assertThat(TestSession.object(author.send(request(HttpMethod.GET, BASE + CODE))))
                .as("refused access must not mutate the shared draft")
                .isEqualTo(before);
        MockHttpServletResponse allowed = send(c, author, CODE, 1, body);
        assertStatus(allowed, c.allowed(), "authorized author");
        if (c.suffix().isEmpty()) {
            assertThat(allowed.getHeader("ETag")).isEqualTo(c.method().equals(HttpMethod.PUT) ? "\"2\"" : "\"1\"");
        }
    }

    private static MockHttpServletResponse send(Case c, TestSession session, String code, long version, Object body)
            throws Exception {
        String suffix = c.suffix().replace("{version}", Long.toString(version));
        var request = request(c.method(), BASE + code + suffix).header("If-Match", "1");
        return c.method().equals(HttpMethod.GET) ? session.send(request) : session.send(request, body, null);
    }

    private static void assertStatus(MockHttpServletResponse response, int status, String scenario) throws Exception {
        assertThat(response.getStatus())
                .as("%s: %s", scenario, response.getContentAsString())
                .isEqualTo(status);
    }
}
