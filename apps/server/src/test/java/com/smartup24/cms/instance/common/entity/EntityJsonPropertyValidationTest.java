package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.EntityFields;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntityJsonPropertyValidationTest {

    @Test
    void jsonPropertiesUseNormalRequiredAndScaleValidation() {
        EntityFields.Builder value = number("amount", "test.amount")
                .jsonProperty("payload", "amount")
                .required()
                .scale(2);
        EntityDefinition entity =
                base().field(value).section("values", "test.values", "amount").build();
        assertThat(entity.fieldsByKey().get("amount").attribute()).isNull();
        assertThat(EntityValidator.problems(entity, Map.of("name", "A"), false))
                .extracting(problem -> problem.field())
                .contains("amount");
        assertThat(EntityValidator.problems(entity, Map.of("name", "A", "amount", "1.234"), false))
                .isNotEmpty();
        assertThat(entity.model().sqlOf("amount")).contains("payload", "amount", "numeric");
    }

    private static Entity base() {
        return Entity.define("test.items", "test.items")
                .table("test_items", "t")
                .scope(EntityScope.all())
                .field(text("name", "test.name").column("name").required())
                .section("main", "test.main", "name")
                .defaultSort("name", Entity.Sort.ASC);
    }
}
