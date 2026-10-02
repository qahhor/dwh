package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdListViewDtos.WidgetResponse;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdReportWidgetService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/report-widgets} (ADR-0032, 10.2; plan 10/10, item 5.8): the viewer's own widgets — reports saved as
 * views of entity lists with the kind {@code widget} — for the dashboard. Part of the profile, like the views; each
 * widget runs at {@code /api/v1/entities/{entity}/reports/{id}} under the entity's own rights.
 */
@RestController
@RequestMapping("/api/v1/report-widgets")
public class MdReportWidgetController {

    private final MdReportWidgetService service;

    public MdReportWidgetController(MdReportWidgetService service) {
        this.service = service;
    }

    @Operation(
            summary = "List my widgets",
            description = "The viewer's widgets for the dashboard, at most 12, over the entities the viewer may see.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<WidgetResponse>> list() {
        return ResponseEntity.ok(service.widgets(Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user")));
    }
}
