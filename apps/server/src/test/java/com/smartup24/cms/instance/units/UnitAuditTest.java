package com.smartup24.cms.instance.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartup24.cms.instance.common.actor.ActorError;
import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.error.ConstraintErrors;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Changes of the fnd tables are written to the framework's {@code audit_log} by the trigger
 * {@code fnd_audit_trigger} (V100); the actor is the numeric {@code app.user_id} from {@code md_users}. The behavior
 * is checked on {@code fnd_units}: one trigger serves every table of the {@code fnd_audit_tables} registry, so the
 * other tables behave the same.
 */
class UnitAuditTest extends EmbeddedPostgresTest {

    private static final String UNIT = "u_audit_test";

    @Autowired
    private MdAuditActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    private AuditActor system;
    private AuditActor user;

    @BeforeEach
    void cleanUnits() {
        system = actors.system();
        user = AuditActor.user(userId());
        tx.executeWithoutResult(status -> {
            actors.apply(system);
            jdbc.sql("select set_config('dwh.maintenance', 'on', true)")
                    .query(String.class)
                    .single();
            jdbc.sql("delete from fnd_unit_coefficient_versions").update();
            jdbc.sql("delete from fnd_unit_coefficients").update();
            jdbc.sql("delete from fnd_units").update();
            // the framework's audit_log is never cleaned (FR-AUD-1): checks go by row_pk, and unit ids never repeat
        });
    }

