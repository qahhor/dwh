package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldAccess;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * One run of the entity contract kit (ADR-0032, 11.3): the entity, its transport and fixture, the users the checks act
 * as — each with a role of their own, in one of two org units with the rule {@code UNITS} — and the requests every
 * check sends. A record is always created through the transport, never inserted by hand.
 */
final class KitWorld {

    /** An id no record has. */
    static final long MISSING = 9_000_000_000L;

    static final String ARCHIVED_ONLY = "[{\"field\":\"archived\",\"op\":\"eq\",\"value\":true}]";

    private static final int PAGE = 50;
    private static final int MOST_PAGES = 40;

    /** A record the kit created: its id, the values it was created with and the revision it answered with. */
    record Created(long id, Map<String, Object> values, long revision, MockHttpServletResponse response) {}

    final WebApplicationContext wac;
    final JdbcClient jdbc;
    final EntityDefinition entity;
    final EntityModel model;
    final EntityTransport transport;
    final EntityFixture fixture;
    final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** Every right of the entity and of its fields, in unit A. */
    final TestUser owner;
    /** Every right of the entity and of its fields, in unit B: outside the owner's scope. */
    final TestUser outsider;
    /** Only {@code view} of the entity, in unit A. */
    final TestUser viewer;
    /** No right of the entity, in unit A. */
    final TestUser stranger;
    /** Every right of the entity but none of its fields, in unit A; null when no field needs a right. */
    final @Nullable TestUser plain;

    private final Map<Long, TestSession> sessions = new HashMap<>();
    private int counter;

    KitWorld(
            WebApplicationContext wac,
            JdbcClient jdbc,
            EntityDefinition entity,
            EntityTransport transport,
            EntityFixture fixture) {
        this.wac = wac;
        this.jdbc = jdbc;
        this.entity = entity;
        this.model = Objects.requireNonNull(entity.model(), () -> entity.code() + " has no table: nothing to check");
        this.transport = transport;
        this.fixture = fixture;
        TestUsers users = TestUsers.of(wac);
        long unitA = users.unit("kit-a");
        long unitB = users.unit("kit-b");
        Map<String, Set<String>> full = fullRights();
        owner = users.withRights(full, unitA);
        outsider = users.withRights(full, unitB);
        viewer = users.withRights(Map.of(entity.form(), Set.of("view")), unitA);
        stranger = users.withRights(Map.of(), unitA);
        plain = fieldRights().isEmpty() ? null : users.withRights(plainRights(), unitA);
    }

    /** The rights of a user without the field rights: a field may need an action of the entity itself. */
    private Map<String, Set<String>> plainRights() {
        Map<String, Set<String>> rights = merge(entityRights(), referenceRights());
        fieldRights().forEach((form, actions) -> {
            Set<String> held = rights.get(form);
            if (held == null) return;
            held.removeAll(actions);
            held.add("view");
        });
        return rights;
    }

    /** The rights of the kit's owner: the entity's, its fields' and its references'. */
    Map<String, Set<String>> fullRights() {
        return merge(merge(entityRights(), fieldRights()), referenceRights());
    }

    /**
     * {@code view} of each other entity a reference names (ADR-0032, 4.6): a new value must be a row its author sees, so
     * the record of a fixture that names one is written by users who see the target.
     */
    Map<String, Set<String>> referenceRights() {
        EntityRegistry registry = wac.getBean(EntityRegistry.class);
        Map<String, Set<String>> rights = new TreeMap<>();
        for (EntityField field : model.fields()) {
            String target = field.options().target();
            if (target == null || target.equals(entity.code())) continue;
            registry.find(target).ifPresent(other -> rights.put(other.form(), Set.of("view")));
        }
        return rights;
    }

