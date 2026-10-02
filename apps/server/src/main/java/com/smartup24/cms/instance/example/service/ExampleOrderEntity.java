package com.smartup24.cms.instance.example.service;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.date;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.hidden;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.money;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.number;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.ref;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.searchable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.textarea;

import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.EntityTab;
import com.smartup24.cms.instance.common.entity.collection.EntityCollection;
import com.smartup24.cms.instance.common.entity.field.FieldDefault;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.entity.hook.Rules;
import com.smartup24.cms.instance.common.entity.search.EntitySearchSpec;
import com.smartup24.cms.instance.common.entity.workflow.EntityWorkflow;
import com.smartup24.cms.instance.common.query.QueryRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The reference document of the low-code platform (ADR-0032, 9.4; plan 10/10, item 5.7) — the whole server side of
 * orders: one declaration, no hooks, no controller and no screen. The general runtime serves the records at
 * {@code /api/v1/entities/example.orders} with their lines, which it reads with the order, checks line by line
 * ({@code lines[3].qty}) and saves in the order's transaction; the process draft → posted → cancelled runs as record
 * actions with a right each ({@code post}, {@code unpost}, {@code cancel}); a posted order keeps its customer, date,
 * currency, unit and lines, a cancelled one is only read. The general screen {@code /e/example.orders} draws the form
 * with the lines, the card with its tabs and the buttons of the transitions from {@code form-meta} and the record's
 * {@code actions}.
 *
 * <p>No accumulation registers: posting moves the status, and a module that keeps balances does it in a hook of the
 * transition (ADR-0032, 19, question 2). An order is in the org unit of its author by default (ADR-0013).
 */
@Configuration
public class ExampleOrderEntity {

    /** The entity's code and its list's. */
    public static final String CODE = "example.orders";

    /** The record property of an order's lines. */
    public static final String LINES = "lines";

    public static final List<String> CURRENCIES = List.of("UZS", "USD", "EUR");

    public static final List<String> STATUSES = List.of("draft", "posted", "cancelled");

    /** The smallest quantity of a line: a thousandth of a unit. */
    private static final BigDecimal THOUSANDTH = new BigDecimal("0.001");

    private static final String TOTAL =
            "(select coalesce(round(sum(l.qty * l.price), 2), 0)" + " from ex_order_lines l where l.order_id = o.id)";

    public static final EntityDefinition DEFINITION = Entity.define(CODE, CODE)
            .table("ex_orders", "o")
            .scope(EntityScope.orgUnit("org_unit_id", "created_by"))
            .rights(
                    "example",
                    "example.orders.rights.form",
                    Map.of(
                            "view", "example.orders.rights.view",
                            "create", "example.orders.rights.create",
                            "update", "example.orders.rights.update",
                            "post", "example.orders.rights.post",
                            "unpost", "example.orders.rights.unpost",
                            "cancel", "example.orders.rights.cancel"))
            .menu(new EntityMenu("nav.example_orders", "receipt_long", "workspace", 90, "example"))
            .field(text("number", "example.orders.col.number")
                    .column("number")
                    .defaultValue(FieldDefault.sequence("ex_orders_number_seq", "ORD-{000000}"))
                    .list(sortable().searchable()))
            .field(date("orderDate", "example.orders.col.order_date")
                    .column("order_date")
                    .required()
                    .defaultValue(FieldDefault.today())
                    .list(sortable()))
            .field(text("customer", "example.orders.col.customer")
                    .column("customer")
                    .required()
                    .length(1, 255)
                    .list(sortable().searchable()))
            .field(select("currency", "example.orders.col.currency", CURRENCIES, null)
                    .column("currency")
                    .required()
                    .defaultValue(FieldDefault.fixed("UZS")))
            .field(select("status", "example.orders.col.status", STATUSES, "example.orders.status.")
                    .column("status")
                    .readonly()
                    .defaultValue(FieldDefault.fixed("draft")))
            .field(money("total", "example.orders.col.total", "UZS", "USD", "EUR")
                    .computed(TOTAL)
                    .currencyFrom("currency")
                    .list(sortable()))
            .field(ref("orgUnitId", "example.orders.col.org_unit", QueryRef.whole("/iam/org-units", "name"))
                    .column("org_unit_id")
                    .defaultValue(FieldDefault.currentOrgUnit())
                    .list(hidden()))
            .field(textarea("comment", "example.orders.col.comment")
                    .column("comment")
                    .length(null, 2000)
                    .list(searchable().hidden()))
            .field(instant("modifiedAt", "example.orders.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable().hidden()))
            .collection(EntityCollection.of(LINES, "example.orders.lines")
                    .table("ex_order_lines", "l")
                    .parentColumn("order_id")
                    .positionColumn("position")
                    .field(text("product", "example.orders.line.product")
                            .column("product")
                            .required()
                            .length(1, 255))
                    .field(number("qty", "example.orders.line.qty")
                            .column("qty")
                            .required()
                            .scale(3)
                            .range(THOUSANDTH, new BigDecimal("1000000")))
                    .field(money("price", "example.orders.line.price", "UZS", "USD", "EUR")
                            .money("price", null)
                            .currencyFrom("currency")
                            .required()
                            .range(BigDecimal.ZERO, null))
                    .field(money("amount", "example.orders.line.amount", "UZS", "USD", "EUR")
                            .computed("round(l.qty * l.price, 2)")
                            .currencyFrom("currency"))
                    .maxRows(EntityCollection.DEFAULT_MAX_ROWS)
                    .build())
            .section("main", "entity.section.main", "number", "orderDate", "customer", "currency", "status", "total")
            .section("settings", "entity.section.settings", "orgUnitId", "comment")
            .actions("create", "update")
            .workflow(EntityWorkflow.on("status")
                    .state("draft", "example.orders.status.draft")
                    .initial()
                    .state("posted", "example.orders.status.posted")
                    .locks("orderDate", "customer", "currency", "orgUnitId", LINES)
                    .state("cancelled", "example.orders.status.cancelled")
                    .terminal()
                    .transition("post", "draft", "posted")
                    .rule(Rules.hasRows(LINES))
                    .transition("unpost", "posted", "draft")
                    .transition("cancel", "draft", "cancelled")
                    .confirm("example.orders.cancel_confirm")
                    .build())
            .tab(EntityTab.sections("main", "ui.entity_page.tab_fields", "main", "settings"))
            .tab(EntityTab.collection(LINES, "example.orders.lines", LINES))
            .tab(EntityTab.history("history", "ui.entity_page.tab_history"))
            // Found by the global search in the org units of the viewer's scope (ADR-0013; ADR-0032, 10.3).
            .search(EntitySearchSpec.title("number").body("customer", "comment"))
            .defaultSort("number", Entity.Sort.DESC)
            .capabilities(
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.EXPORT,
                    EntityCapability.HISTORY,
                    EntityCapability.BULK)
            .build();

    @Bean
    public EntityDefinition exampleOrdersEntity() {
        return DEFINITION;
    }
}
