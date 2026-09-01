package com.greenwhite.dwh.cp.instance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class CpInstanceCredentialRepository {

    private final JdbcClient jdbc;

    public CpInstanceCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void createEnrollment(long instanceId,
                                 String tokenHash,
                                 Instant expiresAt,
                                 long actorUserId) {
        jdbc.sql("""
                        insert into cp_instance_enrollment_tokens(
                            instance_id, token_hash, expires_at, created_by)
                        values (:instanceId, :tokenHash, :expiresAt, :actorUserId)
                        """)
                .param("instanceId", instanceId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", dbTime(expiresAt))
                .param("actorUserId", actorUserId)
                .update();
    }

    public Optional<ExchangedCredential> exchange(String enrollmentHash,
                                                  String credentialHash,
                                                  Instant now) {
        return jdbc.sql("""
                        with candidate as (
                            select id
                            from cp_instance_enrollment_tokens
                            where token_hash = :enrollmentHash
                              and consumed_at is null
                              and expires_at > :now
                            for update
                        ), consumed as (
                            update cp_instance_enrollment_tokens enrollment
                            set consumed_at = :now
                            from candidate
                            where enrollment.id = candidate.id
                            returning enrollment.instance_id
                        ), created as (
                            insert into cp_instance_credentials(
                                instance_id, credential_hash, activated_at)
                            select instance_id, :credentialHash, :now
                            from consumed
                            returning id, instance_id
                        )
                        select id, instance_id
                        from created
                        """)
                .param("enrollmentHash", enrollmentHash)
                .param("credentialHash", credentialHash)
                .param("now", dbTime(now))
                .query((rs, rowNum) -> new ExchangedCredential(
                        rs.getLong("id"),
                        rs.getLong("instance_id")))
                .optional();
    }

    public Optional<CpInstancePrincipal> authenticate(String credentialHash, Instant now) {
        return jdbc.sql("""
                        with touched as (
                            update cp_instance_credentials
                            set last_used_at = :now
                            where credential_hash = :credentialHash
                              and activated_at <= :now
                              and revoked_at is null
                              and (expires_at is null or expires_at > :now)
                            returning id, instance_id
                        )
                        select touched.id as credential_id,
                               instance.id as instance_id,
                               client.id as client_id,
                               client.code as client_code
                        from touched
                        join cp_instances instance on instance.id = touched.instance_id
                        join cp_clients client on client.id = instance.client_id
                        """)
                .param("credentialHash", credentialHash)
                .param("now", dbTime(now))
                .query((rs, rowNum) -> new CpInstancePrincipal(
                        rs.getLong("instance_id"),
                        rs.getLong("client_id"),
                        rs.getString("client_code"),
                        rs.getLong("credential_id")))
                .optional();
    }

    public Optional<Long> rotate(long instanceId,
                                 long credentialId,
                                 String successorHash,
                                 Instant now,
                                 Instant previousValidUntil) {
        return jdbc.sql("""
                        with predecessor as (
                            select id, instance_id
                            from cp_instance_credentials
                            where id = :credentialId
                              and instance_id = :instanceId
                              and activated_at <= :now
                              and revoked_at is null
                              and successor_id is null
                              and (expires_at is null or expires_at > :now)
                            for update
                        ), successor as (
                            insert into cp_instance_credentials(
                                instance_id, credential_hash, activated_at, predecessor_id)
                            select instance_id, :successorHash, :now, id
                            from predecessor
                            returning id, predecessor_id
                        ), updated as (
                            update cp_instance_credentials current_credential
                            set expires_at = least(
                                    coalesce(current_credential.expires_at, :previousValidUntil),
                                    :previousValidUntil),
                                successor_id = successor.id
                            from successor
                            where current_credential.id = successor.predecessor_id
                            returning current_credential.successor_id
                        )
                        select successor_id
                        from updated
                        """)
                .param("instanceId", instanceId)
                .param("credentialId", credentialId)
                .param("successorHash", successorHash)
                .param("now", dbTime(now))
                .param("previousValidUntil", dbTime(previousValidUntil))
                .query(Long.class)
                .optional();
    }

    public int revoke(long instanceId, long credentialId, Instant now) {
        return jdbc.sql("""
                        update cp_instance_credentials
                        set revoked_at = :now
                        where id = :credentialId
                          and instance_id = :instanceId
                          and revoked_at is null
                        """)
                .param("instanceId", instanceId)
                .param("credentialId", credentialId)
                .param("now", dbTime(now))
                .update();
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record ExchangedCredential(long credentialId, long instanceId) {
    }
}
