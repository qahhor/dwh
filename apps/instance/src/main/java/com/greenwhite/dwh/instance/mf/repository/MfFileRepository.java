package com.greenwhite.dwh.instance.mf.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MfFileRepository {

    private final JdbcClient jdbcClient;

    public MfFileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public FileRecord create(String sha256, String originalName, long sizeBytes,
                             String mimeType, String storageBucket, String storageKey, Long createdBy) {

        return jdbcClient.sql("""
                insert into mf_files (id, sha256, original_name, size_bytes, mime_type, storage_bucket, storage_key, created_at, created_by)
                values (gen_random_uuid(), :sha256, :originalName, :sizeBytes, :mimeType, :storageBucket, :storageKey, now(), :createdBy)
                returning id, sha256, original_name, size_bytes, mime_type, storage_bucket, storage_key, created_at, created_by
                """)
                .param("sha256", sha256)
                .param("originalName", originalName)
                .param("sizeBytes", sizeBytes)
                .param("mimeType", mimeType)
                .param("storageBucket", storageBucket)
                .param("storageKey", storageKey)
                .param("createdBy", createdBy)
                .query(this::mapRecord)
                .single();
    }

    public Optional<FileRecord> findById(UUID id) {
        return jdbcClient.sql("""
                select id, sha256, original_name, size_bytes, mime_type, storage_bucket, storage_key, created_at, created_by
                from mf_files
                where id = :id
                """)
                .param("id", id)
                .query(this::mapRecord)
                .optional();
    }

    public Optional<FileRecord> findBySha256(String sha256) {
        return jdbcClient.sql("""
                select id, sha256, original_name, size_bytes, mime_type, storage_bucket, storage_key, created_at, created_by
                from mf_files
                where sha256 = :sha256
                limit 1
                """)
                .param("sha256", sha256)
                .query(this::mapRecord)
                .optional();
    }

    public void delete(UUID id) {
        jdbcClient.sql("delete from mf_files where id = :id")
                .param("id", id)
                .update();
    }

    private FileRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FileRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("sha256"),
                rs.getString("original_name"),
                rs.getLong("size_bytes"),
                rs.getString("mime_type"),
                rs.getString("storage_bucket"),
                rs.getString("storage_key"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null
        );
    }

    public record FileRecord(
            UUID id,
            String sha256,
            String originalName,
            long sizeBytes,
            String mimeType,
            String storageBucket,
            String storageKey,
            Instant createdAt,
            Long createdBy
    ) {}
}
