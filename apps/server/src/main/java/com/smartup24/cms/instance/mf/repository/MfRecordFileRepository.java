package com.smartup24.cms.instance.mf.repository;

import com.smartup24.cms.instance.common.entity.EntityFiles.FileFacts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The attachments of stored files to the file and image fields of entity records ({@code mf_record_files}, V161;
 * ADR-0032, 4.7).
 */
@Repository
public class MfRecordFileRepository {

    private static final String FACTS = "f.id, f.original_name, f.size_bytes, f.mime_type";

    private final JdbcClient jdbc;

    public MfRecordFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The file, when {@code userId} uploaded it or it is attached to the record ({@code recordId} may be null). */
    public Optional<FileFacts> attachable(UUID fileId, String entity, @Nullable Long recordId, long userId) {
        return jdbc.sql("select " + FACTS + " from mf_files f where f.id = :fileId and (f.created_by = :userId"
                        + " or exists (select 1 from mf_record_files r where r.file_id = f.id and r.entity = :entity"
                        + " and r.record_id = :recordId))")
                .param("fileId", fileId)
                .param("userId", userId)
                .param("entity", entity)
                .param("recordId", recordId == null ? -1L : recordId)
                .query((rs, rowNum) -> facts(rs))
                .optional();
    }

    /** The file, when it is attached to the record. */
    public Optional<FileFacts> attached(String entity, long recordId, UUID fileId) {
        return jdbc.sql("select " + FACTS + " from mf_record_files r join mf_files f on f.id = r.file_id"
                        + " where r.entity = :entity and r.record_id = :recordId and r.file_id = :fileId limit 1")
                .param("entity", entity)
                .param("recordId", recordId)
                .param("fileId", fileId)
                .query((rs, rowNum) -> facts(rs))
                .optional();
    }

    /** The file attached to the record's field, if any. */
    public Optional<UUID> current(String entity, long recordId, String fieldKey) {
        return jdbc.sql("select file_id from mf_record_files where entity = :entity and record_id = :recordId"
                        + " and field_key = :fieldKey limit 1")
                .param("entity", entity)
                .param("recordId", recordId)
                .param("fieldKey", fieldKey)
                .query(UUID.class)
                .optional();
    }

    /** Replaces the attachment of the record's field with {@code fileId}, or removes it when null. */
    public void replace(String entity, long recordId, String fieldKey, @Nullable UUID fileId) {
        jdbc.sql("delete from mf_record_files where entity = :entity and record_id = :recordId"
                        + " and field_key = :fieldKey and file_id is distinct from cast(:fileId as uuid)")
                .param("entity", entity)
                .param("recordId", recordId)
                .param("fieldKey", fieldKey)
                .param("fileId", fileId)
                .update();
        if (fileId != null) {
            jdbc.sql("insert into mf_record_files (entity, record_id, field_key, file_id)"
                            + " values (:entity, :recordId, :fieldKey, :fileId) on conflict do nothing")
                    .param("entity", entity)
                    .param("recordId", recordId)
                    .param("fieldKey", fieldKey)
                    .param("fileId", fileId)
                    .update();
        }
    }

    /** Removes the record's attachments; how many there were. */
    public int deleteRecord(String entity, long recordId) {
        return jdbc.sql("delete from mf_record_files where entity = :entity and record_id = :recordId")
                .param("entity", entity)
                .param("recordId", recordId)
                .update();
    }

    private static FileFacts facts(ResultSet rs) throws SQLException {
        return new FileFacts(
                rs.getObject("id", UUID.class),
                rs.getString("original_name"),
                rs.getLong("size_bytes"),
                rs.getString("mime_type"));
    }
}
