package com.greenwhite.dwh.instance.mf.service;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import com.greenwhite.dwh.instance.mf.pref.MfPref;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The file list in the field registry: {@code GET /api/v1/files} and {@code /api/v1/query-meta/mf.files}.
 * The key is a UUID, read as text so the keyset cursor can carry it.
 */
@Configuration
public class MfFileQuery {

    public static final QueryList LIST = new QueryList(
            "mf.files",
            MfPref.FORM_FILES,
            "view",
            MfFileRepository.detailColumns(),
            MfFileRepository.detailFrom(),
            "f.id::text",
            List.of(
                    QueryField.of("originalName", "files.imya_fayla", QueryFieldType.TEXT, "f.original_name")
                            .asSortable().asSearchable(),
                    QueryField.of("sizeBytes", "files.razmer", QueryFieldType.NUMBER, "f.size_bytes").asSortable(),
                    QueryField.of("mimeType", "files.tip_mime", QueryFieldType.TEXT, "f.mime_type"),
                    QueryField.of("creatorName", "files.zagruzil", QueryFieldType.TEXT, "u.name")
                            .asNullable().asSearchable(),
                    QueryField.of("createdAt", "files.data_zagruzki", QueryFieldType.INSTANT, "f.created_at")
                            .asSortable()),
            "createdAt",
            true,
            QueryList.DEFAULT_LIMIT,
            QueryList.MAX_LIMIT);

    @Bean
    public QueryList mfFilesQueryList() {
        return LIST;
    }
}
