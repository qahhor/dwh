package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.support.entity.KitWorld.fieldErrors;
import static com.smartup24.cms.instance.support.entity.KitWorld.ifMatch;
import static com.smartup24.cms.instance.support.entity.KitWorld.number;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.collection.EntityCollection;
import com.smartup24.cms.instance.common.entity.workflow.EntityState;
import com.smartup24.cms.instance.common.entity.workflow.EntityTransition;
import com.smartup24.cms.instance.common.entity.workflow.EntityWorkflow;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The process of a document (ADR-0032, 9.2 and 11.2, "collections and process"): a new record starts in the initial
 * state; each transition from a state it does not leave is 422 {@code entity_transition_not_allowed}, without its right
 * 403, and from a state it leaves moves the record, raises its revision and reaches the history; a field or collection a
 * state locks is 422 {@code readonly}; the record's actions follow its state.
 */
final class KitProcessChecks {

    private final KitWorld world;
    private final EntityWorkflow workflow;
    private final KitCollectionChecks collections;
    private final Map<String, TestUser> without = new HashMap<>();

    KitProcessChecks(KitWorld world, KitCollectionChecks collections) {
        this.world = world;
        this.workflow = Objects.requireNonNull(world.model.workflow(), "no process");
        this.collections = collections;
    }

    static List<DynamicTest> of(KitWorld world, KitCollectionChecks collections) {
        return world.model.workflow() == null ? List.of() : new KitProcessChecks(world, collections).process();
    }

