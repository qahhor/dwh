package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Mailing;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Automation;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormDetail;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportItem;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportRequest;
import com.smartup24.cms.instance.studio.service.StudioFormViews;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a package being made holds and why (ADR-0047): the selected items first, then what they stand on — the report
 * of a mailing, the dataset and the form of a report, the form of a dataset or an automation (and the forms its actions
 * write or name the rights of), the forms the fields of a form refer to and the lists of every form. A form comes after
 * the forms it refers to, so an import can create them in order.
 */
final class SolutionSelection {
    static final String SELECTED = "selected";
    static final String DEPENDENCY = "dependency";

    /** Why an item is in the package: selected, or a dependency of the item {@code of} ({@code kind:key}). */
    record Picked(String reason, @Nullable String of) {}

    private final StudioFormViews views;
    private final SolutionSources sources;
    private final Map<String, FormDetail> forms = new LinkedHashMap<>();
    private final Set<String> visiting = new HashSet<>();
    private final Set<String> lists = new LinkedHashSet<>();
    private final Map<String, Dataset> datasets = new LinkedHashMap<>();
    private final Map<Long, ReportViewItem> reports = new LinkedHashMap<>();
    private final Map<Long, Mailing> mailings = new LinkedHashMap<>();
    private final Map<Long, Automation> automations = new LinkedHashMap<>();
    private final Map<String, Picked> reasons = new LinkedHashMap<>();
    private final Map<String, Labels> titles = new LinkedHashMap<>();

    SolutionSelection(StudioFormViews views, SolutionSources sources) {
        this.views = views;
        this.sources = sources;
    }

    SolutionSelection pick(ExportRequest request) {
        Picked selected = new Picked(SELECTED, null);
        for (Long id : distinct(request.mailings())) mailing(id, selected);
        for (Long id : distinct(request.reports())) report(id, selected);
        for (String code : distinct(request.datasets())) dataset(code, selected);
        for (Long id : distinct(request.automations())) automation(id, selected);
        for (String code : distinct(request.forms())) form(code, selected);
        for (FormDetail form : List.copyOf(forms.values())) {
            String of = "form:studio." + form.declaration().entity().name();
            for (Target target : targets(form)) {
                if (!"list".equals(target.kind()) || !lists.add(target.code())) continue;
                note(
                        "list",
                        target.code(),
                        new Picked(DEPENDENCY, of),
                        sources.list(target.code(), false).name());
            }
        }
        return this;
    }

    Map<String, FormDetail> forms() {
        return forms;
    }

    Set<String> lists() {
        return lists;
    }

    Map<String, Dataset> datasets() {
        return datasets;
    }

    Map<Long, ReportViewItem> reports() {
        return reports;
    }

    Map<Long, Mailing> mailings() {
        return mailings;
    }

    Map<Long, Automation> automations() {
        return automations;
    }

    /** What the package holds, in the order an import applies it. */
    List<ExportItem> items() {
        List<ExportItem> items = new ArrayList<>();
        for (String kind : List.of("list", "form", "dataset", "report", "mailing", "automation")) {
            reasons.forEach((key, picked) -> {
                if (!key.startsWith(kind + ":")) return;
                String code = key.substring(kind.length() + 1);
                items.add(new ExportItem(kind, code, titles.get(key), picked.reason(), picked.of()));
            });
        }
        return List.copyOf(items);
    }

    boolean holds(String kind, String key) {
        return reasons.containsKey(kind + ":" + key);
    }

    private void form(String code, Picked why) {
        if (forms.containsKey(code)) {
            note("form", code, why, null);
            return;
        }
        if (!visiting.add(code)) return;
        FormDetail detail = views.get(code);
        for (Target target : targets(detail)) {
            if ("form".equals(target.kind()) && !target.code().equals(code))
                form(target.code(), new Picked(DEPENDENCY, "form:" + code));
        }
        forms.put(code, detail);
        visiting.remove(code);
        note("form", code, why, detail.declaration().entity().title());
    }

    private void dataset(String code, Picked why) {
        if (datasets.containsKey(code)) {
            note("dataset", code, why, null);
            return;
        }
        Dataset dataset = sources.dataset(code);
        datasets.put(code, dataset);
        note("dataset", code, why, dataset.name());
        live(dataset.entity(), "dataset:" + code);
    }

    private void report(long id, Picked why) {
        if (reports.containsKey(id)) {
            note("report", Long.toString(id), why, null);
            return;
        }
        ReportViewItem report = sources.report(id);
        reports.put(id, report);
        note("report", Long.toString(id), why, SolutionItems.same(report.name()));
        String dataset = SolutionSources.datasetOf(report.state());
        if (dataset != null) dataset(dataset, new Picked(DEPENDENCY, "report:" + id));
        live(report.entity(), "report:" + id);
    }

    private void mailing(long id, Picked why) {
        Mailing mailing = sources.mailing(id);
        mailings.put(id, mailing);
        note("mailing", Long.toString(id), why, SolutionItems.same(mailing.name()));
        report(mailing.viewId(), new Picked(DEPENDENCY, "mailing:" + id));
    }

    private void automation(long id, Picked why) {
        Automation automation = sources.automation(id);
        automations.put(id, automation);
        note("automation", Long.toString(id), why, SolutionItems.same(automation.name()));
        String of = "automation:" + id;
        live(automation.form(), of);
        for (var action : automation.actions()) {
            if (action.form() != null) live(action.form(), of);
            if (action.recipients() == null || action.recipients().rights() == null) continue;
            for (Right right : action.recipients().rights()) live(right.form(), of);
        }
    }

    /** A live form an item stands on; an entity declared in code ships with every installation. */
    private void live(String code, String of) {
        if (code.startsWith("studio.")) form(code, new Picked(DEPENDENCY, of));
    }

    private void note(String kind, String key, Picked why, @Nullable Labels title) {
        String id = kind + ":" + key;
        Picked known = reasons.get(id);
        if (known == null || SELECTED.equals(why.reason())) reasons.put(id, why);
        if (title != null) titles.put(id, title);
    }

    static List<Target> targets(FormDetail form) {
        List<Target> targets = new ArrayList<>();
        for (Field field : form.declaration().fields()) if (field.target() != null) targets.add(field.target());
        if (form.declaration().lines() != null) {
            form.declaration()
                    .lines()
                    .forEach(table -> table.fields().forEach(column -> {
                        if (column.target() != null) targets.add(column.target());
                    }));
        }
        return targets;
    }

    private static <T> List<T> distinct(@Nullable List<T> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }
}
