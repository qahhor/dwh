package com.smartup24.cms.instance.report;

import com.smartup24.cms.instance.common.entity.report.EntityReportDataset;
import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** A prepared projection of requests at their original grain, without product price measures (ADR-0039). */
@TestConfiguration
public class ExampleReportDatasetFixture {
    @Bean
    public EntityReportDataset requestProductsReportDataset() {
        return new EntityReportDataset(
                "example.requests.products",
                ExampleRequestsEntity.CODE,
                "example.requests.col.product_id",
                "productId",
                List.of(new EntityReportDataset.Projection("productUnit", "unit")));
    }
}
