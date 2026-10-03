package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.report.EntityReportViews;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.md.api.MdListViewDtos;
import com.smartup24.cms.instance.md.api.MdListViewDtos.WidgetResponse;
import com.smartup24.cms.instance.md.repository.MdListViewRepository;
import com.smartup24.cms.instance.md.repository.MdListViewRepository.ListView;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The reports people saved as views of entity lists (ADR-0032, 10.2; plan 10/10, item 5.8): the viewer's widgets for
 * the dashboard, and a saved report for the runtime to run ({@link EntityReportViews}). Reports and widgets are
 * personal: a view is found only among its owner's own (question 6 of ADR-0032, 19 — the proposed default, taken as an
 * assumption).
 */
@Service
public class MdReportWidgetService implements EntityReportViews {

    private final MdListViewRepository repo;
    private final EntityReports reports;
    /** Reads the stored state column (plan 10/10, item 3.11: the application's mapper). */
    private final JsonColumns stored;

    public MdReportWidgetService(MdListViewRepository repo, EntityReports reports, ObjectMapper json) {
        this.repo = repo;
        this.reports = reports;
        this.stored = new JsonColumns(json, "md_list_views");
    }

    /**
     * The viewer's widgets, by name, each with the entity whose list it was saved on; a widget of an entity the
     * viewer may no longer see is left out (the view stays and comes back with the right).
     */
    @Transactional(readOnly = true)
    public List<WidgetResponse> widgets(long userId) {
        return repo.widgets(userId).stream()
                .flatMap(view -> reports.entityOfList(view.listCode()).stream()
                        .map(entity -> new WidgetResponse(
                                view.id(),
                                view.listCode(),
                                entity,
                                view.name(),
                                stored.tree(view.stateJson()),
                                view.lockVersion(),
                                view.modifiedAt())))
                .toList();
    }

    @Override
    public Optional<SavedReport> report(long userId, String listCode, long viewId) {
        return repo.find(userId, listCode, viewId)
                .filter(view -> !MdListViewDtos.TABLE.equals(view.kind()))
                .map(this::saved);
    }

    private SavedReport saved(ListView view) {
        JsonNode state = stored.tree(view.stateJson());
        JsonNode filter = state.get("filter");
        return new SavedReport(
                state.get("groupBy"),
                state.get("measures"),
                filter == null || filter.isNull() ? null : filter.toString());
    }
}
