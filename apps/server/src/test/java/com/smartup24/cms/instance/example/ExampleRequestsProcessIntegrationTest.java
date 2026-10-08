package com.smartup24.cms.instance.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.example.service.ExampleProductsEntity;
import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * The reference requests beyond the entity contract (plan 10/10, item 6.6), the recipes of the cookbook as a running
 * example: only the approver writes the resolution (a field right), a decision stamps its moment (a hook on the
 * transition), a submitted or decided request is not deleted — by id or in bulk — because its state keeps it (the
 * platform's refusal, 422 {@code entity_state_locked}, ADR-0032, 9.2), and an archived product is no longer offered to
 * a new request (a reference, 422 {@code archived}).
 */
class ExampleRequestsProcessIntegrationTest extends EmbeddedPostgresTest {

    private static final String REQUESTS = "/api/v1/entities/" + ExampleRequestsEntity.CODE;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    private TestSession clerk;
    private TestSession approver;
    private long product;

    @BeforeEach
    void signIn() throws Exception {
        modules.toggleModuleStatus("example", true);
        TestUsers users = TestUsers.of(wac);
        long unit = users.unit("requests-" + UUID.randomUUID().toString().substring(0, 8));
        TestUser clerkUser = users.withRights(
                Map.of(
                        ExampleRequestsEntity.CODE, Set.of("view", "create", "update", "delete", "submit", "recall"),
                        ExampleProductsEntity.CODE, Set.of("view", "update", "delete")),
                unit);
        TestUser approverUser = users.withRights(
                Map.of(
                        ExampleRequestsEntity.CODE, Set.of("view", "update", "approve", "reject"),
                        ExampleProductsEntity.CODE, Set.of("view")),
                unit);
        clerk = TestSession.signIn(wac, clerkUser.login());
        approver = TestSession.signIn(wac, approverUser.login());
        product = ExampleProducts.insert(jdbc, clerkUser.id());
    }

    @Test
    @DisplayName("6.6: only the approver writes the resolution; the approval stamps decidedAt; a decided one stays")
    void theApproverDecides() throws Exception {
        Map<String, Object> request = created();
        Object id = request.get("id");
        assertThat(request).containsEntry("status", "draft").doesNotContainKey("decidedAt");

        MockHttpServletResponse byClerk =
                clerk.send(patch(REQUESTS + "/" + id).header("If-Match", "\"1\""), Map.of("resolution", "fine"));
        assertThat(byClerk.getStatus()).as(byClerk.getContentAsString()).isEqualTo(422);
        assertThat(byClerk.getContentAsString()).contains("\"field\":\"resolution\"", "\"code\":\"readonly\"");

        assertThat(take(clerk, id, "submit", 1).getStatus()).isEqualTo(200);
        MockHttpServletResponse resolved =
                approver.send(patch(REQUESTS + "/" + id).header("If-Match", "\"2\""), Map.of("resolution", "Buy it"));
        assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(200);

        MockHttpServletResponse approved = take(approver, id, "approve", 3);
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(approved))
                .containsEntry("status", "approved")
                .containsEntry("resolution", "Buy it")
                .extracting(record -> record.get("decidedAt"))
                .isNotNull();

        MockHttpServletResponse deleted = clerk.send(delete(REQUESTS + "/" + id).header("If-Match", "\"4\""));
        assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(422);
        assertThat(TestSession.object(deleted))
                .containsEntry("code", "entity_state_locked")
                .containsEntry("messageKey", "error.common.entity_state_locked")
                .containsEntry("params", Map.of("state", "approved", "action", "delete"));
    }

    @Test
    @DisplayName("ADR-0032, 9.2: a bulk delete passes a draft and refuses a submitted request record by record")
    void aBulkDeleteRefusesWhatTheStateKeeps() throws Exception {
        Object draft = created().get("id");
        Object submitted = created().get("id");
        assertThat(take(clerk, submitted, "submit", 1).getStatus()).isEqualTo(200);
        MockHttpServletResponse bulk =
                clerk.send(post(REQUESTS + "/bulk"), Map.of("action", "delete", "ids", List.of(draft, submitted)));
        assertThat(bulk.getStatus()).as(bulk.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(bulk)).containsEntry("succeeded", 1).containsEntry("failed", 1);
        assertThat(bulk.getContentAsString()).contains("entity_state_locked");
        assertThat(clerk.send(get(REQUESTS + "/" + submitted)).getStatus()).isEqualTo(200);
        assertThat(clerk.send(get(REQUESTS + "/" + draft)).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("6.6: a draft is deleted; an archived product is refused to a new request")
    void aDraftIsDeletedAndAnArchivedProductRefused() throws Exception {
        Map<String, Object> request = created();
        MockHttpServletResponse deleted =
                clerk.send(delete(REQUESTS + "/" + request.get("id")).header("If-Match", "\"1\""));
        assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(204);

        MockHttpServletResponse archived = clerk.send(
                put("/api/v1/entities/" + ExampleProductsEntity.CODE + "/" + product + "/archived")
                        .header("If-Match", "\"1\""),
                Map.of("archived", true));
        assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse refused =
                clerk.send(post(REQUESTS), Map.of("subject", "Paper", "productId", product, "qty", "2"));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("\"field\":\"productId\"", "\"code\":\"archived\"");
    }

    private Map<String, Object> created() throws Exception {
        MockHttpServletResponse response =
                clerk.send(post(REQUESTS), Map.of("subject", "Paper", "productId", product, "qty", "2.5"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return TestSession.object(response);
    }

    private static MockHttpServletResponse take(TestSession who, Object id, String transition, long revision)
            throws Exception {
        return who.send(
                post(REQUESTS + "/" + id + "/actions/" + transition).header("If-Match", "\"" + revision + "\""));
    }
}
