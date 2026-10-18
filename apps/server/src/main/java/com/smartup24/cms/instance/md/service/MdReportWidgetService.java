package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.report.EntityReportViews;
import com.smartup24.cms.instance.md.api.MdListViewDtos.WidgetResponse;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.repository.MdListViewRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Personal widgets and shared saved reports are validated against the current reader's fields (ADR-0036). */
@Service
public class MdReportWidgetService implements EntityReportViews {
    private final MdListViewRepository repo;
    private final MdReportCatalogService catalog;

    public MdReportWidgetService(MdListViewRepository repo, MdReportCatalogService catalog) {
        this.repo = repo;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<WidgetResponse> widgets(long userId) {
        return repo.widgets(userId).stream()
                .flatMap(view -> catalog.visible(view, userId, false).stream())
                .map(view -> new WidgetResponse(
                        view.id(),
                        view.listCode(),
                        view.entity(),
                        view.name(),
                        view.state(),
                        view.lockVersion(),
                        view.modifiedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SavedReport> report(long userId, String listCode, long viewId) {
        return catalog.find(userId, viewId)
                .filter(view -> view.listCode().equals(listCode))
                .map(this::saved);
    }

    private SavedReport saved(ReportViewItem view) {
        JsonNode state = view.state();
        return new SavedReport(state, MdReportViewState.dataset(state));
    }
}
