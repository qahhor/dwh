package com.greenwhite.dwh.instance.ms.note.service;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The personal note list in the field registry (ADR-0016, roadmap item 51): {@code GET /api/v1/notes} and
 * {@code /api/v1/query-meta/ms.notes}. Pinned notes first, then the most recently changed, as before: the
 * hidden sort key {@code rank} joins the pin flag and the change time into one text value, so the registry's
 * keyset on one field and the row id keeps that order.
 */
@Configuration
public class MsNoteQuery {

    public static final String COLUMNS = """
            n.id, n.title, n.content_md, n.color, n.is_pinned, n.attributes::text as attributes_str,
            n.created_by, n.modified_by, n.created_at, n.modified_at""";

    private static final String RANK =
            "(case when n.is_pinned then '1' else '0' end"
                    + " || to_char(n.modified_at at time zone 'UTC', 'YYYYMMDDHH24MISSUS'))";

    public static final QueryList LIST = new QueryList(
            "ms.notes",
            "notes",
            "view",
            COLUMNS,
            "ms_notes n",
            "n.id",
            List.of(
                    QueryField.of("title", "notes.col.title", QueryFieldType.TEXT, "n.title").asSortable().asSearchable(),
                    QueryField.of("contentMd", "notes.col.content", QueryFieldType.TEXT, "n.content_md")
                            .asSearchable().asHidden(),
                    QueryField.of("color", "notes.col.color", QueryFieldType.TEXT, "n.color"),
                    QueryField.of("isPinned", "notes.col.pinned", QueryFieldType.BOOLEAN, "n.is_pinned"),
                    QueryField.of("modifiedAt", "notes.col.modified_at", QueryFieldType.INSTANT, "n.modified_at")
                            .asSortable(),
                    QueryField.of("createdAt", "notes.col.created_at", QueryFieldType.INSTANT, "n.created_at")
                            .asSortable().asHidden(),
                    QueryField.of("rank", "notes.col.rank", QueryFieldType.TEXT, RANK)
                            .asSortable().asNotFilterable().asHidden()),
            "rank",
            true,
            QueryList.DEFAULT_LIMIT,
            QueryList.MAX_LIMIT);

    @Bean
    public QueryList msNotesQueryList() {
        return LIST;
    }
}
