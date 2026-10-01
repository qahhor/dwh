package com.smartup24.cms.instance.mf.service;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.mf.pref.MfPref;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                    QueryField.of("originalName", "files.list.file_name", QueryFieldType.TEXT, "f.original_name")
                            .asSortable()
                            .asSearchable(),
                    QueryField.of("sizeBytes", "files.list.size", QueryFieldType.NUMBER, "f.size_bytes")
                            .asSortable(),
                    QueryField.of("mimeType", "files.list.mime_type", QueryFieldType.TEXT, "f.mime_type"),
                    QueryField.of("creatorName", "files.list.uploaded_by", QueryFieldType.TEXT, "u.name")
                            .asNullable()
                            .asSearchable(),
                    QueryField.of("createdAt", "files.list.upload_date", QueryFieldType.INSTANT, "f.created_at")
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
