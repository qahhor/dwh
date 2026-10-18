package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.LineField;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.State;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.studio.service.StudioChangeRules.Change;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Which changes of a document's parts a new version may make (ADR-0043), as {@link StudioChangeRules} does for its
 * fields. Tables of lines, columns, statuses and transitions may be added; labels, conditions, comments, locks and the
 * formulas of columns change freely. Refused: a table removed or moved — its rows live in the table of its place
 * ({@code lines_moved}); fewer rows than before ({@code max_rows}); a column removed, of another type or target, made
 * required, shorter, with fewer decimals or choices, or made computed while it holds entered values (the codes of the
 * fields, addressed {@code lines.<table>.<column>}); a number's pattern or reset changed, or the number dropped — the
 * numbers given stay unique only under one pattern ({@code numbering}); the statuses dropped ({@code workflow_removed});
 * a status removed — records may be in it ({@code state_removed}); another first status — the records saved before the
 * statuses read the first one ({@code initial}).
 */
final class StudioDocumentChangeRules {
    private StudioDocumentChangeRules() {}

    /** The refused changes of the document's parts from {@code active} to {@code next}. */
    static void incompatible(Declaration active, Declaration next, List<Change> changes) {
        lines(active.lines(), next.lines(), changes);
        numbering(active.entity().numbering(), next.entity().numbering(), changes);
        workflow(active.workflow(), next.workflow(), changes);
    }

    private static void lines(@Nullable List<Lines> before, @Nullable List<Lines> after, List<Change> changes) {
        if (before == null) return;
        List<Lines> following = after == null ? List.of() : after;
        for (int index = 0; index < before.size(); index++) {
            Lines table = before.get(index);
            String at = "lines." + table.key();
            if (index >= following.size() || !following.get(index).key().equals(table.key())) {
                changes.add(new Change(at, "lines_moved"));
                continue;
            }
            Lines kept = following.get(index);
            if (kept.maxRows() < table.maxRows()) changes.add(new Change(at, "max_rows"));
            columns(at, table.fields(), kept.fields(), changes);
        }
    }

    private static void columns(String at, List<LineField> before, List<LineField> after, List<Change> changes) {
        Map<String, LineField> following = new LinkedHashMap<>();
        after.forEach(column -> following.put(column.id(), column));
        for (LineField column : before) {
            String path = at + "." + column.id();
            LineField kept = following.remove(column.id());
            if (kept == null) {
                changes.add(new Change(path, "removed"));
                continue;
            }
            if (!column.type().equals(kept.type())) {
                changes.add(new Change(path, "type"));
                continue;
            }
            if (!Objects.equals(column.target(), kept.target())) changes.add(new Change(path, "target"));
            if (dropsOption(column.options(), kept.options())) changes.add(new Change(path, "option_removed"));
            if (!column.required() && kept.required()) changes.add(new Change(path, "required"));
            if (shorter(column.maxLength(), kept.maxLength())) changes.add(new Change(path, "max_length"));
            if (shorter(column.scale(), kept.scale())) changes.add(new Change(path, "scale"));
            if (column.formula() == null && kept.formula() != null) changes.add(new Change(path, "computed"));
        }
        for (LineField added : following.values()) {
            if (added.required()) changes.add(new Change(at + "." + added.id(), "required"));
        }
    }

    private static void numbering(@Nullable Numbering before, @Nullable Numbering after, List<Change> changes) {
        if (before == null) return;
        if (after == null
                || !before.pattern().equals(after.pattern())
                || !before.reset().equals(after.reset())) {
            changes.add(new Change("entity.numbering", "numbering"));
        }
    }

    private static void workflow(@Nullable Workflow before, @Nullable Workflow after, List<Change> changes) {
        if (before == null) return;
        if (after == null) {
            changes.add(new Change("workflow", "workflow_removed"));
            return;
        }
        if (!before.states().getFirst().code().equals(after.states().getFirst().code())) {
            changes.add(
                    new Change("workflow.states." + before.states().getFirst().code(), "initial"));
        }
        Set<String> kept = after.states().stream().map(State::code).collect(Collectors.toSet());
        for (State state : before.states()) {
            if (!kept.contains(state.code()))
                changes.add(new Change("workflow.states." + state.code(), "state_removed"));
        }
    }

    private static boolean dropsOption(@Nullable List<Option> before, @Nullable List<Option> after) {
        if (before == null) return false;
        Set<String> kept =
                after == null ? Set.of() : after.stream().map(Option::code).collect(Collectors.toSet());
        return before.stream().map(Option::code).anyMatch(code -> !kept.contains(code));
    }

    private static boolean shorter(@Nullable Integer before, @Nullable Integer after) {
        return before != null && after != null && after < before;
    }
}
