package com.smartup24.cms.instance.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.versioning.VersioningService;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.support.ConstraintCodeCatalog;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.units.api.UnitConversion.CoefficientRef;
import com.smartup24.cms.instance.units.api.UnitError;
import com.smartup24.cms.instance.units.service.UnitService;
import com.smartup24.cms.instance.warehouse.api.WarehouseError;
import com.smartup24.cms.instance.warehouse.load.WarehouseLoadService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@link ConstraintCode} enums of the modules split out of the former foundation and the {@code pg_constraint}
 * constraints of the {@code fnd_*} tables match both ways, with names by the naming rule, and each constraint is a
 * code of the module that owns its table (plan 10/10, item 4.2); the facades turn a violation of every class
 * (uk/fk/ck/ex) into a {@link ConstraintViolationException} with its code, and the transaction is rolled back.
 */
class ConstraintCodeMappingTest extends EmbeddedPostgresTest {

    private static final Pattern NAME = Pattern.compile("^fnd_[a-z0-9_]+_(uk|fk|ck|ex)_[a-z0-9_]+$");

    @Autowired
    private UnitService units;

    @Autowired
    private VersioningService versioning;

    @Autowired
    private WarehouseLoadService loads;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    private AuditActor actor;

    @BeforeEach
    void clean() {
        actor = actors.system();
        tx.executeWithoutResult(status -> {
            actors.apply(actor);
            jdbc.sql("select set_config('dwh.maintenance', 'on', true)")
                    .query(String.class)
                    .single();
            jdbc.sql("delete from fnd_unit_coefficient_versions").update();
            jdbc.sql("delete from fnd_unit_coefficients").update();
            jdbc.sql("delete from fnd_units").update();
            jdbc.sql("delete from fnd_load_log").update();
            jdbc.sql("update fnd_loads set superseded_by = null").update();
            jdbc.sql("delete from fnd_loads").update();
        });
    }

    @Test
    @DisplayName("AC-9а: каждое ограничение u/f/c/x таблиц fnd_* есть в enum, каждый элемент enum с именем — в базе")
    void enumAndConstraintsMatchBothWays() {
        // The fnd_test_* tables come from the version standard tests (VersioningServiceTest): fixtures, not the core
        // schema
        Set<String> database = Set.copyOf(jdbc.sql("""
                        select con.conname from pg_constraint con
                          join pg_class rel on rel.oid = con.conrelid
                          join pg_namespace ns on ns.oid = rel.relnamespace
                         where ns.nspname = 'public' and rel.relname like 'fnd\\_%'
                           and rel.relname not like 'fnd\\_test\\_%'
                           and con.contype in ('u', 'f', 'c', 'x')
                        """).query(String.class).list());
        Set<String> enumerated = ConstraintCodeCatalog.all().stream()
                .map(ConstraintCode::constraintName)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        assertThat(database).as("ограничения в базе").isNotEmpty();
        assertThat(database.stream()
                        .filter(name -> !NAME.matcher(name).matches())
                        .sorted()
                        .toList())
                .as("имена не по регламенту <table>_(uk|fk|ck|ex)_<suffix>")
                .isEmpty();
        assertThat(database.stream()
                        .filter(name -> !enumerated.contains(name))
                        .sorted()
                        .toList())
                .as("ограничения базы без элемента ConstraintCode")
                .isEmpty();
        assertThat(enumerated.stream()
                        .filter(name -> !database.contains(name))
                        .sorted()
                        .toList())
                .as("элементы ConstraintCode без ограничения в базе")
                .isEmpty();
        assertThat(ConstraintCodeCatalog.all().stream()
                        .filter(code -> code.constraintName().isPresent())
                        .filter(code -> !code.name()
                                .equalsIgnoreCase(code.constraintName().get()))
                        .toList())
                .as("имя элемента enum = имя ограничения в верхнем регистре")
                .isEmpty();
    }

