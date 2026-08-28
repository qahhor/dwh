package com.greenwhite.dwh.cp.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class CpUserRepository {

    private final JdbcClient jdbc;

    public CpUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CpUser> findByLogin(String login) {
        return jdbc.sql("""
                        select id, name, login, email, password_hash, state, created_at
                        from cp_users where lower(login) = lower(:login)
                        """)
                .param("login", login)
                .query(CpUserRepository::map)
                .optional();
    }

    public Optional<CpUser> findById(Long id) {
        return jdbc.sql("""
                        select id, name, login, email, password_hash, state, created_at
                        from cp_users where id = :id
                        """)
                .param("id", id)
                .query(CpUserRepository::map)
                .optional();
    }

    public long count() {
        return jdbc.sql("select count(*) from cp_users").query(Long.class).single();
    }

    public Long create(String name, String login, String email, String passwordHash) {
        return jdbc.sql("""
                        insert into cp_users (name, login, email, password_hash, state)
                        values (:name, :login, :email, :hash, 'A')
                        returning id
                        """)
                .param("name", name)
                .param("login", login)
                .param("email", email)
                .param("hash", passwordHash)
                .query(Long.class)
                .single();
    }

    public void assignRole(Long userId, String roleCode) {
        jdbc.sql("""
                        insert into cp_user_roles (user_id, role_id)
                        select :userId, id from cp_roles where code = :code
                        on conflict do nothing
                        """)
                .param("userId", userId)
                .param("code", roleCode)
                .update();
    }

    public Set<String> getRoles(Long userId) {
        return Set.copyOf(jdbc.sql("""
                        select r.code from cp_user_roles ur
                        join cp_roles r on r.id = ur.role_id
                        where ur.user_id = :userId
                        """)
                .param("userId", userId)
                .query(String.class)
                .list());
    }

    public List<CpUser> list() {
        return jdbc.sql("""
                        select id, name, login, email, password_hash, state, created_at
                        from cp_users order by login
                        """)
                .query(CpUserRepository::map)
                .list();
    }

    private static CpUser map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CpUser(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("login"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record CpUser(Long id, String name, String login, String email,
                         String passwordHash, String state, Instant createdAt) {}
}