    List<DynamicTest> process() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("form-meta gives the process", this::formMetaGivesTheProcess));
        tests.add(dynamicTest("a new record starts in " + workflow.initial().code(), this::startsInTheInitialState));
        tests.add(dynamicTest("the record's actions follow its state", this::actionsFollowTheState));
        for (EntityTransition transition : workflow.transitions()) {
            String code = transition.code();
            stateWhere(transition, false)
                    .ifPresent(state -> tests.add(dynamicTest(
                            code + " from " + state + " is 422 entity_transition_not_allowed",
                            () -> notAllowed(transition, state))));
            Optional<String> from = stateWhere(transition, true);
            if (from.isEmpty()) continue;
            if (!world.entityRights().get(world.entity.form()).equals(rightsWithout(transition.permission()))) {
                tests.add(dynamicTest(
                        code + " without the right " + transition.permission() + " is 403",
                        () -> forbidden(transition, from.get())));
            }
            tests.add(dynamicTest(
                    code + " from " + from.get() + " moves the record to " + transition.to(),
                    () -> taken(transition, from.get())));
        }
        for (EntityState state : workflow.states()) {
            if (state.locks().isEmpty() && !state.terminal()) continue;
            if (path(state.code()).isEmpty()) continue;
            tests.add(dynamicTest("in " + state.code() + " a locked field is 422 readonly", () -> locked(state)));
        }
        return tests;
    }

    private void formMetaGivesTheProcess() throws Exception {
        Map<String, Object> meta =
                TestSession.object(world.session(world.owner).send(get("/api/v1/form-meta/" + world.entity.code())));
        Map<?, ?> process = (Map<?, ?>) meta.get("workflow");
        assertThat(process).as("form-meta workflow").isNotNull();
        assertThat(process.get("field")).isEqualTo(workflow.field());
        assertThat(((List<?>) process.get("transitions")).size())
                .isEqualTo(workflow.transitions().size());
    }

    private void startsInTheInitialState() throws Exception {
        Created record = world.create(world.owner);
        assertThat(world.readOk(world.owner, record.id()))
                .containsEntry(workflow.field(), workflow.initial().code());
    }

    private void actionsFollowTheState() throws Exception {
        Created record = world.create(world.owner);
        Set<String> actions = new TreeSet<>(
                (List<String>) cast(world.readOk(world.owner, record.id()).get("actions")));
        for (EntityTransition transition : workflow.transitions()) {
            assertThat(actions.contains(transition.code()))
                    .as(
                            "%s is offered in %s",
                            transition.code(), workflow.initial().code())
                    .isEqualTo(transition.from().contains(workflow.initial().code()));
        }
    }

    private void notAllowed(EntityTransition transition, String state) throws Exception {
        Created record = reach(state);
        MockHttpServletResponse refused = take(world.owner, transition.code(), record.id());
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        Map<String, Object> problem = TestSession.object(refused);
        assertThat(problem).containsEntry("code", "entity_transition_not_allowed");
        assertThat(world.readOk(world.owner, record.id())).containsEntry(workflow.field(), state);
    }

    private void forbidden(EntityTransition transition, String state) throws Exception {
        Created record = reach(state);
        MockHttpServletResponse refused = take(userWithout(transition.permission()), transition.code(), record.id());
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(403);
    }

    private void taken(EntityTransition transition, String state) throws Exception {
        Created record = reach(state);
        long revision = world.revision(world.owner, record.id());
        MockHttpServletResponse moved = take(world.owner, transition.code(), record.id());
        assertThat(moved.getStatus()).as(moved.getContentAsString()).isEqualTo(200);
        Map<String, Object> body = TestSession.object(moved);
        assertThat(body).containsEntry(workflow.field(), transition.to());
        assertThat(number(body.get("revision"))).isEqualTo(revision + 1);
        if (world.has(EntityCapability.HISTORY)) {
            assertThat(world.history(world.owner, record.id()).getContentAsString())
                    .as("the history names the transition")
                    .contains("\"" + transition.code() + "\"");
        }
    }

    private void locked(EntityState state) throws Exception {
        Created record = reach(state.code());
        long revision = world.revision(world.owner, record.id());
        String token = world.token();
        for (FormField field : world.entity.fields()) {
            boolean lockedField = state.terminal() || state.locks().contains(field.key());
            if (!lockedField || field.attribute() != null || field.flags().readonly() != null) continue;
            Optional<Object> changed = EntitySamples.changed(field, token);
            if (changed.isEmpty()) continue;
            MockHttpServletResponse refused =
                    world.update(world.owner, record.id(), Map.of(field.key(), changed.get()), ifMatch(revision));
            assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
            assertThat(fieldErrors(refused)).contains(field.key() + ":" + EntityValidator.READONLY);
        }
        for (EntityCollection collection : world.model.collections()) {
            if (!state.terminal() && !state.locks().contains(collection.key())) continue;
            MockHttpServletResponse refused = world.update(
                    world.owner,
                    record.id(),
                    Map.of(collection.key(), collections.rows(collection)),
                    ifMatch(revision));
            assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
            assertThat(fieldErrors(refused)).contains(collection.key() + ":" + EntityValidator.READONLY);
        }
        assertThat(world.revision(world.owner, record.id()))
                .as("nothing was written")
                .isEqualTo(revision);
    }

    /** A record of the owner in {@code state}, reached by the shortest path of transitions from the initial one. */
    private Created reach(String state) throws Exception {
        Created record = world.create(world.owner);
        for (EntityTransition step : path(state).orElseThrow()) {
            MockHttpServletResponse moved = take(world.owner, step.code(), record.id());
            assertThat(moved.getStatus())
                    .as("%s: %s", step.code(), moved.getContentAsString())
                    .isEqualTo(200);
        }
        return record;
    }

    /** The shortest path of transitions from the initial state to {@code state}, if any. */
    private Optional<List<EntityTransition>> path(String state) {
        Map<String, List<EntityTransition>> paths = new LinkedHashMap<>();
        paths.put(workflow.initial().code(), List.of());
        Deque<String> queue = new ArrayDeque<>(List.of(workflow.initial().code()));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (EntityTransition transition : workflow.transitions()) {
                if (!transition.from().contains(current) || paths.containsKey(transition.to())) continue;
                List<EntityTransition> next = new ArrayList<>(paths.get(current));
                next.add(transition);
                paths.put(transition.to(), next);
                queue.add(transition.to());
            }
        }
        return Optional.ofNullable(paths.get(state));
    }

    /** A reachable state that the transition leaves ({@code leaves}) or does not leave, the nearest first. */
    private Optional<String> stateWhere(EntityTransition transition, boolean leaves) {
        return workflow.states().stream()
                .map(EntityState::code)
                .filter(state -> transition.from().contains(state) == leaves)
                .filter(state -> path(state).isPresent())
                .min((a, b) -> Integer.compare(
                        path(a).orElseThrow().size(), path(b).orElseThrow().size()));
    }

    /** Takes the transition from the record's current revision. */
    private MockHttpServletResponse take(TestUser who, String transition, long id) throws Exception {
        long revision = world.revision(world.owner, id);
        return world.session(who)
                .send(post(world.transport.record(id) + "/actions/" + transition)
                        .header("If-Match", ifMatch(revision)));
    }

    /** A user of the owner's unit with every right of the entity but {@code permission}. */
    private TestUser userWithout(String permission) {
        return without.computeIfAbsent(permission, missing -> {
            Map<String, Set<String>> rights = new HashMap<>(world.fieldRights());
            rights.put(world.entity.form(), rightsWithout(missing));
            return TestUsers.of(world.wac).withRights(rights, world.owner.unit());
        });
    }

    private Set<String> rightsWithout(String permission) {
        Set<String> rights = new TreeSet<>(world.entityRights().get(world.entity.form()));
        rights.remove(permission);
        rights.add("view");
        return rights;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(@Nullable Object value) {
        return (T) Objects.requireNonNull(value);
    }
}
