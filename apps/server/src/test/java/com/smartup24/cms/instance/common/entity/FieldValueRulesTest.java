package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.FieldOptions.JsonRoot;
import com.smartup24.cms.platform.api.entity.field.FieldParams;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormFlags;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.2): the server's check of each new type case by case, the kept form of a value,
 * the equality a read-only field is judged by, and the cache of the reference entities.
 */
class FieldValueRulesTest {

    private static final String FILE_ID = "6f1c2a52-6b0e-4d3e-9a51-1f2d3c4b5a69";

    @Test
    void anEmailAPhoneAndAnAddress() {
        FormField email = field(FieldType.EMAIL, FieldParams.NONE);
        FormField phone = field(FieldType.PHONE, FieldParams.NONE);
        FormField url = field(FieldType.URL, FieldParams.NONE);

        assertThat(code(email, " Ann@Example.com ")).isNull();
        assertThat(code(email, "a".repeat(250) + "@x.uz")).isEqualTo("invalid");
        assertThat(FieldValueRules.normalize(email, " Ann@Example.COM ")).isEqualTo("ann@example.com");
        assertThat(code(phone, "+998 (90) 123-45-67")).isNull();
        assertThat(code(phone, "+0123456789")).isEqualTo("invalid");
        assertThat(FieldValueRules.normalize(phone, "+998 90-123")).isEqualTo("+99890123");
        assertThat(FieldValueRules.normalize(url, " https://a.uz ")).isEqualTo("https://a.uz");
        assertThat(FieldValueRules.normalize(field(FieldType.TEXT, FieldParams.NONE), " x "))
                .isEqualTo(" x ");
        for (String bad : List.of("", "https://", "mailto:a@b.uz", "http://a b", "x".repeat(2050))) {
            assertThat(code(url, bad)).as(bad).isEqualTo("invalid");
        }
        assertThat(code(url, "HTTPS://Example.com/a?b=1")).isNull();
    }

    @Test
    void money() {
        FormField total = new FormField(
                "total",
                "l",
                null,
                FieldType.MONEY,
                false,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.valueOf(1000),
                null,
                List.of(),
                null,
                null,
                null,
                FormFlags.NONE,
                new FieldParams(1, null, null, List.of(), List.of("UZS", "JPY"), null, null));

        assertThat(code(total, Map.of("amount", "10.5", "currency", "UZS"))).isNull();
        assertThat(code(total, "10 UZS")).isEqualTo("invalid");
        assertThat(code(total, Map.of("currency", "UZS"))).isEqualTo("invalid");
        assertThat(code(total, Map.of("amount", "1e3", "currency", "UZS"))).isEqualTo("invalid");
        assertThat(code(total, Map.of("amount", "10"))).isEqualTo("invalid");
        assertThat(code(total, Map.of("amount", "10", "currency", "EUR"))).isEqualTo("invalid");
        assertThat(code(total, Map.of("amount", "10.5", "currency", "JPY")))
                .as("JPY has no cents")
                .isEqualTo("invalid");
        assertThat(code(total, Map.of("amount", "10.25", "currency", "UZS")))
                .as("scale 1")
                .isEqualTo("invalid");
        assertThat(code(total, Map.of("amount", "-1", "currency", "UZS"))).isEqualTo("out_of_range");
        assertThat(code(total, Map.of("amount", "1001", "currency", "UZS"))).isEqualTo("out_of_range");
        assertThat(FieldValueRules.currencyDigits("UZS", -1)).isEqualTo(2);
        assertThat(FieldValueRules.currencyDigits("ZZZ", -1)).isEqualTo(-1);
        assertThat(FieldValueRules.currencyDigits(null, 7)).isEqualTo(7);
    }

    @Test
    void severalReferences() {
        FormField tags = new FormField(
                "tags",
                "l",
                null,
                FieldType.MULTI_REF,
                false,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                QueryRef.paged("/tags", "name"),
                null,
                FormFlags.NONE,
                new FieldParams(null, 2, null, List.of(), List.of(), null, null));

        assertThat(code(tags, List.of(1, "2"))).isNull();
        assertThat(code(tags, "1,2")).isEqualTo("invalid");
        assertThat(code(tags, List.of(0))).isEqualTo("invalid");
        assertThat(code(tags, List.of(1.5))).isEqualTo("invalid");
        assertThat(code(tags, List.of("x"))).isEqualTo("invalid");
        assertThat(code(tags, List.of(1L, 1L))).isEqualTo("invalid");
        assertThat(code(tags, List.of(1, 2, 3))).isEqualTo(FieldValueRules.TOO_MANY);
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(FieldValueRules.keyList(withNull)).isEmpty();
        assertThat(FieldValueRules.keyList(null)).isEmpty();
    }

    @Test
    void filesEnumerationsAndJson() {
        FormField file = field(FieldType.FILE, FieldParams.NONE);
        FormField unit =
                field(FieldType.ENUM, new FieldParams(null, null, null, List.of(), List.of(), "ex.units", null));
        FormField object =
                field(FieldType.JSON, new FieldParams(null, null, null, List.of(), List.of(), null, JsonRoot.OBJECT));
        FormField array =
                field(FieldType.JSON, new FieldParams(null, null, null, List.of(), List.of(), null, JsonRoot.ARRAY));
        FormField any = field(FieldType.JSON, FieldParams.NONE);

        assertThat(code(file, Map.of("id", FILE_ID, "name", "a.pdf"))).isNull();
        assertThat(code(file, Map.of("name", "a.pdf"))).isEqualTo("invalid");
        assertThat(FieldValueRules.fileId(null)).isEmpty();
        assertThat(FieldValueRules.fileId(" " + FILE_ID.toUpperCase() + " ")).contains(UUID.fromString(FILE_ID));
        assertThat(code(unit, "kg")).isNull();
        assertThat(code(unit, " ")).isEqualTo("invalid");
        assertThat(code(object, "{\"a\": 1}")).isNull();
        assertThat(code(object, "{oops")).isEqualTo("invalid");
        assertThat(code(object, List.of(1))).isEqualTo("invalid");
        assertThat(code(array, List.of(1))).isNull();
        assertThat(code(array, Map.of("a", 1))).isEqualTo("invalid");
        assertThat(code(any, "\"text\"")).isEqualTo("invalid");
        assertThat(code(any, Map.of("big", "x".repeat(FieldValueRules.MAX_JSON_BYTES))))
                .isEqualTo(FieldValueRules.TOO_LARGE);
        assertThat(code(field(FieldType.DATE, FieldParams.NONE), "2026-10-01"))
                .as("an older type has none here")
                .isNull();
    }

