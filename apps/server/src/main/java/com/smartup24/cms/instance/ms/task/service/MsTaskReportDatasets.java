package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.entity.report.EntityReportDataset;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tasks remain the grain while project dates follow current project access (ADR-0039). */
@Configuration
public class MsTaskReportDatasets {
    @Bean
    public EntityReportDataset taskProjectsReportDataset() {
        return new EntityReportDataset(
                "ms.tasks.projects",
                MsTaskEntity.CODE,
                "tasks.dataset.projects",
                "projectId",
                List.of(
                        new EntityReportDataset.Projection(
                                "projectCreatedAt", "createdAt", "tasks.dataset.project_created_at"),
                        new EntityReportDataset.Projection(
                                "projectModifiedAt", "modifiedAt", "tasks.dataset.project_modified_at")));
    }
}
