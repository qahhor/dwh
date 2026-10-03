package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.money;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityEnums.Items;
import com.smartup24.cms.instance.common.entity.FormDocumentMetas.FormTabMeta;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.EntityTab;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import com.smartup24.cms.platform.api.entity.hook.EntityActionCall;
import com.smartup24.cms.platform.api.entity.hook.EntityActionHandler;
import com.smartup24.cms.platform.api.entity.hook.Rules;
import com.smartup24.cms.platform.api.entity.workflow.EntityWorkflow;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The declaration of a document (ADR-0032, 9; plan 10/10, item 5.7): what a collection, a process and the tabs of a
 * card refuse when an entity is declared, and what {@code form-meta} gives of them.
 */
class EntityDocumentModelTest {

    private static final List<String> CURRENCIES = List.of("UZS", "USD");

    private static final Supplier<EntityRecordStore> NO_STORE = () -> {
        throw new IllegalStateException("no runtime in this test");
    };

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("5.7: a row holds a column, money or a computed value, without default, condition or right")
    void aRowHoldsValuesCheckedWithoutTheDatabase() {
        assertThat(lines(UnaryOperator.identity()).written())
                .extracting(field -> field.key())
                .containsExactly("qty");
        assertThatThrownBy(() -> lines(
                        c -> c.field(ref("productId", "p").column("product_id").target("md.users", "name"))))
                .hasMessageContaining("without the database");
        assertThatThrownBy(() ->
                        lines(c -> c.field(text("note", "n").column("note").defaultValue(FieldDefault.fixed("x")))))
                .hasMessageContaining("no read-only mode, default or condition");
        assertThatThrownBy(() -> lines(c -> c.field(text("cf", "c").attribute("cf"))))
                .hasMessageContaining("lives in a column or is computed");
        assertThatThrownBy(() ->
                        lines(c -> c.field(text("note", "n").column("note").requires("x.y", "view"))))
                .hasMessageContaining("no right of its own");
        assertThatThrownBy(() -> lines(c -> c.field(text("id", "i").column("row_id"))))
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> lines(c -> c.maxRows(501))).hasMessageContaining("between 1 and 500");
        assertThatThrownBy(() -> EntityCollection.of("lines", "l")
                        .table("t_lines", EntityCollection.DOCUMENT_ALIAS)
                        .parentColumn("doc_id")
                        .field(number("qty", "q").column("qty"))
                        .build())
                .hasMessageContaining("the document's");
    }

    @Test
    @DisplayName("5.7: money of a row takes its currency from a select of the document whose options it allows")
    void moneyOfARowTakesTheDocumentsCurrency() {
        EntityCollection priced = lines(c ->
                c.field(money("price", "p", "UZS", "USD").money("price", null).currencyFrom("currency")));
        assertThat(document(entity -> entity.collection(priced)).model().collections())
                .hasSize(1);

        EntityCollection euro = lines(
                c -> c.field(money("price", "p", "UZS").money("price", null).currencyFrom("currency")));
        assertThatThrownBy(() -> document(entity -> entity.collection(euro)))
                .hasMessageContaining("which is no select column of currencies it allows");
        assertThatThrownBy(() -> money("total", "t", "UZS").column("total").build())
                .hasMessageContaining("money lives in a pair of money columns or is computed");
    }

    @Test
    @DisplayName("5.7: a process has one initial state, known states and no transition out of a terminal one")
    void aProcessIsCheckedWhenItIsBuilt() {
        assertThatThrownBy(() -> EntityWorkflow.on("status")
                        .state("a", "a")
                        .state("b", "b")
                        .build())
                .hasMessageContaining("exactly one initial state");
        assertThatThrownBy(() -> EntityWorkflow.on("status")
                        .state("a", "a")
                        .initial()
                        .transition("go", "a", "z")
                        .build())
                .hasMessageContaining("unknown state");
        assertThatThrownBy(() -> EntityWorkflow.on("status")
                        .state("a", "a")
                        .initial()
                        .state("b", "b")
                        .terminal()
                        .transition("back", "b", "a")
                        .build())
                .hasMessageContaining("leaves a terminal state");
        EntityWorkflow process = process();
        assertThat(process.allows("post", "draft")).isTrue();
        assertThat(process.allows("post", "posted")).isFalse();
        assertThat(process.locked("posted", Set.of("title", "qty"))).containsExactly("title");
        assertThat(process.locked("cancelled", Set.of("title", "qty"))).containsExactlyInAnyOrder("title", "qty");
    }

    @Test
    @DisplayName("5.7: the status is a read-only select of the states that starts in the initial one; locks are known")
    void theStatusFieldFitsTheProcess() {
        assertThat(document(entity -> entity.workflow(process())).actions())
                .filteredOn(action -> action.kind() == EntityAction.Kind.TRANSITION)
                .extracting(EntityAction::code)
                .containsExactly("post", "cancel");
        assertThatThrownBy(() -> document(entity -> entity.workflow(EntityWorkflow.on("currency")
                        .state("UZS", "u")
                        .initial()
                        .state("USD", "d")
                        .build())))
                .hasMessageContaining("read-only select column of its states");
        assertThatThrownBy(() -> document(entity -> entity.workflow(EntityWorkflow.on("status")
                        .state("draft", "d")
                        .initial()
                        .locks("nothing")
                        .state("posted", "p")
                        .state("cancelled", "c")
                        .build())))
                .hasMessageContaining("which is no field of the form or collection");
    }

    @Test
    @DisplayName("5.7: a transition needs no handler, and a handler of one fails the start")
    void transitionsHaveNoHandlers() {
        EntityDefinition order = document(entity -> entity.workflow(process()));
        assertThat(new EntityRegistry(List.of(order), List.of(), List.of(), List.of(), List.of(), NO_STORE)
                        .find(order.code()))
                .isPresent();
        EntityActionHandler post = new EntityActionHandler() {
            @Override
            public String entity() {
                return order.code();
            }

            @Override
            public String action() {
                return "post";
            }

            @Override
            public void run(EntityActionCall call) {}
        };
        assertThatThrownBy(() ->
                        new EntityRegistry(List.of(order), List.of(), List.of(), List.of(), List.of(post), NO_STORE))
                .hasMessageContaining("not declared");
    }

    @Test
    @DisplayName("5.7: tabs place every section once, a history tab needs the history, a related list a reference")
    void tabsFitTheForm() {
        assertThatThrownBy(() -> document(entity ->
                        entity.tab(EntityTab.sections("a", "a", "main")).tab(EntityTab.sections("b", "b", "main"))))
                .hasMessageContaining("unknown or repeated section");
        assertThatThrownBy(() -> document(entity -> entity.tab(EntityTab.history("history", "h"))))
                .hasMessageContaining("a history tab needs the history");
        assertThatThrownBy(() -> document(entity -> entity.tab(EntityTab.collection("lines", "l", "lines"))))
                .hasMessageContaining("unknown collection");
        EntityDefinition order = document(entity -> entity.tab(EntityTab.related("others", "o", "test.docs", "title")));
        assertThatThrownBy(() -> new EntityRegistry(List.of(order)))
                .hasMessageContaining("no filterable reference of an entity with a table");
    }

    @Test
    @DisplayName("5.7: form-meta gives the rows, the process and the tabs; a related list only to a viewer of it")
    void formMetaGivesTheDocument() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "ann", "ann@test.local", 1L, false, Set.of("example.orders.view"), 1L, false, 0, null));
        FormMeta meta = FormMetaController.of(ExampleOrderEntity.DEFINITION, code -> Items.NONE, code -> false);
        assertThat(meta.collections()).singleElement().satisfies(lines -> {
            assertThat(lines.key()).isEqualTo("lines");
            assertThat(lines.maxRows()).isEqualTo(500);
            assertThat(lines.fields())
                    .extracting(field -> field.key() + ":" + field.currencyFrom())
                    .containsExactly("product:null", "qty:null", "price:currency", "amount:currency");
        });
        assertThat(meta.workflow()).isNotNull();
        assertThat(meta.workflow().transitions())
                .extracting(transition -> transition.code() + ":" + transition.confirmKey())
                .containsExactly("post:null", "unpost:null", "cancel:example.orders.cancel_confirm");
        assertThat(meta.tabs()).extracting(FormTabMeta::kind).containsExactly("sections", "collection", "history");

        EntityDefinition order = document(entity -> entity.tab(EntityTab.related("others", "o", "test.docs", "title")));
        assertThat(FormMetaController.of(order, code -> Items.NONE, code -> false)
                        .tabs())
                .isEmpty();
        assertThat(FormMetaController.of(order, code -> Items.NONE, "test.docs"::equals)
                        .tabs())
                .extracting(FormTabMeta::entity)
                .containsExactly("test.docs");
        assertThat(FormMetaController.of(MsNoteEntity.DEFINITION, code -> Items.NONE, code -> true)
                        .collections())
                .isNull();
    }

    private static EntityWorkflow process() {
        return EntityWorkflow.on("status")
                .state("draft", "d")
                .initial()
                .state("posted", "p")
                .locks("title")
                .state("cancelled", "c")
                .terminal()
                .transition("post", "draft", "posted")
                .rule(Rules.hasRows("lines"))
                .transition("cancel", "draft", "cancelled")
                .permission("update")
                .build();
    }

    private static EntityCollection lines(UnaryOperator<EntityCollection.Builder> more) {
        return more.apply(EntityCollection.of("lines", "l")
                        .table("t_lines", "l")
                        .parentColumn("doc_id")
                        .field(number("qty", "q").column("qty")))
                .build();
    }

    private static EntityDefinition document(UnaryOperator<Entity> more) {
        return more.apply(Entity.define("test.docs", "test.docs")
                        .table("t_docs", "d")
                        .scope(EntityScope.all())
                        .field(text("title", "t").column("title"))
                        .field(select("currency", "c", CURRENCIES, null).column("currency"))
                        .field(select("status", "s", List.of("draft", "posted", "cancelled"), null)
                                .column("status")
                                .readonly()
                                .defaultValue(FieldDefault.fixed("draft")))
                        .field(ref("parentId", "p", QueryRef.whole("/x", "name"))
                                .column("parent_id"))
                        .section("main", "m", "title", "currency", "status", "parentId")
                        .actions("create", "update")
                        .defaultSort("title", Entity.Sort.ASC))
                .build();
    }
}
