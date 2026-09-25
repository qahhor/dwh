package com.greenwhite.dwh.instance.upl.upload;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import com.greenwhite.dwh.instance.upl.UplPref;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Список загрузок в реестре полей: {@code GET /api/v1/upl/packages} и {@code /api/v1/query-meta/upl.packages}.
 * Поля без колонки ({@code asHidden}) — только для фильтра.
 */
@Configuration
public class UplPackageQuery {

    public static final QueryList LIST = new QueryList(
            "upl.packages",
            UplPref.FORM_PACKAGES,
            UplPref.ACTION_VIEW,
            UplPackageRepository.PACKAGE_COLUMNS,
            UplPackageRepository.PACKAGE_FROM,
            "p.id",
            List.of(
                    QueryField.of("uploadedAt", "upl.pkg.col.uploaded_at", QueryFieldType.INSTANT, "p.uploaded_at")
                            .asSortable(),
                    QueryField.of("sourceName", "upl.pkg.col.source", QueryFieldType.TEXT, "s.name")
                            .asSortable().asSearchable(),
                    QueryField.of("sourceCode", "upl.list.col.code", QueryFieldType.TEXT, "s.code")
                            .asSearchable().asHidden(),
                    QueryField.of("periodFrom", "upl.pkg.col.period", QueryFieldType.DATE, "p.period_from")
                            .asSortable(),
                    QueryField.of("periodTo", "upl.pkg.col.period_to", QueryFieldType.DATE, "p.period_to").asHidden(),
                    QueryField.of("fileName", "upl.pkg.col.file", QueryFieldType.TEXT, "p.file_name").asSearchable(),
                    QueryField.enumeration("status", "upl.pkg.col.status", "p.status",
                            List.of(UplPackageModel.RECEIVED, UplPackageModel.VERIFIED, UplPackageModel.REJECTED,
                                    UplPackageModel.APPLIED), "upl.pkg.status."),
                    QueryField.of("rowsTotal", "upl.pkg.col.rows", QueryFieldType.NUMBER, "p.rows_total").asNullable(),
                    QueryField.of("errorsTotal", "upl.pkg.col.errors", QueryFieldType.NUMBER, "p.errors_total")
                            .asNullable().asHidden(),
                    QueryField.of("formatVersion", "upl.pkg.col.format_version", QueryFieldType.NUMBER,
                            "p.format_version").asHidden(),
                    QueryField.of("uploadedBy", "upl.pkg.col.uploaded_by", QueryFieldType.TEXT, "p.uploaded_by")
                            .asHidden()),
            "uploadedAt",
            true,
            QueryList.DEFAULT_LIMIT,
            QueryList.MAX_LIMIT);

    @Bean
    public QueryList uplPackagesQueryList() {
        return LIST;
    }
}
