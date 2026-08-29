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

    public long getTotalCompanyUsedBytes() {
        Long sum = jdbcClient.sql("select coalesce(sum(size_bytes), 0) from mf_files")
                .query(Long.class)
                .single();
        return sum != null ? sum : 0L;
    }

    public long getUserUsedBytes(Long userId) {
        if (userId == null) return 0L;
        Long sum = jdbcClient.sql("select coalesce(sum(size_bytes), 0) from mf_files where created_by = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        return sum != null ? sum : 0L;
    }

    public long getCompanyQuotaBytes() {
        Long quota = jdbcClient.sql("select coalesce(storage_quota_bytes, 53687091200) from md_instance_info limit 1")
                .query(Long.class)
                .optional()
                .orElse(53687091200L);
        return quota != null ? quota : 53687091200L;
    }

    public long getUserEffectiveQuotaBytes(Long userId) {
        if (userId == null) return 1073741824L; // 1 GB
        Long quota = jdbcClient.sql("""
                select coalesce(
                    u.storage_quota_bytes,
                    max(r.storage_quota_bytes),
                    1073741824
                ) as effective_quota
                from md_users u
                left join md_user_roles ur on ur.user_id = u.id
                left join md_roles r on r.id = ur.role_id
                where u.id = :userId
                group by u.id, u.storage_quota_bytes
                """)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElse(1073741824L);
        return quota != null ? quota : 1073741824L;
    }

    public int countTotalFiles() {
        Integer count = jdbcClient.sql("select count(*) from mf_files")
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }

    public int countUserFiles(Long userId) {
        if (userId == null) return 0;
        Integer count = jdbcClient.sql("select count(*) from mf_files where created_by = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }

    public java.util.List<FileDetailRecord> listFiles(Long userId, boolean onlyMine, String query, int limit) {
        StringBuilder sql = new StringBuilder("""
                select f.id, f.sha256, f.original_name, f.size_bytes, f.mime_type,
                       f.storage_bucket, f.storage_key, f.created_at, f.created_by,
                       u.name as creator_name, u.login as creator_login
                from mf_files f
                left join md_users u on u.id = f.created_by
                where 1=1
                """);

        var client = jdbcClient;
        if (onlyMine && userId != null) {
            sql.append(" and f.created_by = :userId");
        }
        if (query != null && !query.isBlank()) {
            sql.append(" and f.original_name ilike :query");
        }
        sql.append(" order by f.created_at desc limit :limit");

        var querySpec = client.sql(sql.toString());
        if (onlyMine && userId != null) {
            querySpec = querySpec.param("userId", userId);
        }
        if (query != null && !query.isBlank()) {
            querySpec = querySpec.param("query", "%" + query.trim() + "%");
        }
        querySpec = querySpec.param("limit", limit > 0 ? limit : 50);

        return querySpec.query((rs, rowNum) -> new FileDetailRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("sha256"),
                rs.getString("original_name"),
                rs.getLong("size_bytes"),
                rs.getString("mime_type"),
                rs.getString("storage_bucket"),
                rs.getString("storage_key"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null,
                rs.getString("creator_name"),
                rs.getString("creator_login")
        )).list();
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

    public record FileDetailRecord(
            UUID id,
            String sha256,
            String originalName,
            long sizeBytes,
            String mimeType,
            String storageBucket,
            String storageKey,
            Instant createdAt,
            Long createdBy,
            String creatorName,
            String creatorLogin
    ) {}
}

