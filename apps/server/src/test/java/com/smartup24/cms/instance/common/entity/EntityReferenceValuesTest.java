package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.store.EntityWrite;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Single reference ids keep their exact long value, while reference codes remain text (ADR-0032, 4.2). */
class EntityReferenceValuesTest {

    private static final EntityDefinition CODE_REFERENCE = Entity.define("test.ref_codes", "test_field_types")
            .table("test_field_types", "t")
            .scope(EntityScope.all())
            .field(ref("region", "test.col.region", new QueryRef("/regions", "name", "code", false))
                    .column("region")
                    .list(sortable()))
            .section("main", "entity.section.main", "region")
            .defaultSort("region", Entity.Sort.ASC)
            .build();
    private static final JsonColumns JSON = new JsonColumns(JsonMapper.shared(), "test_field_types");

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void aNumericReferenceMustBeAnExactLong(Object value) {
        assertThat(EntityValidator.problems(
                        FieldTypesFixture.DEFINITION, Map.of("kind", "wholesale", "ownerId", value), true))
                .extracting(item -> item.field() + ":" + item.code() + ":" + item.messageKey())
                .containsExactly("ownerId:invalid:error.field.ref_invalid");
    }

    static Stream<Object> invalidReferences() {
        return Stream.of(
                new BigInteger("18446744073709551617"),
                new BigDecimal("18446744073709551617"),
                new BigInteger("-18446744073709551615"),
                new BigDecimal("1.5"),
                Double.NaN,
                Double.POSITIVE_INFINITY,
                "9223372036854775808",
                "18446744073709551617",
                "-9223372036854775809",
                "+9223372036854775808",
                "north");
    }

    @Test
    void aWriteNeverTruncatesANumericReference() {
        for (Object value : List.of(
                new BigInteger("18446744073709551617"),
                new BigDecimal("-18446744073709551615"),
                new BigDecimal("1.5"))) {
            assertThatThrownBy(() -> EntityWrite.of(FieldTypesFixture.DEFINITION, Map.of("ownerId", value), JSON))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Test
    void exactLongBoundariesAndSignedIdsKeepTheirMeaning() {
        Map<Object, Object> samples = Map.of(
                Long.MIN_VALUE,
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                "9223372036854775807",
                Long.MAX_VALUE,
                new BigInteger("9223372036854775807"),
                Long.MAX_VALUE,
                new BigDecimal("7.0"),
                7L,
                " 007 ",
                7L,
                "-9223372036854775808",
                Long.MIN_VALUE,
                "+9223372036854775807",
                Long.MAX_VALUE,
                "-1",
                -1L,
                "+7",
                7L);
        samples.forEach((value, expected) -> {
            assertThat(EntityValidator.problems(
                            FieldTypesFixture.DEFINITION, Map.of("kind", "wholesale", "ownerId", value), true))
                    .isEmpty();
            assertThat(EntityWrite.of(FieldTypesFixture.DEFINITION, Map.of("ownerId", value), JSON)
                            .columns())
                    .singleElement()
                    .satisfies(column -> assertThat(column.value()).isEqualTo(expected));
        });
    }

    @Test
    void textCodesRemainValidOnlyForACodeBasedReference() {
        assertThat(EntityValidator.problems(CODE_REFERENCE, Map.of("region", " north "), true))
                .isEmpty();
        assertThat(EntityWrite.of(CODE_REFERENCE, Map.of("region", " north "), JSON)
                        .columns())
                .singleElement()
                .satisfies(column -> assertThat(column.value()).isEqualTo("north"));
    }

    @Test
    void aWriteNeverStoresANonIdStringInAnIdColumn() {
        for (String value : List.of("-9223372036854775809", "+9223372036854775808", "north")) {
            assertThatThrownBy(() -> EntityWrite.of(FieldTypesFixture.DEFINITION, Map.of("ownerId", value), JSON))
                    .isInstanceOf(NumberFormatException.class);
        }
    }

    @Test
    void multipleReferencesStillRefuseOversizedAndFractionalKeys() {
        for (Object value : List.of(new BigInteger("18446744073709551617"), "18446744073709551617", 1.5)) {
            assertThat(EntityValidator.problems(FieldTypesFixture.DEFINITION, Map.of("tagIds", List.of(value)), true))
                    .extracting(item -> item.field() + ":" + item.code())
                    .containsExactly("tagIds:invalid");
        }
    }
}
