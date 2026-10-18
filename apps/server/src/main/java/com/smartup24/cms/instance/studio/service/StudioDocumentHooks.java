package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.State;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Transition;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.platform.api.entity.hook.EntityActionCall;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntityOperation;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The saves of a document of a live form (ADR-0043), as the general runtime runs its hooks: a new record takes the next
 * number of the form unless a status gives it; a transition keeps its comment — required, optional or none, at most
 * {@link #MAX_COMMENT} characters — gives the number when it reaches the status that does and the record has none, and
 * tells the people who may take a transition of the status it reached that is marked to notify. The hooks run in the
 * transaction of the save: a refused save keeps no number and sends no notice.
 */
final class StudioDocumentHooks implements EntityHooks {
    /** The longest comment of a transition. */
    static final int MAX_COMMENT = 1000;

    /** The parameter of a transition's comment in the body of the action. */
    static final String COMMENT = "comment";

    /** What one form's documents are: its code, partition, data scope, title, number and statuses. */
    record Spec(
            String code,
            long formId,
            String scope,
            String titleKey,
            Labels title,
            @Nullable Numbering numbering,
            @Nullable Workflow workflow) {

        Optional<Transition> transition(@Nullable String action) {
            if (workflow == null || action == null) return Optional.empty();
            return workflow.transitions().stream()
                    .filter(transition -> transition.code().equals(action))
                    .findFirst();
        }

        /** The transitions that leave {@code state} and tell the people who may take them. */
        List<Transition> notifying(String state) {
            if (workflow == null) return List.of();
            return workflow.transitions().stream()
                    .filter(transition -> Boolean.TRUE.equals(transition.notifies())
                            && transition.from().contains(state))
                    .toList();
        }

        Labels state(String code) {
            return workflow == null
                    ? title
                    : workflow.states().stream()
                            .filter(state -> state.code().equals(code))
                            .map(State::labels)
                            .findFirst()
                            .orElse(title);
        }
    }

    private final Spec spec;
    private final StudioDocuments documents;

    StudioDocumentHooks(Spec spec, StudioDocuments documents) {
        this.spec = spec;
        this.documents = documents;
    }

    @Override
    public String entity() {
        return spec.code();
    }

    @Override
    public void beforeSave(EntitySave save) {
        Numbering numbering = spec.numbering();
        if (save.operation() == EntityOperation.CREATE) {
            if (numbering != null && numbering.assignOn() == null) number(save, numbering);
            return;
        }
        if (save.operation() != EntityOperation.ACTION) return;
        Optional<Transition> taken = spec.transition(save.action());
        if (taken.isEmpty()) return;
        Transition transition = taken.get();
        comment(save, transition);
        if (numbering != null
                && transition.to().equals(numbering.assignOn())
                && save.values().get(MdSimpleFormDocumentLogic.NUMBER) == null) {
            number(save, numbering);
        }
    }

    @Override
    public void afterSave(EntitySave save) {
        Long id = save.id();
        if (save.operation() != EntityOperation.ACTION || id == null) return;
        Optional<Transition> taken = spec.transition(save.action());
        if (taken.isEmpty()) return;
        String reached = taken.get().to();
        List<Transition> next = spec.notifying(reached);
        if (next.isEmpty()) return;
        EntityValues values = save.values();
        documents.notify(new StudioDocuments.Step(
                spec.code(),
                save.entity().form(),
                spec.scope(),
                id,
                key(values.get("createdBy")),
                key(values.get(StudioEntityCompiler.ORG_UNIT)),
                save.actor().userId(),
                title(values, id),
                spec.title(),
                reached,
                spec.state(reached),
                next));
    }

    private void number(EntitySave save, Numbering numbering) {
        save.values().set(MdSimpleFormDocumentLogic.NUMBER, documents.number(spec.formId(), numbering));
    }

    /** The comment of a transition, as the record keeps it until the next one: null when there is none. */
    private static void comment(EntitySave save, Transition transition) {
        Object sent = save instanceof EntityActionCall call ? call.params().get(COMMENT) : null;
        String comment = sent == null || "none".equals(transition.comment())
                ? ""
                : String.valueOf(sent).strip();
        if ("required".equals(transition.comment()) && comment.isEmpty()) {
            save.reject(COMMENT, "required", "error.studio.comment_required", Map.of());
            return;
        }
        if (comment.codePointCount(0, comment.length()) > MAX_COMMENT) {
            save.reject(COMMENT, "too_long", "error.field.too_long", Map.of("max", MAX_COMMENT));
            return;
        }
        save.values().set(MdSimpleFormDocumentLogic.COMMENT, comment.isEmpty() ? null : comment);
    }

    /** What names the record in a notice: its title field, number or the record's own number, else its id. */
    private String title(EntityValues values, long id) {
        Object title = values.get(spec.titleKey());
        if (title == null || String.valueOf(title).isBlank()) return "#" + id;
        return StudioFormLayout.RECORD_NUMBER.equals(spec.titleKey()) ? "#" + title : String.valueOf(title);
    }

    private static @Nullable Long key(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
