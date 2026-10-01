package com.smartup24.cms.instance.md;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.field.EntityFields;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.md.service.MdCustomFieldFormFields;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.1, acceptance "0 parallel field declarations" (ADR-0032, 3.5): an entity's fields are declared
 * once, as {@code EntityField}s of its model, and both its form fields and its list are derived from them. A module
 * cannot build an entity by hand, nor declare the list of an entity as a second, hand-written {@link QueryList}.
 * Lists that are not entities (the audit, security events, files, upload packages) stay plain {@code QueryList}s;
 * the administrator's custom fields are data, read at request time (ADR-0019, 2.3).
 */
class EntityFieldsSingleSourceTest {

    private static final String ROOT = "com.smartup24.cms.instance";
    private static final String COMMON_ENTITY = ROOT + ".common.entity";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    /** Every declared entity with a list has its model, its form fields are the model's, its list derived. */
    @Test
    void everyEntityListIsDerivedFromItsFields() throws Exception {
        List<EntityDefinition> entities = EntityActionPermissionContractTest.declaredEntities();
        assertThat(entities).extracting(EntityDefinition::code).contains("ms.notes");

        List<String> problems = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            EntityModel model = entity.model();
            if (model == null) {
                if (entity.listCode() != null) problems.add(entity.code() + ": a list without its fields");
                continue;
            }
            if (!entity.fields().equals(model.formFields())) {
                problems.add(entity.code() + ": form fields other than its model's");
            }
            QueryList list = EntityLists.queryList(entity);
            // After the declared fields only what the platform adds: the archived flag (ADR-0032, 5.4).
            List<QueryField> derived = new ArrayList<>(model.listFields());
            derived.addAll(EntityLists.platformFields(entity));
            if (!list.fields().equals(derived)) {
                problems.add(entity.code() + ": list fields other than its model's");
            }
        }
        assertThat(problems).isEmpty();
    }

    /** No {@code @Bean QueryList} has the code of an entity or of an entity's list: that would be a second declaration. */
    @Test
    void noDeclaredListRepeatsAnEntity() throws Exception {
        List<EntityDefinition> entities = EntityActionPermissionContractTest.declaredEntities();
        Set<String> taken = entities.stream()
                .flatMap(entity -> Stream.of(entity.code(), entity.listCode()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        assertThat(EntityFieldContractTest.declaredLists())
                .extracting(QueryList::code)
                .doesNotContainAnyElementsOf(taken);
        // The registry refuses it at start as well.
        List<QueryList> again = List.of(EntityLists.queryList(entities.stream()
                .filter(entity -> entity.code().equals("ms.notes"))
                .findFirst()
                .orElseThrow()));
        assertThatThrownBy(() -> new QueryListRegistry(again, List.of(new EntityLists(entities)), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared once");
    }

    /** An entity is declared with {@link Entity#define}, so its form fields can only come from its model. */
    @Test
    void entitiesAreBuiltOnlyByTheDeclarationBuilder() {
        noClasses()
                .that()
                .resideOutsideOfPackage(COMMON_ENTITY + "..")
                .should()
                .callConstructorWhere(DescribedPredicate.describe(
                        "of EntityDefinition", call -> call.getTargetOwner().isEquivalentTo(EntityDefinition.class)))
                .because("an entity's fields are declared once, as EntityField (plan 10/10, item 5.1)")
                .check(classes);
    }

    /**
     * A form field is made from an entity field, in {@code common.entity}; outside it only for the administrator's
     * custom fields, which are data and have no list declaration in code.
     */
    @Test
    void formFieldsAreDerivedNotDeclared() {
        noClasses()
                .that()
                .resideOutsideOfPackage(COMMON_ENTITY + "..")
                .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(MdCustomFieldFormFields.class)))
                .should()
                .callConstructorWhere(DescribedPredicate.describe(
                        "of FormField", call -> call.getTargetOwner().isEquivalentTo(FormField.class)))
                .orShould(callFormFieldFactories())
                .because("an entity's form fields are derived from its EntityField declaration (plan 10/10, item 5.1)")
                .check(classes);
    }

    /** A class that declares an entity declares no list fields: the entity's list comes from its fields. */
    @Test
    void entityDeclarationsDeclareNoListFields() {
        List<String> problems = new ArrayList<>();
        for (JavaClass type : classes) {
            boolean declaresEntity = type.getMethodCallsFromSelf().stream()
                    .anyMatch(call -> call.getTargetOwner().isEquivalentTo(Entity.class)
                            && call.getName().equals("define"));
            if (!declaresEntity) continue;
            type.getMethodCallsFromSelf().stream()
                    .filter(call -> call.getTargetOwner().isEquivalentTo(QueryField.class))
                    .forEach(call -> problems.add(type.getName() + " calls QueryField." + call.getName()));
            type.getConstructorCallsFromSelf().stream()
                    .filter(call -> call.getTargetOwner().isEquivalentTo(QueryList.class)
                            || call.getTargetOwner().isEquivalentTo(QueryField.class))
                    .forEach(call -> problems.add(
                            type.getName() + " builds " + call.getTargetOwner().getSimpleName()));
        }
        assertThat(problems).isEmpty();
    }

    /** The guard itself: a hand-built list beside an entity is refused. */
    @Test
    void theBuilderRefusesAListFieldWithoutATable() {
        assertThatThrownBy(() -> Entity.define("x.items", "x")
                        .field(EntityFields.text("name", "x.col.name").column("name"))
                        .section("main", "m", "name")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table");
    }

    private static ArchCondition<JavaClass> callFormFieldFactories() {
        return new ArchCondition<>("call the factories of FormField") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                    if (call.getTargetOwner().isEquivalentTo(FormField.class)
                            && Set.of("of", "select").contains(call.getName())) {
                        events.add(SimpleConditionEvent.violated(type, call.getDescription()));
                    }
                }
            }
        };
    }
}
