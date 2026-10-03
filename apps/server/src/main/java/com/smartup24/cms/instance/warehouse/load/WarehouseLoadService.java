package com.smartup24.cms.instance.warehouse.load;

import com.smartup24.cms.instance.common.actor.ActorError;
import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import com.smartup24.cms.instance.common.error.ConstraintErrors;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.instance.warehouse.api.WarehouseError;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoad;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.instance.warehouse.repository.LoadRepository;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Load versions and the package log; the SQL is in {@link LoadRepository} (plan 10/10, item 4.2).
 *
 * <p>Apply protocol: {@link #begin} creates a version in {@code pending}; the {@code RawWriter} facade then writes
 * rows into pg-dwh under that {@code load_id}; {@link #apply} moves the version to {@code applied} and supersedes the
 * previous load of the same source for the same period. On failure, {@link #fail} is called: the rows stay, and the
 * maintenance job {@code fnd.load_cleanup} deletes them. Allowed transitions: {@code pending→applied},
 * {@code pending→failed}, {@code applied→superseded}.
 */
@Service
public class WarehouseLoadService implements WarehouseLoads {

    private static final List<ConstraintCode> CODES =
            ConstraintErrors.codes(WarehouseError.values(), ActorError.values());

    private final LoadRepository loads;
    private final AuditActorContext actors;

    @Autowired
    public WarehouseLoadService(LoadRepository loads, AuditActorContext actors) {
        this.loads = loads;
        this.actors = actors;
    }

    /** A service built by hand, for tests and tools. */
    public WarehouseLoadService(JdbcClient jdbc, AuditActorContext actors) {
        this(new LoadRepository(jdbc), actors);
    }

    /** Opens a load version. A repeated {@code package_ref} is rejected by {@code fnd_loads_uk_package_ref}. */
    @Transactional
    @Override
    public long begin(
            String sourceCode,
            UUID packageRef,
            LocalDate periodFrom,
            LocalDate periodTo,
            String formatVersion,
            AuditActor actor) {
        actors.apply(actor);
        return ConstraintErrors.translating(
                CODES, () -> loads.insertPending(sourceCode, packageRef, periodFrom, periodTo, formatVersion));
    }

    /**
     * Applies a load. The row counters must add up ({@code accepted + rejected = total}), otherwise the
     * {@code fnd_loads_ck_rows} constraint rejects the update. The previously applied load of the same source for the
     * same period becomes {@code superseded} and gets a {@code superseded_by} reference to this one.
     */
    @Transactional
    @Override
    public void apply(long loadId, int rowsTotal, int rowsAccepted, int rowsRejected, AuditActor actor) {
        WarehouseLoad load = lockPending(loadId);
        actors.apply(actor);
        int updated = ConstraintErrors.translating(
                CODES, () -> loads.markApplied(loadId, rowsTotal, rowsAccepted, rowsRejected, actor.name()));
        requireUpdated(updated);
        ConstraintErrors.translating(CODES, () -> {
            loads.supersedeOthers(loadId, load.sourceCode(), load.periodFrom(), load.periodTo());
            return null;
        });
    }

    /** Marks a load failed and writes the reason to the log; a call without a reason is rejected. */
    @Transactional
    @Override
    public void fail(long loadId, String reason, AuditActor actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Причина сбоя не задана: загрузка без причины не отмечается");
        }
        WarehouseLoad load = lockPending(loadId);
        actors.apply(actor);
        int updated = ConstraintErrors.translating(CODES, () -> loads.markFailed(loadId));
        requireUpdated(updated);
        log(load.packageRef(), "failed", WarehouseLoad.PENDING, WarehouseLoad.FAILED, actor, reason, null);
    }

    /**
     * Writes a package log row. {@code load_id} is filled in once the load version has been applied; before that the
     * log is keyed by {@code package_ref}. The {@code fnd_load_log_append_only()} trigger forbids updating or deleting
     * log rows.
     */
    @Transactional
    @Override
    public void log(
            UUID packageRef,
            String event,
            String fromStatus,
            String toStatus,
            AuditActor actor,
            String note,
            String fileSha) {
        if (actor == null) {
            throw new ConstraintViolationException(ActorError.AUDIT_ACTOR_MISSING);
        }
        actors.apply(actor);
        Long loadId = loads.appliedLoadId(packageRef).orElse(null);
        ConstraintErrors.translating(CODES, () -> {
            loads.insertLog(packageRef, loadId, event, fromStatus, toStatus, actor.name(), note, fileSha);
            return null;
        });
    }

    /** The current data versions of a source: applied loads only. */
    @Transactional(readOnly = true)
    @Override
    public List<Long> appliedLoadIds(String sourceCode) {
        return loads.appliedLoadIds(sourceCode);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<WarehouseLoad> find(long loadId) {
        return loads.find(loadId);
    }

    /**
     * Reads a load under a row lock ({@code for update}) and requires the {@code pending} status. A concurrent
     * {@code apply}/{@code fail} of the same load waits for the commit and then sees the new status; a row write still
     * in progress ({@code RawWriter.write} holds {@code for share}) is also waited for.
     */
    private WarehouseLoad lockPending(long loadId) {
        WarehouseLoad load = loads.lock(loadId)
                .orElseThrow(() -> new ConstraintViolationException(WarehouseError.FND_LOAD_STATUS_TRANSITION));
        if (!WarehouseLoad.PENDING.equals(load.status())) {
            throw new ConstraintViolationException(WarehouseError.FND_LOAD_STATUS_TRANSITION);
        }
        return load;
    }

    /** A status transition is one UPDATE conditioned on the current status: 0 rows means the state already moved on. */
    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new ConstraintViolationException(WarehouseError.FND_LOAD_STATUS_TRANSITION);
        }
    }
}
