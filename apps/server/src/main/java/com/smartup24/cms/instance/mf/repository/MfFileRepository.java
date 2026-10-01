package com.smartup24.cms.instance.mf.repository;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MfFileRepository {

    private static final long FILE_QUOTA_LOCK_KEY = 0x4D46514CL;

    /** Columns and source of the file list; the registry list {@code mf.files} reads them too. */
    static final String DETAIL_COLUMNS = """
            f.id, f.sha256, f.original_name, f.size_bytes, f.mime_type,
                   f.storage_bucket, f.storage_key, f.created_at, f.created_by,
                   u.name as creator_name, u.login as creator_login""";

    static final String DETAIL_FROM = "mf_files f left join md_pub_users u on u.id = f.created_by";

    public static String detailColumns() {
        return DETAIL_COLUMNS;
    }

    public static String detailFrom() {
        return DETAIL_FROM;
    }

    private final JdbcClient jdbcClient;
    private final QueryListRepository lists;

    public MfFileRepository(JdbcClient jdbcClient, QueryListRepository lists) {
        this.lists = lists;
        this.jdbcClient = jdbcClient;
    }

    public FileRecord create(
            String sha256,
            String originalName,
            long sizeBytes,
            String mimeType,
            String storageBucket,
            String storageKey,
            Long createdBy) {

        return jdbcClient
                .sql("""
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
        return findById(id, ScopeFilter.unrestricted());
    }

    public Optional<FileRecord> findById(UUID id, ScopeFilter scope) {
        String sql = """
                select f.id, f.sha256, f.original_name, f.size_bytes, f.mime_type,
                       f.storage_bucket, f.storage_key, f.created_at, f.created_by
                from mf_files f
                where f.id = :id
                """ + scope.sql();
        var query = jdbcClient.sql(sql).param("id", id);
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query(this::mapRecord).optional();
    }

    /**
     * Any ownership record with this content, used to reuse an object already
     * on disk instead of uploading it again. The returned record may belong to
     * another user, so it must never be exposed (V010): only the bucket and key
     * are taken from it.
     */
    public Optional<FileRecord> findBySha256(String sha256) {
        return jdbcClient
                .sql("""
                select id, sha256, original_name, size_bytes, mime_type, storage_bucket, storage_key, created_at, created_by
                from mf_files
                where sha256 = :sha256
                limit 1
                """)
                .param("sha256", sha256)
                .query(this::mapRecord)
                .optional();
    }

    /** This user's ownership record for this content: a repeat upload of the user's own file. */
    public Optional<FileRecord> findBySha256AndOwner(String sha256, Long ownerId) {
        if (ownerId == null) {
            return Optional.empty();
        }
        return jdbcClient
                .sql("""
                select id, sha256, original_name, size_bytes, mime_type, storage_bucket, storage_key, created_at, created_by
                from mf_files
                where sha256 = :sha256 and created_by = :ownerId
                """)
                .param("sha256", sha256)
                .param("ownerId", ownerId)
                .query(this::mapRecord)
                .optional();
    }

    /** Whether this content still has owners: the check before deleting the object from disk. */
    public boolean existsBySha256(String sha256) {
        Long count = jdbcClient
                .sql("select count(*) from mf_files where sha256 = :sha256")
                .param("sha256", sha256)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    public void delete(UUID id) {
        jdbcClient.sql("delete from mf_files where id = :id").param("id", id).update();
    }

    /**
     * Serializes file-quota writers for the duration of the current transaction.
     * Reads remain lock-free; callers must re-read usage after acquiring the lock.
     */
    public void lockQuotaBudget() {
        jdbcClient
                .sql("select pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", FILE_QUOTA_LOCK_KEY)
                .query((rs, rowNum) -> Boolean.TRUE)
                .single();
    }

    public long getTotalCompanyUsedBytes() {
        Long sum = jdbcClient
                .sql("select coalesce(sum(size_bytes), 0) from mf_files")
                .query(Long.class)
                .single();
        return sum != null ? sum : 0L;
    }

    public long getUserUsedBytes(Long userId) {
        if (userId == null) return 0L;
        Long sum = jdbcClient
                .sql("select coalesce(sum(size_bytes), 0) from mf_files where created_by = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        return sum != null ? sum : 0L;
    }

    public int countTotalFiles() {
        Integer count = jdbcClient
                .sql("select count(*) from mf_files")
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }

    public int countUserFiles(Long userId) {
        if (userId == null) return 0;
        Integer count = jdbcClient
                .sql("select count(*) from mf_files where created_by = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }

    /**
     * A page of the file list by the registry plan. The data scope (ADR-0013) and "only mine" go into the same
     * SQL as extra predicates, so paging and the total count only ever see visible files.
     */
    public KeysetPage<FileDetailRecord> pageFiles(QueryPlan plan, ScopeFilter scope, Long onlyOwnerId) {
        StringBuilder sql = new StringBuilder(scope.sql());
        Map<String, Object> params = new LinkedHashMap<>();
        if (scope.bindsUserId()) {
            params.put("scopeUserId", scope.userId());
        }
        if (onlyOwnerId != null) {
            sql.append(" and f.created_by = :onlyOwnerId");
            params.put("onlyOwnerId", onlyOwnerId);
        }
        return lists.page(plan, this::mapDetail, new QueryPlan.SqlFragment(sql.toString(), params));
    }

    private FileDetailRecord mapDetail(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FileDetailRecord(
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
                rs.getString("creator_login"));
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
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
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
            Long createdBy) {}

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
            String creatorLogin) {}
}
