package com.smartup24.cms.instance.common.entity.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** A refused reference changes no row, revision, audit or committed event (ADR-0032, 6.3). */
@Import(EntityRuntimeFixture.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityReferenceOverflowIntegrationTest extends EmbeddedPostgresTest {

    private static final String ITEMS = "/api/v1/entities/" + EntityRuntimeFixture.CODE;
    private static final BigInteger MODULUS = new BigInteger("18446744073709551616");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    private TestSession session;
    private long note;

    @BeforeAll
    void setUp() throws Exception {
        for (String statement : EntityRuntimeFixture.DDL.split(";")) {
            jdbc.sql(statement).update();
        }
        TestUsers users = TestUsers.of(wac);
        long unit = users.unit("ref_overflow");
        var owner = users.withRights(Map.of("notes", Set.of("view", "create", "update", "delete")), unit);
        session = TestSession.signIn(wac, owner.login());
        MockHttpServletResponse created = session.send(post("/api/v1/entities/ms.notes"), Map.of("title", "target"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        note = ((Number) TestSession.object(created).get("id")).longValue();
    }

    @AfterAll
    void dropTable() {
        jdbc.sql("drop table if exists " + EntityRuntimeFixture.TABLE).update();
    }

    @ParameterizedTest
    @ValueSource(strings = {"number", "negative", "text", "fraction"})
    void aCreateWithAnInvalidReferenceLeavesNoTrace(String form) throws Exception {
        State before = state();
        MockHttpServletResponse refused =
                session.send(post(ITEMS), Map.of("title", "overflow create", "noteId", invalid(form)));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(errors(refused)).containsExactly("noteId:invalid");
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"number", "negative", "text", "fraction"})
    void aPatchWithAnInvalidReferenceLeavesTheRecordAndRevisionUnchanged(String form) throws Exception {
        long id = item("before invalid patch");
        State before = state();
        MockHttpServletResponse refused = session.send(
                patch(ITEMS + "/" + id).header("If-Match", "\"1\""),
                Map.of("title", "after invalid patch", "noteId", invalid(form)));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(errors(refused)).containsExactly("noteId:invalid");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aHandlersOversizedReferenceIsAModuleDefectAndWritesNothing() throws Exception {
        long id = item("before invalid action");
        State before = state();
        MockHttpServletResponse refused = session.send(
                post(ITEMS + "/" + id + "/actions/link").header("If-Match", "\"1\""),
                Map.of("noteId", invalid("number")));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(500);
        assertThat(TestSession.object(refused)).containsEntry("code", "internal_error");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void theLargestLongIsAValidKeyWhoseExistenceIsChecked() throws Exception {
        State before = state();
        MockHttpServletResponse refused =
                session.send(post(ITEMS), Map.of("title", "missing boundary", "noteId", "9223372036854775807"));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(errors(refused)).containsExactly("noteId:not_found");
        assertThat(state()).isEqualTo(before);
    }

    private Object invalid(String form) {
        return switch (form) {
            case "number" -> MODULUS.add(BigInteger.valueOf(note));
            case "negative" -> BigInteger.valueOf(note).subtract(MODULUS);
            case "text" -> MODULUS.add(BigInteger.valueOf(note)).toString();
            case "fraction" -> new BigDecimal("1.5");
            default -> throw new IllegalArgumentException(form);
        };
    }

    private long item(String title) throws Exception {
        MockHttpServletResponse created = session.send(post(ITEMS), Map.of("title", title));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) TestSession.object(created).get("id")).longValue();
    }

    private State state() {
        List<String> rows = jdbc.sql("select row_to_json(r)::text from test_rt_items r order by id")
                .query(String.class)
                .list();
        long audits = jdbc.sql("select count(*) from audit_log where table_name = :table")
                .param("table", EntityRuntimeFixture.TABLE)
                .query(Long.class)
                .single();
        return new State(
                rows,
                audits,
                EntityRuntimeFixture.SAVES.size(),
                EntityRuntimeFixture.COMMITTED.size(),
                EntityRuntimeFixture.EVENTS.size());
    }

    private record State(List<String> rows, long audits, int saves, int committed, int events) {}

    private static List<String> errors(MockHttpServletResponse response) throws Exception {
        Object errors = TestSession.object(response).get("errors");
        if (!(errors instanceof List<?> list)) return List.of();
        return list.stream()
                .map(item -> ((Map<?, ?>) item).get("field") + ":" + ((Map<?, ?>) item).get("code"))
                .toList();
    }
}
