package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.EntityEnums.Items;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormFieldMeta;
import com.smartup24.cms.instance.common.entity.collection.EntityCollection;
import com.smartup24.cms.instance.common.entity.workflow.EntityState;
import com.smartup24.cms.instance.common.entity.workflow.EntityTransition;
import com.smartup24.cms.instance.common.entity.workflow.EntityWorkflow;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The parts of a document's form (ADR-0032, 9; plan 10/10, item 5.7) as {@code form-meta} gives them: the collections
 * with the fields of a row, the process with its states and transitions, and the tabs of the card. An entity without
 * them answers without these properties, so the forms of the entities declared before keep their answer.
 */
public final class FormDocumentMetas {

    /** A collection: its key, title, the fields of a row in order and the most rows a record has. */
    public record FormCollectionMeta(String key, String labelKey, List<FormFieldMeta> fields, int maxRows) {}

    /** A state of the process: what it is called, whether a record starts in it or ends there, what it locks. */
    public record FormStateMeta(String code, String labelKey, boolean initial, boolean terminal, List<String> locks) {}

    /** A transition: the states it leaves, the one it reaches, its right and the question to ask first, if any. */
    public record FormTransitionMeta(
            String code,
            List<String> from,
            String to,
            String permission,
            @Nullable String confirmKey) {}

    /** The process: the key of its status field, its states and its transitions, in declaration order. */
    public record FormWorkflowMeta(String field, List<FormStateMeta> states, List<FormTransitionMeta> transitions) {}

    /**
     * A tab of the card: its kind ({@code sections}, {@code collection}, {@code related}, {@code history}) and what it
     * shows — the sections, the collection, or the entity and its reference field of a related list.
     */
    public record FormTabMeta(
            String key,
            String labelKey,
            String kind,
            @Nullable List<String> sections,
            @Nullable String collection,
            @Nullable String entity,
            @Nullable String field) {}

    private FormDocumentMetas() {}

    /** The collections with the fields of a row; null for an entity without them. */
    static @Nullable List<FormCollectionMeta> collections(EntityDefinition entity, Function<String, Items> enumItems) {
        EntityModel model = entity.model();
        if (model == null || model.collections().isEmpty()) return null;
        return model.collections().stream()
                .map(collection -> new FormCollectionMeta(
                        collection.key(), collection.labelKey(), fields(collection, enumItems), collection.maxRows()))
                .toList();
    }

    private static List<FormFieldMeta> fields(EntityCollection collection, Function<String, Items> enumItems) {
        return collection.formFields().stream()
                .map(field -> FormFieldMetas.of(field, enumItems, false))
                .toList();
    }

    /** The process; null for an entity without one. */
    static @Nullable FormWorkflowMeta workflow(EntityDefinition entity) {
        EntityModel model = entity.model();
        EntityWorkflow workflow = model == null ? null : model.workflow();
        if (workflow == null) return null;
        return new FormWorkflowMeta(
                workflow.field(),
                workflow.states().stream().map(FormDocumentMetas::state).toList(),
                workflow.transitions().stream()
                        .map(FormDocumentMetas::transition)
                        .toList());
    }

    private static FormStateMeta state(EntityState state) {
        return new FormStateMeta(
                state.code(),
                state.labelKey(),
                state.initial(),
                state.terminal(),
                state.locks().stream().sorted().toList());
    }

    private static FormTransitionMeta transition(EntityTransition transition) {
        return new FormTransitionMeta(
                transition.code(),
                transition.from().stream().sorted().toList(),
                transition.to(),
                transition.permission(),
                transition.confirmKey());
    }

    /**
     * The tabs the viewer may open (ADR-0032, 9.3): a related list only for a viewer of its entity; null for an entity
     * whose card has the platform's own tabs.
     *
     * @param viewable whether the viewer may see the entity of this code
     */
    static @Nullable List<FormTabMeta> tabs(EntityDefinition entity, Predicate<String> viewable) {
        EntityModel model = entity.model();
        if (model == null || model.tabs().isEmpty()) return null;
        return model.tabs().stream()
                .filter(tab -> tab.kind() != EntityTab.Kind.RELATED || viewable.test(String.valueOf(tab.entity())))
                .map(tab -> new FormTabMeta(
                        tab.key(),
                        tab.labelKey(),
                        tab.kind().wire(),
                        tab.sections().isEmpty() ? null : tab.sections(),
                        tab.collection(),
                        tab.entity(),
                        tab.field()))
                .toList();
    }
}
