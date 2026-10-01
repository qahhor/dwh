package com.smartup24.cms.instance.md.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storage quotas held by md: the instance limit and the limits of users and roles. The file module reads them
 * through {@code MdStorageQuotaService}, not through these tables (ADR-0026).
 */
@Repository
public class MdStorageQuotaRepository {

    private final JdbcClient jdbcClient;

    public MdStorageQuotaRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The instance limit, or empty when the instance has no row or no limit of its own. */
    public Optional<Long> instanceQuotaBytes() {
        return jdbcClient
                .sql("select storage_quota_bytes from md_instance_info limit 1")
                .query((rs, rowNum) -> rs.getObject("storage_quota_bytes", Long.class))
                .optional();
    }

    /** The limit of the user, else the largest limit of the user's roles; empty when none sets one. */
    public Optional<Long> userQuotaBytes(long userId) {
        return jdbcClient
                .sql("""
                select coalesce(u.storage_quota_bytes, max(r.storage_quota_bytes)) as quota
                from md_users u
                left join md_user_roles ur on ur.user_id = u.id
                left join md_roles r on r.id = ur.role_id
                where u.id = :userId
                group by u.id, u.storage_quota_bytes
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> rs.getObject("quota", Long.class))
                .optional();
    }
}
