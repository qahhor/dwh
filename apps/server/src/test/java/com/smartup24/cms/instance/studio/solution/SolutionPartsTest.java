package com.smartup24.cms.instance.studio.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.MdUserService.AuthUser;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** ADR-0047: placeholders, values naming records, the outcome of items, a run and the mapping of placeholders. */
class SolutionPartsTest {
    private final SolutionFixtures fixtures = new SolutionFixtures();

    @Test
    void placeholdersNameRolesOnceAndUsersByWhatTheyReceive() {
        Map<String, String> ru = Map.of("studio.packages.placeholder.mailing", "Получатели рассылки «{name}»");
        SolutionPlaceholders placeholders = new SolutionPlaceholders(language -> "ru".equals(language) ? ru : Map.of());
        assertThat(placeholders.role(7, "Согласующий")).isEqualTo("role-1");
        assertThat(placeholders.role(7, "Согласующий")).isEqualTo("role-1");
        assertThat(placeholders.role(9, "  \u0007 ")).isEqualTo("role-2");
        assertThat(placeholders.role(10, "a\u2028b")).isEqualTo("role-3");
        placeholders.users("mailing-1", "studio.packages.placeholder.mailing", "Отчёт ".repeat(20), true);
        var all = placeholders.all();
        assertThat(all)
                .extracting(placeholder -> placeholder.key())
                .containsExactly("role-1", "role-2", "role-3", "mailing-1");
        assertThat(all.get(1).labels().ru()).isEqualTo("?");
        assertThat(all.get(2).labels().ru()).isEqualTo("?");
        Labels labels = all.get(3).labels();
        assertThat(labels.ru()).startsWith("Получатели рассылки «Отчёт").hasSizeLessThanOrEqualTo(80);
        assertThat(labels.en()).isEqualTo("studio.packages.placeholder.mailing");
        assertThat(all.get(3).required()).isTrue();
    }

    @Test
    void aValueOnAFieldThatRefersByIdIsReported() {
        QueryField client = field("client", QueryFieldType.NUMBER, true);
        QueryField units = field("units", QueryFieldType.REF_SET, false);
        QueryField region = field("region", QueryFieldType.ENUM, true);
        QueryField total = field("total", QueryFieldType.NUMBER, false);
        QueryList list = mock(QueryList.class);
        when(list.field("client")).thenReturn(Optional.of(client));
        when(list.field("units")).thenReturn(Optional.of(units));
        when(list.field("region")).thenReturn(Optional.of(region));
        when(list.field("total")).thenReturn(Optional.of(total));
        when(list.field("missing")).thenReturn(Optional.empty());
        var json = fixtures.json;
        List<FieldErrorItem> problems = new ArrayList<>();
        SolutionValues.filter(list, json.readTree("""
                [{"field": "client", "op": "eq", "value": 5}, {"field": "region", "op": "eq", "value": "tashkent"},
                 {"any": [{"field": "units", "op": "in", "value": [1]}, {"field": "total", "op": "gt", "value": 3}]},
                 {"field": "client", "op": "empty"}, {"field": "missing", "op": "eq", "value": 1}, 7]
                """), "reports.1", problems);
        SolutionValues.filter(list, null, "reports.1", problems);
        SolutionValues.filter(list, json.readTree("""
                [{"field": 5, "value": 1}, {"field": "client", "op": "eq"}, {"field": "client", "value": null}]
                """), "reports.1", problems);
        assertThat(problems)
                .extracting(problem -> problem.params().get("field"))
                .containsExactly("client", "units");
        problems.clear();
        var parameters = json.readTree("""
                [{"key": "who", "field": "client", "default": 3}, {"key": "where", "field": "region", "default": "x"},
                 {"key": "none", "field": "client"}]
                """);
        SolutionValues.parameters(list, parameters, "reports.1", problems);
        SolutionValues.parameters(list, json.readTree("{}"), "reports.1", problems);
        SolutionValues.values(list, parameters, Map.of("who", 4, "where", "x"), "mailings.2", problems);
        SolutionValues.values(list, null, Map.of(), "mailings.2", problems);
        SolutionValues.values(list, json.readTree("{}"), Map.of(), "mailings.2", problems);
        SolutionValues.parameters(
                list, json.readTree("[{\"key\": \"a\", \"field\": \"client\", \"default\": null}]"), "x", problems);
        assertThat(problems).extracting(FieldErrorItem::field).containsExactly("reports.1", "mailings.2");
    }

