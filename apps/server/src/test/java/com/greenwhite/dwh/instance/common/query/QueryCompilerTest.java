package com.greenwhite.dwh.instance.common.query;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class QueryCompilerTest {

    private static final QueryList LIST = new QueryList("test.items", "test.form", "view",
            "t.id, t.code", "test_items t", "t.id",
            List.of(
                    QueryField.of("code", "test.code", QueryFieldType.TEXT, "t.code").asSortable(),
                    QueryField.of("note", "test.note", QueryFieldType.TEXT, "t.note").asNullable(),
                    QueryField.of("amount", "test.amount", QueryFieldType.NUMBER, "t.amount").asSortable(),
                    QueryField.of("dueOn", "test.due", QueryFieldType.DATE, "t.due_on").asNullable(),
                    QueryField.of("active", "test.active", QueryFieldType.BOOLEAN, "t.active"),
                    QueryField.enumeration("state", "test.state", "t.state", List.of("open", "closed"), "test.state."),
                    QueryField.of("secret", "test.secret", QueryFieldType.TEXT, "t.secret").asNotFilterable()),
            "code");

    private static QueryPlan compile(String filter, String sort) {
        return QueryCompiler.compile(LIST, filter, sort, null, null);
    }

    private static List<FieldErrorItem> errors(String filter, String sort) {
        ApiException e = catchThrowableOfType(ApiException.class, () -> compile(filter, sort));
        assertThat(e).as("expected a validation error").isNotNull();
        return e.getFieldErrors();
    }

    @Test
    @DisplayName("пустой запрос — сортировка и размер страницы списка по умолчанию, без условий")
    void emptyRequestUsesDefaults() {
        QueryPlan plan = compile(null, null);

        assertThat(plan.conditions()).isEmpty();
        assertThat(plan.where().sql()).isEmpty();
        assertThat(plan.orderBy()).isEqualTo(" order by t.code asc, t.id asc");
        assertThat(plan.limit()).isEqualTo(QueryList.DEFAULT_LIMIT);
        assertThat(plan.keyset().sql()).isEmpty();
    }

    @Test
    @DisplayName("условия приводятся к типу поля и уходят только параметрами")
    void conditionsBecomeTypedParameters() {
        QueryPlan plan = compile("""
                [{"field":"code","op":"contains","value":"50%_a\\\\b"},
                 {"field":"amount","op":"between","value":[10,"20.5"]},
                 {"field":"state","op":"in","value":["open","closed"]},
                 {"field":"dueOn","op":"gte","value":"2026-01-31"},
                 {"field":"note","op":"empty"},
                 {"field":"active","op":"eq","value":true}]
                """, null);

        QueryPlan.SqlFragment where = plan.where();
        assertThat(where.sql()).isEqualTo(" and t.code ilike :q_f0 escape '\\'"
                + " and t.amount between :q_f1_a and :q_f1_b"
                + " and t.state in (:q_f2)"
                + " and t.due_on >= :q_f3"
                + " and (t.note is null or t.note = '')"
                + " and t.active = :q_f5");
        assertThat(where.params())
                .containsEntry("q_f0", "%50\\%\\_a\\\\b%")
                .containsEntry("q_f1_a", new BigDecimal("10"))
                .containsEntry("q_f1_b", new BigDecimal("20.5"))
                .containsEntry("q_f2", List.of("open", "closed"))
                .containsEntry("q_f3", LocalDate.of(2026, 1, 31))
                .containsEntry("q_f5", Boolean.TRUE)
                .doesNotContainKey("q_f4");
        assertThat(where.sql()).doesNotContain("50%").doesNotContain("open");
    }

    @Test
    @DisplayName("каждая ошибка адресована своему условию, все сразу")
    void reportsEveryBadConditionWithItsAddress() {
        assertThat(errors("""
                [{"field":"nope","op":"eq","value":"x"},
                 {"field":"secret","op":"eq","value":"x"},
                 {"field":"code","op":"gt","value":"x"},
                 {"field":"state","op":"eq","value":"archived"},
                 {"field":"amount","op":"eq","value":"ten"},
                 {"field":"amount","op":"between","value":[1]},
                 {"field":"code","op":"contains","value":""},
                 {"field":"code","op":"empty"},
                 {"field":"note","op":"empty","value":"x"}]
                """, null))
                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("filter[0].field", QueryCompiler.UNKNOWN_FIELD),
                        org.assertj.core.groups.Tuple.tuple("filter[1].field", QueryCompiler.UNKNOWN_FIELD),
                        org.assertj.core.groups.Tuple.tuple("filter[2].op", QueryCompiler.OP_NOT_ALLOWED),
                        org.assertj.core.groups.Tuple.tuple("filter[3].value", QueryCompiler.VALUE_INVALID),
                        org.assertj.core.groups.Tuple.tuple("filter[4].value", QueryCompiler.VALUE_INVALID),
                        org.assertj.core.groups.Tuple.tuple("filter[5].value", QueryCompiler.VALUE_INVALID),
                        org.assertj.core.groups.Tuple.tuple("filter[6].value", QueryCompiler.VALUE_INVALID),
                        org.assertj.core.groups.Tuple.tuple("filter[7].op", QueryCompiler.OP_NOT_ALLOWED),
                        org.assertj.core.groups.Tuple.tuple("filter[8].value", QueryCompiler.VALUE_INVALID));
    }

    @Test
    @DisplayName("фильтр не JSON, не массив или слишком длинный — ошибка всего фильтра")
    void rejectsMalformedFilter() {
        assertThat(errors("code=abc", null)).extracting(FieldErrorItem::code)
                .containsExactly(QueryCompiler.FILTER_INVALID);
        assertThat(errors("{\"field\":\"code\"}", null)).extracting(FieldErrorItem::code)
                .containsExactly(QueryCompiler.FILTER_INVALID);
        String many = "[" + String.join(",", java.util.Collections.nCopies(QueryCompiler.MAX_CONDITIONS + 1,
                "{\"field\":\"active\",\"op\":\"eq\",\"value\":true}")) + "]";
        assertThat(errors(many, null)).extracting(FieldErrorItem::code)
                .containsExactly(QueryCompiler.FILTER_TOO_LONG);
    }

    @Test
    @DisplayName("сортировка — только по сортируемому полю; минус — по убыванию")
    void sortsBySortableFieldsOnly() {
        QueryPlan plan = compile(null, "-amount");
        assertThat(plan.orderBy()).isEqualTo(" order by t.amount desc, t.id desc");

        assertThat(errors(null, "note")).extracting(FieldErrorItem::field, FieldErrorItem::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("sort", QueryCompiler.SORT_INVALID));
        assertThat(errors(null, "t.id; drop table x")).extracting(FieldErrorItem::code)
                .containsExactly(QueryCompiler.SORT_INVALID);
    }

    @Test
    @DisplayName("размер страницы вне границ списка — INVALID_LIMIT")
    void rejectsLimitOutOfRange() {
        assertThatThrownBy(() -> QueryCompiler.compile(LIST, null, null, 0, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage())
                        .isEqualTo(QueryCompiler.INVALID_LIMIT));
        assertThatThrownBy(() -> QueryCompiler.compile(LIST, null, null, QueryList.MAX_LIMIT + 1, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("курсор продолжает только тот запрос, которым выдан")
    void cursorBelongsToItsQuery() {
        QueryPlan first = compile("[{\"field\":\"active\",\"op\":\"eq\",\"value\":true}]", "-amount");
        String cursor = new QueryCursor(first.fingerprint(), new BigDecimal("12.50"), 42, 7).encode(first.sort());

        QueryPlan next = QueryCompiler.compile(LIST, "[{\"field\":\"active\",\"op\":\"eq\",\"value\":true}]",
                "-amount", null, cursor);
        assertThat(next.cursor().lastId()).isEqualTo(42);
        assertThat(next.cursor().total()).isEqualTo(7);
        assertThat(next.keyset().sql())
                .isEqualTo(" and (t.amount < :q_after_value or (t.amount = :q_after_value and t.id < :q_after_id))");
        assertThat(next.keyset().params()).containsEntry("q_after_value", new BigDecimal("12.50"))
                .containsEntry("q_after_id", 42L);

        for (String otherSort : new String[]{"amount", "code"}) {
            assertThatThrownBy(() -> QueryCompiler.compile(LIST,
                    "[{\"field\":\"active\",\"op\":\"eq\",\"value\":true}]", otherSort, null, cursor))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage())
                            .isEqualTo(QueryCompiler.INVALID_CURSOR));
        }
        assertThatThrownBy(() -> QueryCompiler.compile(LIST,
                "[{\"field\":\"active\",\"op\":\"eq\",\"value\":false}]", "-amount", null, cursor))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QueryCompiler.compile(LIST, null, null, null, "%%%"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("свободный поиск q: подстрока в любом searchable-поле, экранирован, входит в отпечаток курсора")
    void searchLooksAtSearchableFieldsOnly() {
        QueryList list = new QueryList("test.search", "test.form", "view", "t.id", "test_items t", "t.id",
                List.of(QueryField.of("code", "c", QueryFieldType.TEXT, "t.code").asSortable().asSearchable(),
                        QueryField.of("name", "n", QueryFieldType.TEXT, "t.name").asSearchable(),
                        QueryField.of("note", "x", QueryFieldType.TEXT, "t.note")),
                "code");

        QueryPlan plan = QueryCompiler.compile(list, null, null, null, null, "  50%_off  ");
        assertThat(plan.where().sql()).isEqualTo(" and (t.code ilike :q_search escape '\\' or t.name ilike :q_search escape '\\')");
        assertThat(plan.where().params()).containsEntry("q_search", "%50\\%\\_off%");
        assertThat(QueryCompiler.compile(list, null, null, null, null, "   ").where().sql()).isEmpty();
        assertThat(plan.fingerprint()).isNotEqualTo(QueryCompiler.compile(list, null, null, null, null, "other").fingerprint());

        assertThatThrownBy(() -> QueryCompiler.compile(list, null, null, null, null, "x".repeat(201)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QueryCompiler.compile(LIST, null, null, null, null, "x"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QueryField.of("n", "n", QueryFieldType.NUMBER, "t.n").asSearchable())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("реестр не пускает сортировку по пустому полю и дубли")
    void registryRejectsUnsafeDefinitions() {
        assertThatThrownBy(() -> QueryField.of("note", "x", QueryFieldType.TEXT, "t.note").asNullable().asSortable())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryField.of("Bad key", "x", QueryFieldType.TEXT, "t.x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryListRegistry(List.of(LIST, LIST)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new QueryListRegistry(List.of(LIST)).find("test.items")).contains(LIST);
    }
}
