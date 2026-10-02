package com.smartup24.cms.instance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.smartup24.cms.instance.common.security.ScopeByIdCases.Case;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * ADR-0013, "404, not 403": every handler that reads, changes or deletes a record by id answers 404 to a caller
 * whose data scope excludes the record, and does its work for a caller whose scope includes it.
 *
 * <p>The caller has every permission of the instance administrator and the rule UNITS on one org unit, so a 404
 * can only come from the data scope. The handlers are found in the running application: a new handler with a path
 * variable fails {@link #everyByIdHandlerIsCoveredOrAllowlisted} until it is in the matrix or in the allowlist
 * with a reason.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopeByIdMatrixIntegrationTest extends EmbeddedPostgresTest {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdUserService users;

    @Autowired
    private MdScopeService scopes;

    @Autowired
    private MdScopeRepository scopeRepository;

    @Autowired
    private MdRoleRepository roles;

    @Autowired
    private MfFileService files;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private ScopeFixture fixture;
    private TestSession viewer;

    @BeforeAll
    void setUp() throws Exception {
        fixture = new ScopeFixture(jdbc, users, scopes, scopeRepository, roles, files);
        viewer = TestSession.signIn(wac, fixture.viewerLogin);
    }

    @Test
    @DisplayName("ADR-0013: every handler with a path variable is in the scope matrix or allowlisted with a reason")
    void everyByIdHandlerIsCoveredOrAllowlisted() {
        Set<String> covered = ScopeByIdCases.CASES.stream().map(Case::handler).collect(Collectors.toSet());
        Set<String> uncovered = new TreeSet<>();
        for (String handler : byIdHandlers().keySet()) {
            if (!covered.contains(handler) && !ScopeByIdCases.ALLOWLIST.containsKey(handler)) {
                uncovered.add(handler);
            }
        }
        assertThat(uncovered)
                .as("by-id handlers without a data scope case (ScopeByIdCases.CASES) or an allowlist reason")
                .isEmpty();
        assertThat(ScopeByIdCases.ALLOWLIST.values())
                .allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @TestFactory
    @DisplayName("ADR-0013: a record outside the caller's scope answers 404; inside it the handler does its work")
    Stream<DynamicTest> outsideTheScopeIsNotFound() {
        Map<String, RequestMappingInfo> handlers = byIdHandlers();
        return ScopeByIdCases.CASES.stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> {
                    RequestMappingInfo info = handlers.get(c.handler());
                    // A case whose handler is gone is stale: remove it from ScopeByIdCases (no skipped tests).
                    assertThat(info)
                            .as("handler %s of a matrix case", c.handler())
                            .isNotNull();
                    Object outside = fixture.create(c.kind(), false);
                    MockHttpServletResponse denied = send(c, info, outside);
                    assertThat(denied.getStatus())
                            .as("%s for a record outside the scope: %s", c.name(), denied.getContentAsString())
                            .isEqualTo(404);
                    Object inside = fixture.create(c.kind(), true);
                    MockHttpServletResponse allowed = send(c, info, inside);
                    assertThat(allowed.getStatus())
                            .as("%s for a record inside the scope: %s", c.name(), allowed.getContentAsString())
                            .isIn(c.inScope());
                }));
    }

    /** The application's handlers with a path variable, by {@code Controller#method}. */
    private Map<String, RequestMappingInfo> byIdHandlers() {
        Map<String, RequestMappingInfo> found = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mappings.getHandlerMethods().entrySet()) {
            HandlerMethod method = entry.getValue();
            boolean ours = method.getBeanType().getName().startsWith("com.smartup24.");
            boolean byId = entry.getKey().getPatternValues().stream().anyMatch(p -> p.contains("{"));
            if (ours && byId) {
                found.put(
                        method.getBeanType().getSimpleName() + "#"
                                + method.getMethod().getName(),
                        entry.getKey());
            }
        }
        return found;
    }

    private MockHttpServletResponse send(Case c, RequestMappingInfo info, Object recordId) throws Exception {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        assertThat(methods).as("one HTTP method for %s", c.handler()).hasSize(1);
        String pattern = new TreeSet<>(info.getPatternValues()).first();
        String path = expand(pattern, c.vars().apply(fixture, recordId), recordId);
        var request = request(HttpMethod.valueOf(methods.iterator().next().name()), path);
        String ifMatch = fixture.ifMatch(c.kind(), recordId);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        Object body = c.body().apply(fixture, recordId);
        return body == null ? viewer.send(request) : viewer.send(request, body);
    }

    /** Every path variable takes the record's id unless the case names another value for it. */
    private static String expand(String pattern, Map<String, Object> vars, Object recordId) {
        Matcher variable = VARIABLE.matcher(pattern);
        StringBuilder path = new StringBuilder();
        while (variable.find()) {
            Object value = vars.getOrDefault(variable.group(1), recordId);
            variable.appendReplacement(path, Matcher.quoteReplacement(String.valueOf(value)));
        }
        variable.appendTail(path);
        return path.toString();
    }
}
