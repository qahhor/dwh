package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.support.entity.KitWorld.MISSING;
import static com.smartup24.cms.instance.support.entity.KitWorld.ifMatch;
import static com.smartup24.cms.instance.support.entity.KitWorld.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Who may do what (ADR-0032, 11.2, "rights" and "scope"; ADR-0013, ADR-0028): without the entity's {@code view} every
 * path refuses; with {@code view} alone every change is 403 and {@code form-meta} offers no action; a record outside the
 * viewer's scope answers exactly as a missing id, 404, on every path by id, and is in none of the viewer's lists, bulk
 * actions or exports.
 */
final class KitAccessChecks {

    /** A by-id request of the entity, sent for one record id as one user. */
    @FunctionalInterface
    private interface ByIdRequest {
        MockHttpServletResponse send(TestUser who, long id) throws Exception;
    }

    private final KitWorld world;

    KitAccessChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> rights() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest(
                "without the view right the entity's endpoints answer " + world.transport.withoutView(),
                this::withoutViewTheEndpointsRefuse));
        tests.add(dynamicTest(
                "without the view right form-meta, query-meta, history, export and bulk refuse",
                this::withoutViewThePlatformRefuses));
        for (Map.Entry<String, ByIdRequest> change : changes().entrySet()) {
            tests.add(dynamicTest("with view alone, " + change.getKey() + " is 403", () -> {
                Created record = world.create(world.owner);
                MockHttpServletResponse refused = change.getValue().send(world.viewer, record.id());
                assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(403);
            }));
        }
        tests.add(dynamicTest("form-meta offers each viewer the actions their rights allow", this::formMetaActions));
        return tests;
    }

    List<DynamicTest> scope() {
        if (!world.scoped()) return List.of();
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, ByIdRequest> path : byIdPaths().entrySet()) {
            tests.add(dynamicTest(
                    "a record outside the scope: " + path.getKey() + " is 404, the same answer as a missing id",
                    () -> outsideIsMissing(path.getValue())));
        }
        tests.add(dynamicTest("a record outside the scope is not in the viewer's list", this::outsideIsNotListed));
        if (world.has(EntityCapability.BULK)) {
            tests.add(dynamicTest(
                    "a bulk action reports a record outside the scope as a missing one and keeps it",
                    this::outsideBulk));
        }
        return tests;
    }

    private void withoutViewTheEndpointsRefuse() throws Exception {
        Created record = world.create(world.owner);
        int expected = world.transport.withoutView();
        TestUser stranger = world.stranger;
        TestSession session = world.session(stranger);
        assertThat(session.send(get(world.transport.collection())).getStatus())
                .as("list")
                .isEqualTo(expected);
        assertThat(world.read(stranger, record.id()).getStatus()).as("read").isEqualTo(expected);
        assertThat(world.post(stranger, world.validValues(stranger)).getStatus())
                .as("create")
                .isEqualTo(expected);
        for (Map.Entry<String, ByIdRequest> change : changes().entrySet()) {
            assertThat(change.getValue().send(stranger, record.id()).getStatus())
                    .as(change.getKey())
                    .isEqualTo(expected);
        }
    }

    private void withoutViewThePlatformRefuses() throws Exception {
        Created record = world.create(world.owner);
        TestSession session = world.session(world.stranger);
        String code = world.entity.code();
        assertThat(session.send(get("/api/v1/form-meta/" + code)).getStatus())
                .as("form-meta")
                .isEqualTo(404);
        if (world.entity.listCode() != null) {
            assertThat(session.send(get("/api/v1/query-meta/" + world.entity.listCode()))
                            .getStatus())
                    .as("query-meta")
                    .isEqualTo(404);
        }
        if (world.has(EntityCapability.HISTORY)) {
            assertThat(world.history(world.stranger, record.id()).getStatus())
                    .as("history")
                    .isIn(403, 404);
        }
        if (world.has(EntityCapability.EXPORT)) {
            assertThat(session.send(post("/api/v1/exports"), Map.of("list", world.entity.listCode(), "lang", "en"))
                            .getStatus())
                    .as("export")
                    .isIn(403, 404);
        }
        if (world.has(EntityCapability.BULK)) {
            MockHttpServletResponse bulk = session.send(
                    post("/api/v1/entities/" + code + "/bulk"),
                    Map.of("action", EntityDefinition.DELETE, "ids", List.of(record.id())));
            assertThat(bulk.getStatus()).as("bulk").isIn(403, 404);
        }
        assertThat(world.read(world.owner, record.id()).getStatus()).isEqualTo(200);
    }

    private void formMetaActions() throws Exception {
        for (TestUser user : List.of(world.owner, world.viewer)) {
            Set<String> held = user == world.owner ? world.entityRights().get(world.entity.form()) : Set.of("view");
            Set<String> expected = new TreeSet<>();
            world.entity.actions().stream()
                    .filter(action -> held.contains(action.permission()))
                    .forEach(action -> expected.add(action.code()));
            MockHttpServletResponse meta = world.session(user).send(get("/api/v1/form-meta/" + world.entity.code()));
            assertThat(meta.getStatus()).as(meta.getContentAsString()).isEqualTo(200);
            assertThat(new TreeSet<>((List<?>) TestSession.object(meta).get("actions")))
                    .as("actions offered to %s", user == world.owner ? "a holder of every right" : "a viewer")
                    .isEqualTo(expected);
            if (user == world.owner && world.transport.strictBody()) {
                // The runtime answers each record with the actions its viewer may take (ADR-0032, 6.2).
                Created record = world.create(world.owner);
                assertThat(new TreeSet<>(
                                (List<?>) world.readOk(world.owner, record.id()).get("actions")))
                        .as("actions of a record read by its owner")
                        .isEqualTo(expected);
            }
        }
    }

    private void outsideIsMissing(ByIdRequest path) throws Exception {
        Created record = world.create(world.owner);
        long revision = world.revision(world.owner, record.id());
        MockHttpServletResponse foreign = path.send(world.outsider, record.id());
        MockHttpServletResponse missing = path.send(world.outsider, MISSING);
        assertThat(foreign.getStatus()).as(foreign.getContentAsString()).isEqualTo(404);
        assertThat(problem(foreign)).isEqualTo(problem(missing));
        assertThat(world.revision(world.owner, record.id()))
                .as("the owner's record is untouched")
                .isEqualTo(revision);
    }

    private void outsideIsNotListed() throws Exception {
        Created foreign = world.create(world.owner);
        Created own = world.create(world.outsider);
        Set<Long> ids = world.ids(world.outsider, null);
        assertThat(ids).contains(own.id()).doesNotContain(foreign.id());
        if (world.has(EntityCapability.ARCHIVE)) {
            assertThat(world.ids(world.outsider, KitWorld.ARCHIVED_ONLY)).doesNotContain(foreign.id());
        }
    }

    private void outsideBulk() throws Exception {
        // The bulk actions the entity declares: delete, archive, and the change of its fields (ADR-0032, 6.1).
        List<String> actions = new ArrayList<>();
        if (world.declares(EntityDefinition.DELETE)) actions.add(EntityDefinition.DELETE);
        if (world.has(EntityCapability.ARCHIVE)) actions.add(EntityDefinition.ARCHIVE);
        if (world.declares("update")) actions.add("update");
        for (String action : actions) {
            Created record = world.create(world.owner);
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("action", action);
            request.put("ids", List.of(record.id(), MISSING));
            if ("update".equals(action)) request.put("params", world.updateValues());
            MockHttpServletResponse bulk = world.session(world.outsider)
                    .send(post("/api/v1/entities/" + world.entity.code() + "/bulk"), request);
            assertThat(bulk.getStatus()).as(bulk.getContentAsString()).isEqualTo(200);
            Map<String, Object> result = TestSession.object(bulk);
            assertThat(result).as(action).containsEntry("succeeded", 0).containsEntry("failed", 2);
            List<?> items = (List<?>) result.get("results");
            assertThat(items).hasSize(2);
            Map<?, ?> foreign = (Map<?, ?>) items.get(0);
            Map<?, ?> missing = (Map<?, ?>) items.get(1);
            assertThat(foreign.get("code") + " " + foreign.get("messageKey"))
                    .as(action)
                    .isEqualTo(missing.get("code") + " " + missing.get("messageKey"));
            assertThat(world.readOk(world.owner, record.id()))
                    .as("the owner's record after a bulk %s", action)
                    .containsEntry("revision", (int) record.revision());
        }
    }

    /** The changes of a record by id, with the transport's endpoint for each declared one. */
    private Map<String, ByIdRequest> changes() {
        Map<String, ByIdRequest> changes = new LinkedHashMap<>();
        if (world.declares("update")) {
            changes.put("update", (who, id) -> world.update(who, id, world.updateValues(), ifMatch(1)));
        }
        if (world.has(EntityCapability.ARCHIVE)) {
            changes.put("archive", (who, id) -> world.archive(who, id, true, ifMatch(1)));
        }
        for (String action : world.ownActions()) {
            changes.put("the action " + action, (who, id) -> world.action(who, action, id));
        }
        if (world.declares(EntityDefinition.DELETE)) {
            changes.put("delete", (who, id) -> world.delete(who, id, null));
        }
        return changes;
    }

    /** Every path by id: the read, the changes and the history. */
    private Map<String, ByIdRequest> byIdPaths() {
        Map<String, ByIdRequest> paths = new LinkedHashMap<>();
        paths.put("read", world::read);
        paths.putAll(changes());
        if (world.has(EntityCapability.HISTORY)) {
            paths.put("history", world::history);
        }
        return paths;
    }
}
