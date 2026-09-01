package com.greenwhite.dwh.cp.repository;

import com.greenwhite.dwh.cp.pref.CpPref;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Реестр клиентов и экземпляров + приём heartbeat (FR-CP-1, FR-CP-2). */
@Repository
public class CpFleetRepository {

    private final JdbcClient jdbc;

    public CpFleetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- клиенты

    public List<CpClient> listClients() {
        return jdbc.sql("""
                        select id, code, name, resource_profile, created_at
                        from cp_clients order by code
                        """)
                .query(CpFleetRepository::mapClient)
                .list();
    }

    public Optional<CpClient> findClientByCode(String code) {
        return jdbc.sql("""
                        select id, code, name, resource_profile, created_at
                        from cp_clients where code = :code
                        """)
                .param("code", code)
                .query(CpFleetRepository::mapClient)
                .optional();
    }

    public Long createClient(String code, String name, String profile) {
        return jdbc.sql("""
                        insert into cp_clients (code, name, resource_profile)
                        values (:code, :name, :profile)
                        returning id
                        """)
                .param("code", code)
                .param("name", name)
                .param("profile", profile)
                .query(Long.class)
                .single();
    }

    /**
     * Флот с вычисленным состоянием: DOWN, если heartbeat не приходил дольше
     * таймаута. Состояние считается запросом, а не хранится — иначе оно
     * протухало бы ровно тогда, когда важнее всего (экземпляр умер и никто
     * не пришёл обновить ему статус).
     */
    public List<CpFleetItem> listFleet() {
        return jdbc.sql("""
                        select i.id, c.code as client_code, c.name as client_name,
                               c.resource_profile, i.environment, i.url,
                               i.app_version, i.schema_version, i.last_heartbeat_at,
                               coalesce(i.license_status, 'ACTIVE') as license_status,
                               case
                                   when i.last_heartbeat_at is null then 'NEVER'
                                   when i.last_heartbeat_at < now() - make_interval(mins => :timeout) then 'DOWN'
                                   else 'UP'
                               end as health
                        from cp_instances i
                        join cp_clients c on c.id = i.client_id
                        order by c.code, i.environment
                        """)
                .param("timeout", CpPref.HEARTBEAT_TIMEOUT_MINUTES)
                .query((rs, n) -> new CpFleetItem(
                        rs.getLong("id"),
                        rs.getString("client_code"),
                        rs.getString("client_name"),
                        rs.getString("resource_profile"),
                        rs.getString("environment"),
                        rs.getString("url"),
                        rs.getString("app_version"),
                        rs.getString("schema_version"),
                        rs.getTimestamp("last_heartbeat_at") != null
                                ? rs.getTimestamp("last_heartbeat_at").toInstant() : null,
                        rs.getString("health"),
                        rs.getString("license_status")))
                .list();
    }

    public Optional<CpInstanceLicense> findInstanceLicense(Long instanceId) {
        return jdbc.sql("""
                        select i.id, c.code as client_code, coalesce(i.license_status, 'ACTIVE') as license_status,
                               c.resource_profile, i.license_expires_at
                        from cp_instances i
                        join cp_clients c on c.id = i.client_id
                        where i.id = :id
                        """)
                .param("id", instanceId)
                .query((rs, n) -> new CpInstanceLicense(
                        rs.getLong("id"),
                        rs.getString("client_code"),
                        rs.getString("license_status"),
                        rs.getString("resource_profile"),
                        rs.getTimestamp("license_expires_at") != null
                                ? rs.getTimestamp("license_expires_at").toInstant() : null))
                .optional();
    }

    public void updateInstanceStatus(Long instanceId, String status) {
        jdbc.sql("update cp_instances set license_status = :status where id = :id")
                .param("id", instanceId)
                .param("status", status)
                .update();
    }

    // -------------------------------------------------- проверки бэкапов

    public List<CpBackupCheck> listBackupChecks(int limit) {
        return jdbc.sql("""
                        select b.id, c.code as client_code, b.is_success,
                               b.check_duration_sec, b.details, b.verified_at
                        from cp_backup_verifications b
                        join cp_clients c on c.id = b.client_id
                        order by b.verified_at desc
                        limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new CpBackupCheck(
                        rs.getLong("id"),
                        rs.getString("client_code"),
                        rs.getBoolean("is_success"),
                        rs.getInt("check_duration_sec"),
                        rs.getString("details"),
                        rs.getTimestamp("verified_at").toInstant()))
                .list();
    }

    private static CpClient mapClient(ResultSet rs, int rowNum) throws SQLException {
        return new CpClient(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("resource_profile"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record CpClient(Long id, String code, String name, String resourceProfile, Instant createdAt) {}

    public record CpFleetItem(Long instanceId, String clientCode, String clientName,
                              String resourceProfile, String environment, String url,
                              String appVersion, String schemaVersion,
                              Instant lastHeartbeatAt, String health, String licenseStatus) {}

    public record CpInstanceLicense(Long id, String clientCode, String licenseStatus,
                                    String resourceProfile, Instant expiresAt) {}

    public record CpBackupCheck(Long id, String clientCode, boolean success,
                                int durationSec, String details, Instant verifiedAt) {}
}