    /** The entity's right: {@code view}, the right of every declared action and {@code import} of an importable one. */
    Map<String, Set<String>> entityRights() {
        Set<String> actions = new TreeSet<>();
        actions.add("view");
        entity.actions().forEach(action -> actions.add(action.permission()));
        if (has(EntityCapability.IMPORT)) actions.add(EntityDefinition.IMPORT);
        return Map.of(entity.form(), actions);
    }

    /** The rights the entity's fields need to exist or to be written (ADR-0032, 5.2). */
    Map<String, Set<String>> fieldRights() {
        Map<String, Set<String>> rights = new TreeMap<>();
        for (EntityField field : model.fields()) {
            FieldAccess access = field.access();
            if (access.requiredForm() != null) {
                rights.computeIfAbsent(access.requiredForm(), form -> new TreeSet<>())
                        .add(Objects.requireNonNull(access.requiredAction()));
            }
            if (access.readonlyForm() != null) {
                rights.computeIfAbsent(access.readonlyForm(), form -> new TreeSet<>())
                        .add(Objects.requireNonNull(access.readonlyAction()));
            }
        }
        return rights;
    }

    boolean has(EntityCapability capability) {
        return entity.capabilities().contains(capability);
    }

    boolean declares(String action) {
        return entity.action(action).isPresent();
    }

    boolean scoped() {
        return !(model.scope() instanceof EntityScope.All);
    }

    /** The fields a save writes, in declaration order. */
    List<EntityField> writable() {
        return model.fields().stream().filter(EntitySamples::writable).toList();
    }

    FormField form(EntityField field) {
        return Objects.requireNonNull(field.formField());
    }

    TestSession session(TestUser user) {
        return sessions.computeIfAbsent(user.id(), id -> {
            try {
                return TestSession.signIn(wac, user.login());
            } catch (Exception e) {
                throw new IllegalStateException("Sign-in of " + user.login() + " failed", e);
            }
        });
    }

    /** A token unique in this run: lower-case letters and digits, so it fits most patterns. */
    String token() {
        return "k" + tag + (++counter);
    }

    /**
     * The values of a fresh record created by {@code creator}: the fixture's, then a made-up value of every written
     * field; the org unit of an org-unit scope is the creator's.
     */
    Map<String, Object> validValues(TestUser creator) {
        Map<String, Object> values = new LinkedHashMap<>();
        String token = token();
        for (EntityField field : writable()) {
            if (creator.equals(plain) && !FieldAccess.OPEN.equals(field.access())) {
                // A field the creator may not see or write is left to its holders.
                continue;
            }
            Optional<Object> unit = unitOf(field, creator);
            Optional<Object> value = unit.isPresent()
                    ? unit
                    : fixture.validValues().containsKey(field.key())
                            ? Optional.ofNullable(fixture.validValues().get(field.key()))
                            : EntitySamples.valid(form(field), token);
            if (value.isPresent()) {
                values.put(field.key(), value.get());
            } else if (form(field).required()) {
                throw new AssertionError("The kit cannot make up a value of the required field " + field.key() + " ("
                        + field.type().wire() + "): give one in EntityFixture.valid(...)");
            }
        }
        Set<String> declared = new HashSet<>();
        model.fields().forEach(field -> declared.add(field.key()));
        fixture.validValues().forEach((key, value) -> {
            if (!declared.contains(key)) values.putIfAbsent(key, value);
        });
        return values;
    }

    /**
     * The org unit a field of {@code creator}'s record holds, when the field holds one: the unit column of an org-unit
     * scope, or a field that takes the creator's home unit by default ({@code FieldDefault.currentOrgUnit()}, the home
     * unit of a user). The kit's users each have their own unit, so a record stays in its creator's scope.
     */
    Optional<Object> unitOf(EntityField field, TestUser creator) {
        boolean scopeColumn = model.scope() instanceof EntityScope.OrgUnit unit
                && field.source() instanceof FieldSource.Column column
                && column.name().equals(unit.orgUnitColumn());
        boolean homeUnit =
                field.formField() != null && form(field).flags().defaultValue() instanceof FieldDefault.CurrentOrgUnit;
        return scopeColumn || homeUnit ? Optional.of(creator.unit()) : Optional.empty();
    }