    @Test
    @DisplayName("AC-6: аудит навешен на все таблицы блоков D и E; реестр fnd_audit_tables совпадает с триггерами")
    void auditCoversFndTables() {
        List<String> expected = List.of(
                "fnd_loads", "fnd_load_log", "fnd_units", "fnd_unit_coefficients", "fnd_unit_coefficient_versions");
        List<String> registered = jdbc.sql(
                        "select table_name from fnd_audit_tables where enabled and table_name like 'fnd\\_%' order by 1")
                .query(String.class)
                .list();
        List<String> triggered = jdbc.sql("""
                        select c.relname from pg_trigger t join pg_class c on c.oid = t.tgrelid
                         where t.tgname like 'fnd\\_%\\_audit\\_trg' and c.relname like 'fnd\\_%' and not t.tgisinternal order by 1
                        """).query(String.class).list();
        assertThat(registered).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(triggered).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("AC-6: I/U/D от пользователя и от задания — строка audit_log с актором, событием и списком колонок")
    void insertUpdateDeleteAreJournaled() {
        long id = inTx(user, () -> insertUnit(UNIT, "Birlik TEST"));
        inTx(
                system,
                () -> jdbc.sql("update fnd_units set name_i18n = cast(:n as jsonb) where id = :id")
                        .param("n", "{\"uz\": \"Birlik TEST 2\"}")
                        .param("id", id)
                        .update());
        inTx(
                user,
                () -> jdbc.sql("delete from fnd_units where id = :id")
                        .param("id", id)
                        .update());

        List<Map<String, Object>> rows =
                jdbc.sql("""
                        select event, changed_by, changed_columns::text as changed_columns,
                               old_row::text as old_row, new_row::text as new_row
                          from audit_log
                         where table_name = 'fnd_units' and row_pk = :pk order by changed_at, id
                        """).param("pk", String.valueOf(id)).query().listOfRows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("event", "I").containsEntry("changed_by", user.userId());
        assertThat(rows.get(0).get("old_row")).isNull();
        assertThat((String) rows.get(0).get("new_row")).contains(UNIT);
        assertThat(rows.get(1)).containsEntry("event", "U").containsEntry("changed_by", system.userId());
        assertThat((String) rows.get(1).get("changed_columns"))
                .contains("name_i18n")
                .doesNotContain("code");
        assertThat(rows.get(2)).containsEntry("event", "D").containsEntry("changed_by", user.userId());
        assertThat(rows.get(2).get("new_row")).isNull();
    }

    @Test
    @DisplayName("AC-6: UPDATE без изменений строки журнала не даёт")
    void noopUpdateIsNotJournaled() {
        long id = inTx(user, () -> insertUnit(UNIT, "Birlik TEST"));
        inTx(
                user,
                () -> jdbc.sql("update fnd_units set code = code where id = :id")
                        .param("id", id)
                        .update());
        assertThat(auditRows(id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-6: актор не задан, пуст, не число или не из md_users — audit_actor_missing, строка не изменена")
    void missingActorIsRefused() {
        long id = inTx(user, () -> insertUnit(UNIT, "Birlik TEST"));
        for (String badActor : new String[] {null, "", "   ", "admin", "42x", "-1", "999999999999"}) {
            Throwable error = catchThrowable(() -> tx.executeWithoutResult(status -> {
                if (badActor != null) {
                    jdbc.sql("select set_config('app.user_id', :a, true)")
                            .param("a", badActor)
                            .query(String.class)
                            .single();
                }
                ConstraintErrors.translating(
                        List.of(ActorError.AUDIT_ACTOR_MISSING),
                        () -> jdbc.sql("update fnd_units set name_i18n = cast(:n as jsonb)" + " where id = :id")
                                .param("n", "{\"uz\": \"X\"}")
                                .param("id", id)
                                .update());
            }));
            assertThat(error).as("актор [%s]", badActor).isInstanceOf(ConstraintViolationException.class);
            assertThat(((ConstraintViolationException) error).code())
                    .as("актор [%s]", badActor)
                    .isEqualTo(ActorError.AUDIT_ACTOR_MISSING);
        }
        assertThat(jdbc.sql("select name_i18n ->> 'uz' from fnd_units where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("Birlik TEST");
        assertThat(auditRows(id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-6: audit_log неизменяем триггером каркаса — наш путь только INSERT")
    void auditLogIsImmutable() {
        long id = inTx(user, () -> insertUnit(UNIT, "Birlik TEST"));
        assertThatThrownBy(() -> jdbc.sql(
                                "update audit_log set event = 'U' where table_name = 'fnd_units'" + " and row_pk = :pk")
                        .param("pk", String.valueOf(id))
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from audit_log where table_name = 'fnd_units'" + " and row_pk = :pk")
                        .param("pk", String.valueOf(id))
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThat(auditRows(id)).isEqualTo(1L);
        assertThat(jdbc.sql("select event from audit_log where table_name = 'fnd_units' and row_pk = :pk")
                        .param("pk", String.valueOf(id))
                        .query(String.class)
                        .single())
                .isEqualTo("I");
    }

    // ---------- helpers ----------

    private long insertUnit(String code, String nameUz) {
        return jdbc.sql("insert into fnd_units (code, name_i18n, base_unit_code)"
                        + " values (:c, cast(:n as jsonb), :c) returning id")
                .param("c", code)
                .param("n", "{\"uz\": \"" + nameUz + "\"}")
                .query(Long.class)
                .single();
    }

    private long auditRows(long unitId) {
        return jdbc.sql("select count(*) from audit_log where table_name = 'fnd_units' and row_pk = :pk")
                .param("pk", String.valueOf(unitId))
                .query(Long.class)
                .single();
    }

    private <T> T inTx(AuditActor actor, Supplier<T> action) {
        return tx.execute(status -> {
            actors.apply(actor);
            return action.get();
        });
    }

    private void inTx(AuditActor actor, Runnable action) {
        inTx(actor, () -> {
            action.run();
            return null;
        });
    }

    private long userId() {
        return tx.execute(status -> jdbc.sql("""
                        insert into md_users (name, login, email, state, language, timezone)
                        values ('Тестовый пользователь TEST', :login, :email, 'A', 'uz', 'UTC')
                        on conflict (login) do update set name = excluded.name
                        returning id
                        """)
                .param("login", "fnd-audit-test")
                .param("email", "fnd-audit-test@localhost")
                .query(Long.class)
                .single());
    }
}
