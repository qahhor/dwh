package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.md.repository.MdSystemAccountRepository;
import com.smartup24.cms.platform.api.actor.AuditActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The actors of audited changes and setting them into the database session. The audit trigger
 * ({@code fnd_audit_trigger}) requires a numeric {@code app.user_id} on any change to the tables it guards, so every
 * writer first calls {@link #apply(AuditActor)} inside its own transaction. md implements the platform contract
 * because it owns the users, the technical account included (plan 10/10, item 4.2).
 */
@Component
public class MdAuditActors implements AuditActorContext {

    private static final String SYSTEM_NAME = "System (DW jobs)";
    private static final String SYSTEM_EMAIL = "system@localhost";

    private final MdSystemAccountRepository accounts;
    private volatile Long systemUserId;

    @Autowired
    public MdAuditActors(MdSystemAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Actors built by hand, for tests and tools. */
    public MdAuditActors(JdbcClient jdbc) {
        this(new MdSystemAccountRepository(jdbc));
    }

    /**
     * Technical account of jobs and seeds, shown as {@code system} in the journals. It is created by code at startup
     * ({@link MdSystemAccountBootstrap}) or on first access; migrations do not create users.
     */
    @Override
    public AuditActor system() {
        Long id = systemUserId;
        if (id == null) {
            id = accounts.findUserId(AuditActor.SYSTEM).orElseGet(this::createAfterInstanceBootstrap);
            systemUserId = id;
        }
        return new AuditActor(id, AuditActor.SYSTEM);
    }

    /**
     * The platform creates the first administrator only when {@code md_users} is empty, so the technical account must
     * not be created before the instance initial setup; otherwise no administrator would ever appear.
     */
    private long createAfterInstanceBootstrap() {
        if (accounts.countUsers() == 0) {
            throw new IllegalStateException(
                    "Account " + AuditActor.SYSTEM + " requested before the instance was set up: md_users has no user");
        }
        return ensureSystemUser(accounts);
    }

    /** Creates the account if missing; call only after the instance initial setup (a startup step). */
    public AuditActor ensureSystem() {
        systemUserId = ensureSystemUser(accounts);
        return new AuditActor(systemUserId, AuditActor.SYSTEM);
    }

    /** Returns the id of the technical account, creating it if missing (idempotent). */
    public static long ensureSystemUser(JdbcClient jdbc) {
        return ensureSystemUser(new MdSystemAccountRepository(jdbc));
    }

    private static long ensureSystemUser(MdSystemAccountRepository accounts) {
        return ensureUser(accounts, AuditActor.SYSTEM, SYSTEM_NAME, SYSTEM_EMAIL);
    }

    /** An account without a password in state {@code P}: nobody can sign in with it. */
    static long ensureUser(JdbcClient jdbc, String login, String name, String email) {
        return ensureUser(new MdSystemAccountRepository(jdbc), login, name, email);
    }

    private static long ensureUser(MdSystemAccountRepository accounts, String login, String name, String email) {
        return accounts.findUserId(login).orElseGet(() -> {
            accounts.insertTechnicalUser(login, name, email);
            return accounts.findUserId(login)
                    .orElseThrow(() -> new IllegalStateException("Account " + login + " is not created in md_users"));
        });
    }

    @Override
    public AuditActor user(long userId) {
        return AuditActor.user(userId);
    }

    /**
     * Sets {@code app.user_id} on the current transaction, so call it only inside {@code @Transactional}: otherwise
     * the setting is lost together with the connection and the audit trigger fails with code
     * {@code audit_actor_missing}.
     */
    @Override
    public void apply(AuditActor actor) {
        accounts.setAuditActor(actor.userId());
    }
}