    /** The change of the update: the fixture's, or a new value of the first field an update may change. */
    Map<String, Object> updateValues() {
        if (!fixture.updateValues().isEmpty()) return new LinkedHashMap<>(fixture.updateValues());
        String token = token();
        List<EntityField> candidates = writable().stream()
                .filter(EntitySamples::updatable)
                .sorted((a, b) -> Boolean.compare(!textual(a), !textual(b)))
                .toList();
        for (EntityField field : candidates) {
            Optional<Object> value = EntitySamples.changed(form(field), token);
            if (value.isPresent()) return new LinkedHashMap<>(Map.of(field.key(), value.get()));
        }
        throw new AssertionError("No field of " + entity.code() + " an update can change: give EntityFixture.update");
    }

    private static boolean textual(EntityField field) {
        return field.type() == FieldType.TEXT || field.type() == FieldType.TEXTAREA;
    }

    Created create(TestUser creator) throws Exception {
        Map<String, Object> values = validValues(creator);
        MockHttpServletResponse response = post(creator, values);
        assertThat(response.getStatus())
                .as("create %s: %s", entity.code(), response.getContentAsString())
                .isEqualTo(201);
        Map<String, Object> body = TestSession.object(response);
        return new Created(number(body.get("id")), values, number(body.get("revision")), response);
    }

    MockHttpServletResponse post(TestUser who, Map<String, ?> body) throws Exception {
        return session(who).send(MockMvcRequestBuilders.post(transport.collection()), body);
    }

    MockHttpServletResponse read(TestUser who, long id) throws Exception {
        return session(who).send(get(transport.read(id)));
    }

    Map<String, Object> readOk(TestUser who, long id) throws Exception {
        MockHttpServletResponse response = read(who, id);
        assertThat(response.getStatus())
                .as("read %d: %s", id, response.getContentAsString())
                .isEqualTo(200);
        return TestSession.object(response);
    }

    long revision(TestUser who, long id) throws Exception {
        return number(readOk(who, id).get("revision"));
    }

    MockHttpServletResponse update(TestUser who, long id, Map<String, ?> body, @Nullable String ifMatch)
            throws Exception {
        return session(who).send(withIfMatch(request(transport.updateMethod(), transport.record(id)), ifMatch), body);
    }

    MockHttpServletResponse delete(TestUser who, long id, @Nullable String ifMatch) throws Exception {
        return session(who).send(withIfMatch(MockMvcRequestBuilders.delete(transport.record(id)), ifMatch));
    }

    MockHttpServletResponse archive(TestUser who, long id, boolean archived, @Nullable String ifMatch)
            throws Exception {
        return session(who).send(withIfMatch(put(transport.archive(id)), ifMatch), Map.of("archived", archived));
    }

    /** A declared action other than the standard ones, through the transport's endpoint for it. */
    MockHttpServletResponse action(TestUser who, String code, long id) throws Exception {
        EntityTransport.RecordAction action = transport
                .action(code)
                .orElseThrow(() -> new AssertionError("The action " + code + " of " + entity.code()
                        + " has no endpoint in the transport: name it with EntityTransport.Module.action(...)"));
        MockHttpServletRequestBuilder request =
                request(action.method(), action.path().apply(id)).header("If-Match", ifMatch(1));
        return action.body() == null ? session(who).send(request) : session(who).send(request, action.body());
    }

    MockHttpServletResponse history(TestUser who, long id) throws Exception {
        return session(who).send(get("/api/v1/history/" + entity.code() + "/" + id));
    }

