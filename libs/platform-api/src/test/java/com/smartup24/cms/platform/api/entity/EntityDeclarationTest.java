package com.smartup24.cms.platform.api.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.date;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.hidden;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.markdown;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.money;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.searchable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.textarea;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import com.smartup24.cms.platform.api.entity.hook.Rules;
import com.smartup24.cms.platform.api.entity.importing.EntityImportSpec;
import com.smartup24.cms.platform.api.entity.search.EntitySearchSpec;
import com.smartup24.cms.platform.api.entity.workflow.EntityWorkflow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The declaration API alone, as a module outside the monorepo uses it (ADR-0033, 3.1): a document with lines, a process,
 * tabs, an import key and a search is built, and what it derives — form fields, list keys, actions, rights — reads back.
 */
class EntityDeclarationTest {

    private static final List<String> CURRENCIES = List.of("UZS", "USD");

    static EntityDefinition orders() {
        return Entity.define("acme.orders", "acme.orders")
                .table("acme_orders", "o")
                .scope(EntityScope.orgUnit("org_unit_id", "created_by"))
                .rights(
                        "acme",
                        "acme.orders.rights.form",
                        Map.of(
                                "view", "acme.orders.rights.view",
                                "create", "acme.orders.rights.create",
                                "update", "acme.orders.rights.update",
                                "post", "acme.orders.rights.post",
                                "import", "acme.orders.rights.import"))
                .menu(new EntityMenu("nav.acme_orders", "receipt", "workspace", 10, "acme"))
                .field(text("number", "acme.col.number")
                        .column("number")
                        .defaultValue(FieldDefault.sequence("acme_orders_number_seq", "ORD-{000000}"))
                        .list(sortable().searchable()))
                .field(date("orderDate", "acme.col.date")
                        .column("order_date")
                        .required()
                        .defaultValue(FieldDefault.today())
                        .list(sortable()))
                .field(text("customer", "acme.col.customer")
                        .column("customer")
                        .required()
                        .length(1, 255)
                        .list(sortable().searchable()))
                .field(select("currency", "acme.col.currency", CURRENCIES, null)
                        .column("currency")
                        .required()
                        .defaultValue(FieldDefault.fixed("UZS")))
                .field(select("status", "acme.col.status", List.of("draft", "posted"), "acme.status.")
                        .column("status")
                        .readonly()
                        .defaultValue(FieldDefault.fixed("draft")))
                .field(money("total", "acme.col.total", "UZS", "USD")
                        .computed("(select 0)")
                        .currencyFrom("currency")
                        .list(sortable()))
                .field(ref("orgUnitId", "acme.col.unit", QueryRef.whole("/iam/org-units", "name"))
                        .column("org_unit_id")
                        .defaultValue(FieldDefault.currentOrgUnit())
                        .list(hidden()))
                .field(textarea("comment", "acme.col.comment")
                        .column("comment")
                        .length(null, 2000)
                        .list(searchable().hidden()))
                .field(instant("modifiedAt", "acme.col.modified_at")
                        .system(SystemColumn.MODIFIED_AT)
                        .list(sortable().hidden()))
                .collection(EntityCollection.of("lines", "acme.lines")
                        .table("acme_order_lines", "l")
                        .parentColumn("order_id")
                        .positionColumn("position")
                        .field(text("product", "acme.line.product")
                                .column("product")
                                .required()
                                .length(1, 255))
                        .field(number("qty", "acme.line.qty")
                                .column("qty")
                                .required()
                                .scale(3)
                                .range(new BigDecimal("0.001"), new BigDecimal("1000000")))
                        .maxRows(EntityCollection.DEFAULT_MAX_ROWS)
                        .build())
                .section(
                        "main", "entity.section.main", "number", "orderDate", "customer", "currency", "status", "total")
                .section("settings", "entity.section.settings", "orgUnitId", "comment")
                .actions("create", "update")
                .workflow(EntityWorkflow.on("status")
                        .state("draft", "acme.status.draft")
                        .initial()
                        .state("posted", "acme.status.posted")
                        .locks("orderDate", "customer", "lines")
                        .terminal()
                        .transition("post", "draft", "posted")
                        .rule(Rules.hasRows("lines"))
                        .build())
                .tab(EntityTab.sections("main", "ui.tab_fields", "main", "settings"))
                .tab(EntityTab.collection("lines", "acme.lines", "lines"))
                .tab(EntityTab.history("history", "ui.tab_history"))
                .importKey("number")
                .search(EntitySearchSpec.title("number").body("customer", "comment"))
                .defaultSort("number", Entity.Sort.DESC)
                .auditTable("acme_orders")
                .capabilities(EntityCapability.SAVED_VIEWS, EntityCapability.EXPORT, EntityCapability.HISTORY)
                .build();
    }