    @Test
    void aReadOnlyValueIsTheSameWhenItMeansTheSame() {
        FormField file = field(FieldType.FILE, FieldParams.NONE);
        FormField tags = field(FieldType.MULTI_REF, FieldParams.NONE);
        FormField money = field(FieldType.MONEY, FieldParams.NONE);
        FormField number = field(FieldType.NUMBER, FieldParams.NONE);
        FormField json = field(FieldType.JSON, FieldParams.NONE);
        FormField email = field(FieldType.EMAIL, FieldParams.NONE);

        assertThat(FieldValueRules.same(file, FILE_ID, Map.of("id", FILE_ID))).isTrue();
        assertThat(FieldValueRules.same(tags, List.of("3", 9), List.of(3L, 9L))).isTrue();
        assertThat(FieldValueRules.same(
                        money, Map.of("amount", "10", "currency", "UZS"), Map.of("amount", "10.00", "currency", "UZS")))
                .isTrue();
        assertThat(FieldValueRules.same(
                        money, Map.of("amount", "10", "currency", "USD"), Map.of("amount", "10.00", "currency", "UZS")))
                .isFalse();
        assertThat(FieldValueRules.same(money, "10", Map.of("amount", "10"))).isFalse();
        assertThat(FieldValueRules.same(number, "12.50", new BigDecimal("12.5")))
                .isTrue();
        assertThat(FieldValueRules.same(number, "x", "x"))
                .as("text that is no number")
                .isTrue();
        assertThat(FieldValueRules.same(json, "{\"a\":1}", Map.of("a", 1))).isTrue();
        assertThat(FieldValueRules.same(json, "{oops", "{oops")).isTrue();
        assertThat(FieldValueRules.same(email, "Ann@X.uz", "ann@x.uz")).isTrue();
        assertThat(FieldValueRules.same(email, " ", null)).isTrue();
        assertThat(FieldValueRules.same(email, "a@x.uz", null)).isFalse();
    }

    @Test
    void referencesAreReadOnceAndForgottenOnChange() {
        JdbcClient jdbc = mock(JdbcClient.class, Answers.RETURNS_DEEP_STUBS);
        CacheManager caches = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(caches.getCache(EntityEnums.CACHE)).thenReturn(cache);
        when(cache.get(anyString(), any(Callable.class)))
                .thenReturn(EntityEnums.Items.active(Map.of("kg", "Kilogram")));
        List<EntityDefinition> entities = List.of(FieldTypesFixture.REFERENCE, FieldTypesFixture.DEFINITION);
        EntityEnums enums = new EntityEnums(entities, jdbc, caches);

        assertThat(enums.isReference(FieldTypesFixture.UNITS)).isTrue();
        assertThat(enums.isReference(FieldTypesFixture.CODE)).isFalse();
        assertThat(enums.items("test.unknown")).isEmpty();
        assertThat(enums.name(FieldTypesFixture.UNITS, "kg")).contains("Kilogram");
        assertThat(enums.name(FieldTypesFixture.UNITS, "pc")).isEmpty();
        enums.evict(FieldTypesFixture.UNITS);
        verify(cache).evict(FieldTypesFixture.UNITS);

        EntityEnums local = new EntityEnums(entities, jdbc, (CacheManager) null);
        local.evict(FieldTypesFixture.UNITS);
        assertThat(local.isReference(FieldTypesFixture.UNITS)).isTrue();
    }

    @Test
    void aFieldResolverLeavesOtherListsAlone() {
        EntityEnums enums = mock(EntityEnums.class);
        when(enums.items(FieldTypesFixture.UNITS)).thenReturn(Map.of("kg", "Kilogram"));
        EntityEnumResolver resolver =
                new EntityEnumResolver(List.of(FieldTypesFixture.REFERENCE, FieldTypesFixture.DEFINITION), enums);
        var list = EntityLists.queryList(FieldTypesFixture.DEFINITION);
        var units = EntityLists.queryList(FieldTypesFixture.REFERENCE);

        assertThat(resolver.resolve(list, list.field("unit").orElseThrow()).enumValues())
                .containsExactly("kg");
        assertThat(resolver.resolve(list, list.field("kind").orElseThrow()))
                .isSameAs(list.field("kind").orElseThrow());
        assertThat(resolver.resolve(units, list.field("unit").orElseThrow()).enumValues())
                .isEmpty();
    }

    private static @Nullable String code(FormField field, Object value) {
        Optional<FieldErrorItem> problem = FieldValueRules.problem(field, value);
        return problem.map(FieldErrorItem::code).orElse(null);
    }

    private static FormField field(FieldType type, FieldParams params) {
        return new FormField(
                type.wire().replace("_", ""),
                "l",
                null,
                type,
                false,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                type == FieldType.MULTI_REF ? QueryRef.paged("/x", "n") : null,
                null,
                FormFlags.NONE,
                params);
    }
}
