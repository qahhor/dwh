package com.smartup24.cms.instance.md.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The technical accounts of the instance and the actor of an audited change (plan 10/10, item 4.2): md owns
 * {@code md_users}, so the account of jobs and seeds is created here, and the actor is set on the session here.
 */
@Repository
public class MdSystemAccountRepository {

    private final JdbcClient jdbc;

    public MdSystemAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The id of the user with the login. */
    public Optional<Long> findUserId(String login) {
        return jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .optional();
    }

    /** How many users the instance has; 0 before its initial setup. */
    public long countUsers() {
        return jdbc.sql("select count(*) from md_users").query(Long.class).single();
    }

    /** Inserts an account without a password in state {@code P}, unless the login exists: nobody can sign in. */
    public void insertTechnicalUser(String login, String name, String email) {
        jdbc.sql("""
                        insert into md_users (name, login, email, state, language, timezone)
                        values (:name, :login, :email, 'P', 'uz', 'UTC')
                        on conflict (login) do nothing
                        """)
                .param("name", name)
                .param("login", login)
                .param("email", email)
                .update();
    }

    /** Sets {@code app.user_id} for the current transaction only (the third argument {@code true} is is_local). */
    public void setAuditActor(long userId) {
        jdbc.sql("select set_config('app.user_id', :id, true)")
                .param("id", String.valueOf(userId))
                .query(String.class)
                .single();
    }
}
