package com.greenwhite.dwh.instance.report.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.report.service.ReportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/tasks/export")
    @RequiresPermission(form = "tasks.items", action = "view")
    public void exportTasks(
            @RequestParam(name = "format", defaultValue = "csv") String format,
            HttpServletResponse response) throws IOException {

        if ("xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format)) {
            response.setContentType("application/vnd.ms-excel; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"tasks-export.xls\"");
            reportService.exportTasksExcelXml(response.getOutputStream());
        } else {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"tasks-export.csv\"");
            reportService.exportTasksCsv(response.getOutputStream());
        }
    }
}
