package com.greenwhite.dwh.instance.upl.format;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import com.greenwhite.dwh.instance.upl.UplPref;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Periodicity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/** Список источников анкеты в реестре полей: {@code GET /api/v1/upl/sources} и {@code /api/v1/query-meta/upl.sources}. */
@Configuration
public class UplSourceQuery {

    private static final String LAST_PUBLISHED_VERSION = """
            (select max(v.version) from upl_format_versions v
              where v.source_id = s.id and v.status <> 'draft')""";

    private static final String HAS_DRAFT = """
            exists (select 1 from upl_format_versions v
                     where v.source_id = s.id and v.status = 'draft')""";

    public static final QueryList LIST = new QueryList(
            "upl.sources",
            UplPref.FORM_SOURCES,
            UplPref.ACTION_VIEW,
            "s.id, s.code, s.name, s.periodicity, " + LAST_PUBLISHED_VERSION + " as last_published_version, "
                    + HAS_DRAFT + " as has_draft",
            "upl_sources s",
            "s.id",
            List.of(
                    QueryField.of("code", "upl.list.col.code", QueryFieldType.TEXT, "s.code").asSortable().asSearchable(),
                    QueryField.of("name", "upl.list.col.name", QueryFieldType.TEXT, "s.name").asSortable().asSearchable(),
                    QueryField.enumeration("periodicity", "upl.list.col.periodicity", "s.periodicity",
                            Arrays.stream(Periodicity.values()).map(Periodicity::db).toList(), "upl.periodicity."),
                    QueryField.of("lastPublishedVersion", "upl.list.col.published_version", QueryFieldType.NUMBER,
                            LAST_PUBLISHED_VERSION).asNullable(),
                    QueryField.of("hasDraft", "upl.list.col.draft", QueryFieldType.BOOLEAN, HAS_DRAFT)),
            "code");

    @Bean
    public QueryList uplSourcesQueryList() {
        return LIST;
    }
}