    @Test
    void aDocumentDeclarationDerivesItsFormListActionsAndRights() {
        EntityDefinition orders = orders();
        EntityModel model = orders.model();

        assertThat(orders.code()).isEqualTo("acme.orders");
        assertThat(orders.listCode()).isEqualTo("acme.orders");
        assertThat(model).isNotNull();
        assertThat(model.table()).isEqualTo("acme_orders");
        assertThat(model.scope().describe()).isEqualTo("orgUnit(org_unit_id, created_by)");
        assertThat(model.scope().columns()).containsExactly("org_unit_id", "created_by");
        assertThat(orders.fields()).extracting(FormField::key).contains("number", "customer", "total");
        assertThat(model.formFields()).isEqualTo(orders.fields());
        assertThat(model.field("total").orElseThrow().listKeys()).containsExactly("total", "totalCurrency");
        assertThat(model.field("comment").orElseThrow().listKeys()).containsExactly("comment");
        assertThat(model.collection("lines")).isPresent();
        assertThat(model.workflow()).isNotNull();
        assertThat(model.tabs()).hasSize(3);
        assertThat(model.importing()).isNotNull();
        assertThat(model.search()).isNotNull();
        assertThat(orders.capabilities())
                .contains(EntityCapability.IMPORT, EntityCapability.SEARCH, EntityCapability.HISTORY);
        assertThat(orders.action("post"))
                .hasValueSatisfying(
                        action -> assertThat(action.kind()).isEqualTo(EntityDefinition.EntityAction.Kind.TRANSITION));
        assertThat(orders.rights()).isNotNull();
        assertThat(orders.rights().keys()).first().isEqualTo("acme.orders.rights.form");
        assertThat(orders.menu()).isNotNull();
        assertThat(orders.menu().routeFor(orders.code())).isEqualTo("/e/acme.orders");
        assertThat(orders.fieldsByKey()).containsKey("currency");
        assertThat(model.sqlOf("customer")).isEqualTo("o.customer");
        assertThatThrownBy(() -> model.sqlOf("nothing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeclarationThatMissesWhatItNeedsIsRefused() {
        assertThatThrownBy(() -> Entity.define("acme.items", "acme.items")
                        .table("acme_items", "i")
                        .field(text("title", "x").column("title").list(sortable()))
                        .section("main", "entity.section.main", "title")
                        .defaultSort("title", Entity.Sort.ASC)
                        .build())
                .as("a table declares its scope")
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Entity.define("acme.items", "acme.items")
                        .table("acme_items", "i")
                        .scope(EntityScope.all())
                        .field(text("title", "x").column("title").list(sortable()))
                        .field(text("title", "y").column("title2"))
                        .section("main", "entity.section.main", "title")
                        .defaultSort("title", Entity.Sort.ASC)
                        .build())
                .as("a key is declared once")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityModel("bad table", "t", List.of(), "x", false, EntityScope.all()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** ADR-0032, 4.5: the items of a reference are read whole for every viewer, so its rows are every viewer's. */
    @Test
    void aReferenceIsSeenWhole() {
        assertThatThrownBy(() -> Entity.define("acme.kinds", "acme.kinds")
                        .table("acme_kinds", "k")
                        .scope(EntityScope.owner("created_by"))
                        .field(text("code", "x").column("code").list(sortable()))
                        .field(text("name", "y").column("name"))
                        .section("main", "entity.section.main", "code", "name")
                        .reference("code", "name")
                        .defaultSort("code", Entity.Sort.ASC)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EntityScope.all()");
        EntityDefinition all = Entity.define("acme.kinds", "acme.kinds")
                .table("acme_kinds", "k")
                .scope(EntityScope.all())
                .field(text("code", "x").column("code").list(sortable()))
                .field(text("name", "y").column("name"))
                .section("main", "entity.section.main", "code", "name")
                .reference("code", "name")
                .defaultSort("code", Entity.Sort.ASC)
                .build();
        assertThat(all.model().reference()).isNotNull();
    }

    @Test
    void fieldsKnowTheirSourceAndTheirSql() {
        EntityField title =
                text("title", "x").column("title").required().list(sortable()).build();
        EntityField region =
                text("region", "x").attribute("region").list(searchable()).build();
        EntityField pinned = bool("pinned", "x").column("is_pinned").build();
        EntityField body =
                markdown("body", "x").column("body").list(searchable()).build();
        EntityField changed = instant("modifiedAt", "x")
                .system(SystemColumn.MODIFIED_AT)
                .list(sortable())
                .build();

        assertThat(title.sql("t")).isEqualTo("t.title");
        assertThat(title.formField()).isNotNull();
        assertThat(title.formField().required()).isTrue();
        assertThat(region.attribute()).isEqualTo("region");
        assertThat(region.sql("t")).isEqualTo("(t.attributes->>'region')");
        assertThat(pinned.listKeys()).containsExactly("pinned");
        assertThat(body.type()).isEqualTo(FieldType.MARKDOWN);
        assertThat(changed.source()).isEqualTo(new FieldSource.SystemValue(SystemColumn.MODIFIED_AT));
        assertThat(SystemColumn.MODIFIED_AT.column()).isEqualTo("modified_at");
        assertThat(FieldType.MULTI_REF.sortable()).isFalse();
        assertThat(FieldType.MONEY.scalar()).isFalse();
        assertThat(FieldType.EMAIL.formatted()).isTrue();
        assertThat(FieldType.DATETIME.wire()).isEqualTo("datetime");
        assertThatThrownBy(() -> text("bad_key", "x").column("c").build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopesAndTheirConditions() {
        assertThat(EntityScope.owner("created_by").describe()).isEqualTo("owner(created_by)");
        assertThat(EntityScope.all().describe()).isEqualTo("all");
        assertThat(EntityScope.all().columns()).isEmpty();
        EntityScope custom = EntityScope.custom(
                "tasks", (user, alias) -> EntityScope.Condition.of(" and " + alias + ".owner_id = :scopeUserId", user));
        assertThat(custom.describe()).isEqualTo("custom(tasks)");
        EntityScope.Condition condition =
                ((EntityScope.Custom) custom).provider().filter(7L, "t");
        assertThat(condition.sql()).isEqualTo(" and t.owner_id = :scopeUserId");
        assertThat(condition.bindsUserId()).isTrue();
        assertThat(condition.userId()).isEqualTo(7L);
        assertThat(EntityScope.Condition.unrestricted().sql()).isEmpty();
        assertThatThrownBy(() -> new EntityScope.Condition("or 1 = 1", false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityScope.Condition(" and x = :scopeUserId", true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityScope.custom("Bad", (user, alias) -> EntityScope.Condition.unrestricted()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityScope.owner("bad column")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referencesNameTheirListAndEndpoint() {
        QueryRef users = QueryRef.paged("/iam/users", "name");
        assertThat(users.paged()).isTrue();
        assertThat(users.keyField()).isEqualTo("id");
        assertThat(users.readBy("/iam/users/page").readPath()).isEqualTo("/iam/users/page");
        assertThat(QueryRef.entityPath("md.users")).isEqualTo("/entities/md.users");
        assertThatThrownBy(() -> QueryRef.whole("no slash", "name")).isInstanceOf(IllegalArgumentException.class);
        assertThat(EntityImportSpec.class).isNotNull();
    }
}
