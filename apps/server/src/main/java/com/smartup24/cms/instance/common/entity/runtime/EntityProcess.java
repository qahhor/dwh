package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FieldValueRules;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.collection.EntityCollection;
import com.smartup24.cms.instance.common.entity.hook.EntityRule;
import com.smartup24.cms.instance.common.entity.hook.EntityValues;
import com.smartup24.cms.instance.common.entity.hook.RuleErrors;
import com.smartup24.cms.instance.common.entity.workflow.EntityTransition;
import com.smartup24.cms.instance.common.entity.workflow.EntityWorkflow;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The process of a document as the runtime applies it (ADR-0032, 9.2; plan 10/10, item 5.7): which actions a viewer
 * may take on a record in its state, what a save cannot change in that state, whether a transition leaves it and the
 * rules the record must meet to take it.
 */
public final class EntityProcess {

    private EntityProcess() {}

    /** The transition {@code action} of the entity's process, if the action is one. */
    public static Optional<EntityTransition> transition(EntityDefinition entity, String action) {
        EntityWorkflow workflow = workflow(entity);
        return workflow == null ? Optional.empty() : workflow.transition(action);
    }

    /**
     * The declared actions this viewer may take on this record (ADR-0032, 6.2): their right is held and, with a process,
     * a transition leaves the record's state; in a terminal state the record is only read — no change, no delete.
     */
    public static List<String> actions(EntityDefinition entity, Map<String, ?> record) {
        EntityWorkflow workflow = workflow(entity);
        String state = workflow == null ? null : text(record.get(workflow.field()));
        boolean terminal = workflow != null
                && state != null
                && workflow.state(state).map(found -> found.terminal()).orElse(false);
        List<String> actions = new ArrayList<>();
        for (EntityAction action : entity.actions()) {
            if (!SecurityContext.hasPermission(entity.form(), action.permission())) continue;
            if (action.kind() == EntityAction.Kind.TRANSITION) {
                if (workflow == null || !workflow.allows(action.code(), state)) continue;
            } else if (terminal && ("update".equals(action.code()) || EntityDefinition.DELETE.equals(action.code()))) {
                continue;
            }
            actions.add(action.code());
        }
        return actions;
    }

    /** The fields and collections a save cannot change in the state of {@code record}; none without a process. */
    public static Set<String> locked(EntityDefinition entity, @Nullable Map<String, ?> record) {
        EntityWorkflow workflow = workflow(entity);
        if (workflow == null || record == null) return Set.of();
        Set<String> all = new LinkedHashSet<>();
        entity.fields().forEach(field -> all.add(field.key()));
        Objects.requireNonNull(entity.model()).collections().forEach(collection -> all.add(collection.key()));
        return workflow.locked(text(record.get(workflow.field())), all);
    }

    /**
     * The locked fields a save would change (ADR-0032, 4.4 and 9.2): a value equal to the record's is passed over, so a
     * client may send the whole record; a different one is {@code readonly}.
     */
    public static List<FieldErrorItem> lockProblems(
            EntityDefinition entity, Map<String, ?> values, Map<String, ?> current, Set<String> locked) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (FormField field : entity.fields()) {
            if (!locked.contains(field.key()) || !values.containsKey(field.key())) continue;
            if (!FieldValueRules.same(field, values.get(field.key()), current.get(field.key()))) {
                errors.add(FieldErrorItem.keyed(field.key(), EntityValidator.READONLY, "error.field.readonly"));
            }
        }
        return errors;
    }

    /**
     * Whether a transition leaves the record's state: 422 {@code entity_transition_not_allowed} with the state and the
     * action otherwise (ADR-0032, 6.12).
     */
    public static void requireAllowed(EntityDefinition entity, EntityTransition transition, Map<String, ?> record) {
        EntityWorkflow workflow = Objects.requireNonNull(workflow(entity), entity.code());
        String state = text(record.get(workflow.field()));
        if (!workflow.allows(transition.code(), state)) {
            throw ApiException.unprocessable(
                    ErrorCode.ENTITY_TRANSITION_NOT_ALLOWED,
                    "error.common.entity_transition_not_allowed",
                    Map.of("from", state == null ? "" : state, "action", transition.code()),
                    List.of());
        }
    }

    /** The problems of the transition's rules over the record as the transition leaves it (ADR-0032, 6.6). */
    public static List<FieldErrorItem> rules(
            EntityTransition transition, EntityValues record, @Nullable EntityValues before) {
        RuleErrors errors = new RuleErrors();
        for (EntityRule rule : transition.rules()) {
            rule.check(record, before, errors);
        }
        return errors.items();
    }

    /** The collections of the entity, in declaration order; none without a table. */
    public static List<EntityCollection> collections(EntityDefinition entity) {
        EntityModel model = entity.model();
        return model == null ? List.of() : model.collections();
    }

    private static @Nullable EntityWorkflow workflow(EntityDefinition entity) {
        EntityModel model = entity.model();
        return model == null ? null : model.workflow();
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
