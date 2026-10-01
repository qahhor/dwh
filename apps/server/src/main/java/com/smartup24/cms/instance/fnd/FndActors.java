package com.smartup24.cms.instance.fnd;

import com.smartup24.cms.instance.fnd.api.FndActor;
import com.smartup24.cms.instance.fnd.api.FndActorContext;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Foundation actors and setting them into the database session. The platform audit ({@code fnd_audit_trigger})
 * requires a numeric {@code app.user_id} on any change to fnd tables, so every foundation service first
 * calls {@link #apply(FndActor)} inside its own transaction.
 */
@Component
public class FndActors implements FndActorContext {

    private static final String SYSTEM_NAME = "System (DW jobs)";
    private static final String SYSTEM_EMAIL = "system@localhost";

    private final JdbcClient jdbc;
    private volatile Long systemUserId;

    public FndActors(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Technical account of jobs and seeds, shown as {@code system} in foundation logs. It is created by code
     * at startup ({@link FndSystemUserBootstrap}) or on first access; migrations
     * do not create users.
     */
    @Override
    public FndActor system() {
        Long id = systemUserId;
        if (id == null) {
            id = findUserId(jdbc, FndPref.SYSTEM_ACTOR).orElseGet(this::createAfterInstanceBootstrap);
            systemUserId = id;
        }
        return new FndActor(id, FndPref.SYSTEM_ACTOR);
    }

    /**
     * The platform creates the first administrator only when {@code md_users} is empty, so the foundation
     * account must not be created before the instance initial setup; otherwise no administrator would ever appear.
     */
    private long createAfterInstanceBootstrap() {
        Long users = jdbc.sql("select count(*) from md_users").query(Long.class).single();
        if (users == 0) {
            throw new IllegalStateException("Учётка " + FndPref.SYSTEM_ACTOR
                    + " запрошена до первичной настройки экземпляра: в md_users нет ни одного пользователя");
        }
        return ensureSystemUser(jdbc);
    }

    /** Creates the account if missing; call only after the instance initial setup (a foundation startup step). */
    public FndActor ensureSystem() {
        systemUserId = ensureSystemUser(jdbc);
        return new FndActor(systemUserId, FndPref.SYSTEM_ACTOR);
    }

    /** Returns the id of the technical account, creating it if missing (idempotent). */
    public static long ensureSystemUser(JdbcClient jdbc) {
        return ensureUser(jdbc, FndPref.SYSTEM_ACTOR, SYSTEM_NAME, SYSTEM_EMAIL);
    }

    /** An account without a password in state {@code P}: nobody can sign in with it. */
    static long ensureUser(JdbcClient jdbc, String login, String name, String email) {
        return findUserId(jdbc, login).orElseGet(() -> {
            jdbc.sql("""
                            insert into md_users (name, login, email, state, language, timezone)
                            values (:name, :login, :email, 'P', 'uz', 'UTC')
                            on conflict (login) do nothing
                            """)
                    .param("name", name)
                    .param("login", login)
                    .param("email", email)
                    .update();
            return findUserId(jdbc, login)
                    .orElseThrow(() -> new IllegalStateException("Учётка " + login + " не создана в md_users"));
        });
    }

    private static Optional<Long> findUserId(JdbcClient jdbc, String login) {
        return jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .optional();
    }

    @Override
    public FndActor user(long userId) {
        return FndActor.user(userId);
    }

    /**
     * Sets {@code app.user_id} on the current transaction (the third argument {@code true} is is_local),
     * so call it only inside {@code @Transactional}: otherwise the setting is lost together
     * with the connection and the audit trigger fails with code {@code audit_actor_missing}.
     */
    @Override
    public void apply(FndActor actor) {
        jdbc.sql("select set_config('app.user_id', :id, true)")
                .param("id", String.valueOf(actor.userId()))
                .query(String.class)
                .single();
    }
}