    @Test
    void anItemRunsInASavepointAndARefusalBecomesItsOutcome() {
        SolutionItems items = new SolutionItems(new Transactions());
        Labels title = SolutionItems.same("Отчёт");
        ImportItem done = items.run("report", "r", title, () -> new SolutionItems.Done(SolutionItems.CREATE).with("n"));
        assertThat(done.outcome()).isEqualTo("create");
        assertThat(done.notes()).containsExactly("n");
        ImportItem refused = items.run("report", "r", title, () -> {
            throw ApiException.validation(
                    "error.studio.solution_invalid",
                    List.of(
                            FieldErrorItem.keyed("name", "x", "error.studio.solution_part"),
                            new FieldErrorItem("state", "x", "bean text")));
        });
        assertThat(refused.outcome()).isEqualTo("refused");
        assertThat(refused.problems())
                .extracting(problem -> problem.message())
                .containsExactly("error.studio.solution_invalid", "error.studio.solution_part", "bean text");
        assertThatThrownBy(() -> items.run("report", "r", title, () -> null)).isInstanceOf(IllegalStateException.class);
        ImportItem plain = items.run("report", "r", title, () -> {
            throw new ApiException(ErrorCode.CONFLICT, "error.studio.solution_owner");
        });
        assertThat(plain.problems())
                .singleElement()
                .satisfies(problem -> assertThat(problem.params()).isNull());
        ImportItem missing = SolutionItems.refused("mailing", "m", title, "error.studio.solution_dependency", Map.of());
        List<ImportItem> all = List.of(
                done,
                refused,
                missing,
                new ImportItem("list", "l", title, "upgrade", List.of(), List.of()),
                new ImportItem("list", "l", title, "unchanged", List.of(), List.of()),
                new ImportItem("grant", "g", title, "skip", List.of(), List.of()));
        var counts = SolutionItems.counts(all);
        assertThat(List.of(counts.create(), counts.upgrade(), counts.unchanged(), counts.skip(), counts.refused()))
                .containsExactly(1, 1, 1, 1, 2);
    }

    @Test
    void aCheckTakesTheImporterForAnUnmappedRequiredPlaceholder() {
        SolutionPackage pack = fixtures.read(fixtures.sealed(fixtures.order()));
        SolutionRun check = new SolutionRun(pack, "order-approval", Map.of("approver", List.of(4L)), false, 0, 9);
        assertThat(check.users("mailing-recipients")).containsExactly(9L);
        assertThat(check.users("seller")).isEmpty();
        assertThat(check.users(null)).isEmpty();
        assertThat(check.role("approver")).isEqualTo(4L);
        assertThat(check.role("seller")).isNull();
        assertThat(check.mapped("approver")).isTrue();
        assertThat(check.mapped(null)).isFalse();
        assertThat(check.title("approver").en()).isEqualTo("Approver");
        assertThat(check.title("unknown").ru()).isEqualTo("unknown");
        SolutionRun apply =
                new SolutionRun(pack, "order-approval", Map.of("mailing-recipients", List.of(5L, 6L)), true, 3, 9);
        assertThat(apply.users("mailing-recipients")).containsExactly(5L, 6L);
        assertThat(new SolutionRun(pack, "s", Map.of(), true, 3, 9).users("mailing-recipients"))
                .isEmpty();
    }

    @Test
    void theMappingNamesActiveRolesAndUsersOfKnownPlaceholders() {
        MdRoleService roles = mock(MdRoleService.class);
        MdUserService users = mock(MdUserService.class);
        when(roles.active(anyLong())).thenReturn(false);
        when(roles.active(4L)).thenReturn(true);
        when(users.findAuthUserById(anyLong())).thenReturn(Optional.empty());
        AuthUser active = mock(AuthUser.class);
        when(active.state()).thenReturn("A");
        when(users.findAuthUserById(5L)).thenReturn(Optional.of(active));
        SolutionMapping mapping = new SolutionMapping(roles, users);
        SolutionPackage pack = fixtures.read(fixtures.sealed(fixtures.order()));

        Map<String, List<Long>> given = new HashMap<>();
        given.put("approver", List.of(4L));
        given.put("mailing-recipients", List.of(5L, 5L));
        given.put("seller", List.of());
        given.put("unused", null);
        assertThat(mapping.check(pack, given, true))
                .containsEntry("approver", List.of(4L))
                .containsEntry("mailing-recipients", List.of(5L))
                .doesNotContainKey("seller");
        assertThat(mapping.check(pack, null, false)).isEmpty();
        assertThat(keys(() -> mapping.check(pack, null, true))).containsExactly("mapping.mailing-recipients");
        assertThat(keys(() -> mapping.check(pack, Map.of("approver", List.of(5L)), false)))
                .containsExactly("mapping.approver");
        assertThat(keys(() -> mapping.check(pack, Map.of("approver", List.of(4L, 5L)), false)))
                .containsExactly("mapping.approver");
        assertThat(keys(() -> mapping.check(pack, Map.of("mailing-recipients", List.of(5L, 6L)), false)))
                .containsExactly("mapping.mailing-recipients");
        List<Long> many = new ArrayList<>();
        for (long id = 100; id < 151; id++) many.add(id);
        assertThat(keys(() -> mapping.check(pack, Map.of("mailing-recipients", many), false)))
                .containsExactly("mapping.mailing-recipients");
        assertThat(keys(() -> mapping.check(pack, Map.of("stranger", List.of(5L)), false)))
                .containsExactly("mapping.stranger");
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(keys(() -> mapping.check(pack, Map.of("mailing-recipients", withNull), false)))
                .containsExactly("mapping.mailing-recipients");
    }

    private static List<String> keys(Runnable check) {
        try {
            check.run();
        } catch (ApiException refused) {
            assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            return refused.getFieldErrors().stream().map(FieldErrorItem::field).toList();
        }
        throw new AssertionError("The mapping was accepted");
    }

    private static QueryField field(String key, QueryFieldType type, boolean ref) {
        QueryField field = mock(QueryField.class);
        when(field.key()).thenReturn(key);
        when(field.type()).thenReturn(type);
        var target = ref ? mock(com.smartup24.cms.platform.api.entity.field.QueryRef.class) : null;
        when(field.ref()).thenReturn(target);
        return field;
    }

    /** A transaction manager without a database: savepoints run in place, a failure is rethrown. */
    private static final class Transactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