    /** The declared actions other than create, update, archive and delete. */
    List<String> ownActions() {
        Set<String> standard = Set.of("create", "update", EntityDefinition.ARCHIVE, EntityDefinition.DELETE);
        return entity.actions().stream()
                .map(EntityDefinition.EntityAction::code)
                .filter(code -> !standard.contains(code))
                .toList();
    }

    /** Whether the viewer's list, page after page, holds the record. */
    boolean listed(TestUser who, long id, @Nullable String filter) throws Exception {
        return ids(who, filter).contains(id);
    }

    /** The ids of every record in the viewer's list (a fresh user's list is short). */
    Set<Long> ids(TestUser who, @Nullable String filter) throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < MOST_PAGES; page++) {
            MockHttpServletRequestBuilder request = get(transport.collection()).param("limit", String.valueOf(PAGE));
            if (filter != null) request.param("filter", filter);
            if (cursor != null) request.param("cursor", cursor);
            MockHttpServletResponse response = session(who).send(request);
            assertThat(response.getStatus())
                    .as("list: %s", response.getContentAsString())
                    .isEqualTo(200);
            Map<String, Object> body = TestSession.object(response);
            for (Object item : (List<?>) body.get("items")) {
                ids.add(number(((Map<?, ?>) item).get("id")));
            }
            cursor = (String) body.get("nextCursor");
            if (!Boolean.TRUE.equals(body.get("hasMore")) || cursor == null) return ids;
        }
        throw new AssertionError("The list of " + entity.code() + " has more than " + PAGE * MOST_PAGES + " records");
    }

    /** The items of the viewer's first list page. */
    List<Map<String, Object>> items(TestUser who) throws Exception {
        MockHttpServletResponse response =
                session(who).send(get(transport.collection()).param("limit", String.valueOf(PAGE)));
        assertThat(response.getStatus())
                .as("list: %s", response.getContentAsString())
                .isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) TestSession.object(response).get("items");
        return items;
    }

    /** The value of a field in a record read back: its property, or the attribute it lives in. */
    static @Nullable Object value(Map<String, Object> record, EntityField field) {
        if (record.containsKey(field.key()) || field.attribute() == null) return record.get(field.key());
        Object attributes = record.get(EntityModel.ATTRIBUTES);
        return attributes instanceof Map<?, ?> map ? map.get(field.attribute()) : null;
    }

    static String ifMatch(long revision) {
        return "\"" + revision + "\"";
    }

    /** What tells one refusal from another: the status, the code and the message key, not the id in the text. */
    static Map<String, Object> problem(MockHttpServletResponse response) throws Exception {
        Map<String, Object> body = response.getContentAsString().isBlank() ? Map.of() : TestSession.object(response);
        return Map.of(
                "status", response.getStatus(),
                "code", String.valueOf(body.get("code")),
                "messageKey", String.valueOf(body.get("messageKey")));
    }

    /** The field problems of a 422 as {@code field:code}. */
    static List<String> fieldErrors(MockHttpServletResponse response) throws Exception {
        Object errors = TestSession.object(response).get("errors");
        if (!(errors instanceof List<?> list)) return List.of();
        return list.stream()
                .map(item -> ((Map<?, ?>) item).get("field") + ":" + ((Map<?, ?>) item).get("code"))
                .toList();
    }

    static long number(@Nullable Object value) {
        return ((Number) Objects.requireNonNull(value, "a number in the answer")).longValue();
    }

    private static MockHttpServletRequestBuilder withIfMatch(
            MockHttpServletRequestBuilder request, @Nullable String ifMatch) {
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private static Map<String, Set<String>> merge(Map<String, Set<String>> a, Map<String, Set<String>> b) {
        Map<String, Set<String>> merged = new TreeMap<>();
        a.forEach((form, actions) ->
                merged.computeIfAbsent(form, f -> new TreeSet<>()).addAll(actions));
        b.forEach((form, actions) ->
                merged.computeIfAbsent(form, f -> new TreeSet<>()).addAll(actions));
        return merged;
    }
}
