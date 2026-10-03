package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.search.EntitySearchSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The global search of an entity with the SEARCH capability (ADR-0032, 10.3 and 11.2, "scope ... and search"; ADR-0013,
 * 2.5): its owner finds a record by its title, a viewer outside the owner's scope never does, a viewer without the
 * entity's {@code view} right does not search the entity at all, and the search names only fields every viewer of the
 * entity sees. The search runs on PostgreSQL in the build (no Typesense), with the same scope predicate the database
 * check of an index hit applies.
 */
final class KitSearchChecks {

    private static final String SEARCH = "search";

    private final KitWorld world;

    KitSearchChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> search() {
        if (!world.has(EntityCapability.SEARCH)) return List.of();
        EntitySearchSpec spec = Objects.requireNonNull(world.model.search(), world.entity.code());
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("the search names only fields every viewer of the entity sees", () -> {
            for (String key : spec.fields()) {
                EntityField field = world.model.field(key).orElseThrow();
                assertThat(field.access().restricted())
                        .as("the searched field %s needs no right", key)
                        .isFalse();
            }
        }));
        tests.add(dynamicTest("its owner finds a record by its title", () -> {
            Searchers searchers = searchers();
            Found record = create(searchers.owner(), spec);
            assertThat(hits(searchers.owner(), record.title(), world.entity.code()))
                    .contains(record.id());
            assertThat(hits(searchers.owner(), record.title(), "ALL")).contains(record.id());
        }));
        if (world.scoped()) {
            tests.add(dynamicTest("a viewer outside the owner's scope never finds it", () -> {
                Searchers searchers = searchers();
                Found record = create(searchers.owner(), spec);
                assertThat(hits(searchers.outsider(), record.title(), world.entity.code()))
                        .doesNotContain(record.id());
                assertThat(hits(searchers.outsider(), record.title(), "ALL")).doesNotContain(record.id());
                assertThat(hits(searchers.outsider(), "#" + record.id(), world.entity.code()))
                        .doesNotContain(record.id());
            }));
        }
        tests.add(dynamicTest("a viewer without the entity's view right does not search it", () -> {
            Searchers searchers = searchers();
            Found record = create(searchers.owner(), spec);
            MockHttpServletResponse named = searchers.stranger().send(world.entity.code(), record.title());
            assertThat(named.getStatus()).as(named.getContentAsString()).isEqualTo(400);
            assertThat(hits(searchers.strangerUser(), record.title(), "ALL")).doesNotContain(record.id());
        }));
        return tests;
    }

    /** A record of the owner and the title it is found by. */
    private record Found(long id, String title) {}

    /** The users of the search checks, each with the search right: the owner, one outside and one without view. */
    private record Searchers(TestUser owner, TestUser outsider, TestUser strangerUser, Requests stranger) {}

    /** Sends a search of one category as one user. */
    private record Requests(KitWorld world, TestUser user) {
        MockHttpServletResponse send(String entity, String query) throws Exception {
            return world.session(user)
                    .send(get("/api/v1/search").param("q", query).param("entity", entity));
        }
    }

    private Searchers searchers() {
        TestUsers users = TestUsers.of(world.wac);
        Map<String, Set<String>> rights = new TreeMap<>(world.entityRights());
        world.fieldRights()
                .forEach((form, actions) ->
                        rights.computeIfAbsent(form, ignored -> new TreeSet<>()).addAll(actions));
        rights.computeIfAbsent(SEARCH, ignored -> new TreeSet<>()).add("view");
        TestUser owner = users.withRights(rights, users.unit("kit-search-a"));
        TestUser outsider = users.withRights(rights, users.unit("kit-search-b"));
        TestUser stranger = users.withRights(Map.of(SEARCH, Set.of("view")), owner.unit());
        return new Searchers(owner, outsider, stranger, new Requests(world, stranger));
    }

    private Found create(TestUser owner, EntitySearchSpec spec) throws Exception {
        MockHttpServletResponse created = world.post(owner, world.validValues(owner));
        assertThat(created.getStatus())
                .as("create: %s", created.getContentAsString())
                .isEqualTo(201);
        long id = KitWorld.number(TestSession.object(created).get("id"));
        Object title = world.readOk(owner, id).get(spec.titleField());
        assertThat(title).as("the title %s of the record", spec.titleField()).isNotNull();
        return new Found(id, String.valueOf(title));
    }

    /** The ids of the hits of a search of {@code query} in {@code entity} ({@code ALL} or a code). */
    private List<Long> hits(TestUser who, String query, String entity) throws Exception {
        MockHttpServletResponse response = new Requests(world, who).send(entity, query);
        assertThat(response.getStatus())
                .as("search: %s", response.getContentAsString())
                .isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        for (Object hit : (List<?>) TestSession.object(response).get("hits")) {
            Map<?, ?> found = (Map<?, ?>) hit;
            if (world.entity.code().equals(found.get("entityType"))) {
                ids.add(Long.parseLong(String.valueOf(found.get("id"))));
            }
        }
        return ids;
    }
}