    @Test
    @DisplayName("4.2: каждое ограничение таблицы fnd_* — код модуля-владельца таблицы")
    void eachConstraintIsACodeOfTheTablesOwner() {
        List<Map<String, Object>> constraints = jdbc.sql("""
                        select rel.relname as table_name, con.conname as constraint_name from pg_constraint con
                          join pg_class rel on rel.oid = con.conrelid
                          join pg_namespace ns on ns.oid = rel.relnamespace
                         where ns.nspname = 'public' and rel.relname like 'fnd\\_%'
                           and rel.relname not like 'fnd\\_test\\_%'
                           and con.contype in ('u', 'f', 'c', 'x')
                        """).query().listOfRows();
        List<String> misplaced = constraints.stream()
                .filter(row -> {
                    String table = (String) row.get("table_name");
                    String constraint = (String) row.get("constraint_name");
                    return ConstraintCodeCatalog.BY_TABLE_PREFIX.entrySet().stream()
                            .filter(owner -> table.startsWith(owner.getKey()))
                            .noneMatch(owner -> owner.getValue().stream()
                                    .anyMatch(code -> code.constraintName()
                                            .filter(constraint::equals)
                                            .isPresent()));
                })
                .map(row -> row.get("table_name") + "." + row.get("constraint_name"))
                .sorted()
                .toList();
        assertThat(constraints).as("ограничения в базе").isNotEmpty();
        assertThat(misplaced)
                .as("ограничение не в enum модуля-владельца таблицы")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-9б: uk/fk/ck/ex через фасады — ConstraintViolationException с кодом, транзакция откатана")
    void facadesTranslateEachConstraintClass() {
        String base = "u_map_base";
        units.registerUnit(base, Map.of("uz", "Bazaviy birlik TEST"), base, actor);
        units.registerUnit("u_map_a", Map.of("uz", "Birlik A TEST"), base, actor);

        // uk: a duplicate unit code
        ConstraintViolationException uk =
                violation(() -> units.registerUnit("u_map_a", Map.of("uz", "Dubl TEST"), base, actor));
        assertThat(uk.code()).isEqualTo(UnitError.FND_UNITS_UK_CODE);
        assertThat(uk.getMessageKey()).isEqualTo("error.fnd.fnd_units_uk_code");
        assertThat(uk.getErrorCode()).isEqualTo(ErrorCode.CODE_ALREADY_EXISTS);
        assertThat(uk.getCause())
                .as("SQL-текст остаётся причиной, код — контракт")
                .isNotNull();

        // fk: a base unit that does not exist
        ConstraintViolationException fk =
                violation(() -> units.registerUnit("u_map_b", Map.of("uz", "Birlik B TEST"), "u_map_missing", actor));
        assertThat(fk.code()).isEqualTo(UnitError.FND_UNITS_FK_BASE_UNIT);
        assertThat(units.findUnit("u_map_b")).as("транзакция откатана").isEmpty();

        // ck: the row counters do not add up
        long loadId = loads.begin(
                "src_map_test",
                UUID.randomUUID(),
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-31"),
                "v1",
                actor);
        ConstraintViolationException ck = violation(() -> loads.apply(loadId, 10, 2, 1, actor));
        assertThat(ck.code()).isEqualTo(WarehouseError.FND_LOADS_CK_ROWS);
        assertThat(loads.find(loadId).orElseThrow().status())
                .as("транзакция откатана")
                .isEqualTo("pending");

        // ex: overlapping intervals of published versions of one coefficient
        CoefficientRef first =
                units.publishCoefficient("u_map_a", base, BigDecimal.TEN, LocalDate.parse("2026-01-01"), actor);
        versioning.supersede(UnitService.COEFFICIENT_VERSIONS, first.coefficientId(), first.version(), actor);
        int closed = versioning.createDraft(UnitService.COEFFICIENT_VERSIONS, first.coefficientId(), actor);
        versioning.updateDraft(
                UnitService.COEFFICIENT_VERSIONS,
                first.coefficientId(),
                closed,
                0,
                Map.of("factor", BigDecimal.TEN),
                actor);
        versioning.publish(
                UnitService.COEFFICIENT_VERSIONS,
                first.coefficientId(),
                closed,
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-12-31"),
                actor);
        int overlapping = versioning.createDraft(UnitService.COEFFICIENT_VERSIONS, first.coefficientId(), actor);
        versioning.updateDraft(
                UnitService.COEFFICIENT_VERSIONS,
                first.coefficientId(),
                overlapping,
                0,
                Map.of("factor", BigDecimal.ONE),
                actor);
        ConstraintViolationException ex = violation(() -> versioning.publish(
                UnitService.COEFFICIENT_VERSIONS,
                first.coefficientId(),
                overlapping,
                LocalDate.parse("2026-06-01"),
                null,
                actor));
        assertThat(ex.code()).isEqualTo(UnitError.FND_UNIT_COEFFICIENT_VERSIONS_EX_VALID);
        List<String> statuses = jdbc.sql("select status from fnd_unit_coefficient_versions"
                        + " where coefficient_id = :id order by version")
                .param("id", first.coefficientId())
                .query(String.class)
                .list();
        assertThat(statuses)
                .as("черновик остался черновиком — откат")
                .containsExactly("superseded", "published", "draft");
    }

    private static ConstraintViolationException violation(Runnable action) {
        Throwable error = catchThrowable(action::run);
        assertThat(error).isInstanceOf(ConstraintViolationException.class);
        return (ConstraintViolationException) error;
    }
}
