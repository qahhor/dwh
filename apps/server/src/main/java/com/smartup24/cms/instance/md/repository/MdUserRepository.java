package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.web.Revisions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MdUserRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;

    public MdUserRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "md_users");
    }

    public UserRecord create(UserCreateData data, Long createdBy) {
        String attributesJson = jsonColumns.object(data.attributes());

        return jdbcClient
                .sql("""
                insert into md_users (name, login, email, phone, password_hash, state, manager_id,
                                     language, timezone, avatar_file_id, attributes, is_2fa_enabled,
                                     force_password_change, created_at, modified_at, created_by, modified_by)
                values (:name, :login, :email, :phone, :passwordHash, :state, :managerId,
                        :language, :timezone, :avatarFileId, cast(:attributes as jsonb), :is2faEnabled,
                        :forcePasswordChange, now(), now(), :createdBy, :createdBy)
                returning id, name, login, email, phone, password_hash, state, manager_id, language, timezone,
                          avatar_file_id, attributes::text as attributes_str, is_2fa_enabled, force_password_change,
                          password_changed_at, created_at, modified_at, created_by, modified_by, auth_version, revision
                """)
                .param("name", data.name())
                .param("login", data.login().toLowerCase().trim())
                .param("email", data.email().toLowerCase().trim())
                .param("phone", data.phone())
                .param("passwordHash", data.passwordHash())
                .param("state", data.state() != null ? data.state() : "A")
                .param("managerId", data.managerId())
                .param("language", data.language() != null ? data.language() : "ru")
                .param("timezone", data.timezone() != null ? data.timezone() : "UTC")
                .param("avatarFileId", data.avatarFileId())
                .param("attributes", attributesJson)
                .param("is2faEnabled", data.is2faEnabled())
                .param("forcePasswordChange", data.forcePasswordChange())
                .param("createdBy", createdBy)
                .query(this::mapUser)
                .single();
    }

    public Optional<UserRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, name, login, email, phone, password_hash, state, manager_id, language, timezone,
                       avatar_file_id, attributes::text as attributes_str, is_2fa_enabled, force_password_change,
                       password_changed_at, created_at, modified_at, created_by, modified_by, auth_version, revision
                from md_users
                where id = :id
                """).param("id", id).query(this::mapUser).optional();
    }

    public Optional<UserRecord> findByLoginOrEmail(String identifier) {
        String clean = identifier.toLowerCase().trim();
        return jdbcClient.sql("""
                select id, name, login, email, phone, password_hash, state, manager_id, language, timezone,
                       avatar_file_id, attributes::text as attributes_str, is_2fa_enabled, force_password_change,
                       password_changed_at, created_at, modified_at, created_by, modified_by, auth_version, revision
                from md_users
                where login = :ident or email = :ident
                """).param("ident", clean).query(this::mapUser).optional();
    }

    /** The ids of active users with two-factor sign-in on. */
    public List<Long> activeTwoFactorUserIds() {
        return jdbcClient
                .sql("select id from md_users where is_2fa_enabled and state = 'A' order by id")
                .query(Long.class)
                .list();
    }

    public Optional<UserRecord> findByLogin(String login) {
        return findByLoginOrEmail(login);
    }

    public Optional<UserRecord> findByEmail(String email) {
        return jdbcClient
                .sql("""
                select id, name, login, email, phone, password_hash, state, manager_id, language, timezone,
                       avatar_file_id, attributes::text as attributes_str, is_2fa_enabled, force_password_change,
                       password_changed_at, created_at, modified_at, created_by, modified_by, auth_version, revision
                from md_users
                where email = :email
                """)
                .param("email", email.toLowerCase().trim())
                .query(this::mapUser)
                .optional();
    }

    public boolean existsByLogin(String login) {
        return jdbcClient
                        .sql("select count(*) from md_users where login = :login")
                        .param("login", login.toLowerCase().trim())
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public boolean existsByEmail(String email) {
        return jdbcClient
                        .sql("select count(*) from md_users where email = :email")
                        .param("email", email.toLowerCase().trim())
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public boolean existsByPhone(String phone) {
        if (phone == null || phone.isBlank()) return false;
        return jdbcClient
                        .sql("select count(*) from md_users where phone = :phone and state = 'A'")
                        .param("phone", phone.trim())
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** Whether an active user other than {@code except} (null: any) has the phone. */
    public boolean phoneTaken(String phone, @Nullable Long except) {
        if (phone.isBlank()) return false;
        return jdbcClient
                        .sql("""
                        select count(*) from md_users
                        where phone = :phone and state = 'A' and id is distinct from :except
                        """)
                        .param("phone", phone.strip())
                        .param("except", except)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /**
     * The part of an anonymisation outside the user's fields (FR-USR-8): the password hash, the avatar and the custom
     * values; the fields are replaced by the entity's action.
     */
    public void wipeCredentials(long userId) {
        jdbcClient.sql("""
                update md_users
                set password_hash = 'ANONYMIZED',
                    avatar_file_id = null,
                    attributes = '{}'::jsonb,
                    modified_at = now(),
                    revision = revision + 1
                where id = :userId
                """).param("userId", userId).update();
    }

    public boolean compareAndSetPassword(
            Long userId, long expectedAuthVersion, String expectedPasswordHash, String newPasswordHash) {
        return jdbcClient
                        .sql("""
                update md_users
                set password_hash = :newPasswordHash, password_changed_at = now(),
                    force_password_change = false, modified_at = now(), revision = revision + 1
                where id = :userId and state = 'A' and auth_version = :expectedAuthVersion
                  and password_hash is not distinct from :expectedPasswordHash
                """)
                        .param("userId", userId)
                        .param("expectedAuthVersion", expectedAuthVersion)
                        .param("expectedPasswordHash", expectedPasswordHash)
                        .param("newPasswordHash", newPasswordHash)
                        .update()
                == 1;
    }

    /** The global access invalidator owns the sole increment; overflow must fail the transaction. */
    public void incrementAuthenticationVersion(Long userId) {
        int changed = jdbcClient
                .sql("update md_users set auth_version = auth_version + 1, revision = revision + 1 where id = :userId")
                .param("userId", userId)
                .update();
        if (changed != 1) throw ApiException.invalidCredentials();
    }

    public void updateLanguage(Long userId, String language, Long modifiedBy) {
        jdbcClient
                .sql("""
                update md_users
                set language = :language, modified_at = now(), modified_by = :modifiedBy, revision = revision + 1
                where id = :userId
                """)
                .param("userId", userId)
                .param("language", language)
                .param("modifiedBy", modifiedBy)
                .update();
    }

    /**
     * Claims the next revision of a user for a change of what belongs to the user outside the profile row: its roles
     * and personal rights (plan 10/10, item 3.6). A change made from an older revision is refused like a profile save.
     */
    public long nextRevision(Long userId, long expectedRevision) {
        return jdbcClient
                .sql("""
                update md_users set modified_at = now(), revision = revision + 1
                where id = :userId and revision = :expectedRevision
                returning revision
                """)
                .param("userId", userId)
                .param("expectedRevision", expectedRevision)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    /** Reads a user row with every column the repository selects. */
    public UserRecord mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Map<String, Object> attrs = jsonColumns.readObject(rs.getString("attributes_str"));
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("login"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("password_hash"),
                rs.getString("state"),
                rs.getObject("manager_id") != null ? rs.getLong("manager_id") : null,
                rs.getString("language"),
                rs.getString("timezone"),
                rs.getObject("avatar_file_id") != null ? UUID.fromString(rs.getString("avatar_file_id")) : null,
                attrs,
                rs.getBoolean("is_2fa_enabled"),
                rs.getBoolean("force_password_change"),
                rs.getTimestamp("password_changed_at") != null
                        ? rs.getTimestamp("password_changed_at").toInstant()
                        : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("modified_at").toInstant(),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null,
                rs.getObject("modified_by") != null ? rs.getLong("modified_by") : null,
                rs.getLong("auth_version"),
                rs.getLong("revision"));
    }

    /** How many ACTIVE users have the given role (protects the last administrator). */
    public int countUsersWithRole(Long roleId) {
        return jdbcClient.sql("""
                        select count(*) from md_user_roles ur
                        join md_users u on u.id = ur.user_id and u.state = 'A'
                        where ur.role_id = :roleId
                        """).param("roleId", roleId).query(Integer.class).single();
    }

    public record UserRecord(
            Long id,
            String name,
            String login,
            String email,
            String phone,
            String passwordHash,
            String state,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            boolean is2faEnabled,
            boolean forcePasswordChange,
            Instant passwordChangedAt,
            Instant createdAt,
            Instant modifiedAt,
            Long createdBy,
            Long modifiedBy,
            @com.fasterxml.jackson.annotation.JsonIgnore long authenticationVersion,
            long revision) {}

    public record UserCreateData(
            String name,
            String login,
            String email,
            String phone,
            String passwordHash,
            String state,
            Long managerId,
            String language,
            String timezone,
            UUID avatarFileId,
            Map<String, Object> attributes,
            boolean is2faEnabled,
            boolean forcePasswordChange) {}
}
