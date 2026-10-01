package com.smartup24.cms.instance.fnd.api;

import com.smartup24.cms.instance.common.actor.AuditActor;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Load versions and the package log.
 *
 * <p>Apply protocol: {@link #begin} creates a version in {@code pending}; {@link FndRawWriter} then writes the rows
 * into pg-dwh under that {@code load_id}; {@link #apply} moves the version to {@code applied} and supersedes the
 * previous load of the same source for the same period. On failure, {@link #fail} is called. Allowed transitions:
 * {@code pending→applied}, {@code pending→failed}, {@code applied→superseded}.
 *
 * <p>Part of the foundation's contract (plan 10/10, item 4.2): callers depend on this interface, not on its
 * implementation, which may move to another package.
 */
public interface FndLoads {

    /** Opens a load version. A repeated {@code package_ref} is rejected by {@code fnd_loads_uk_package_ref}. */
    long begin(
            String sourceCode,
            UUID packageRef,
            LocalDate periodFrom,
            LocalDate periodTo,
            String formatVersion,
            AuditActor actor);

    /**
     * Applies a load. The row counters must add up ({@code accepted + rejected = total}); the previously applied load
     * of the same source for the same period becomes {@code superseded}.
     */
    void apply(long loadId, int rowsTotal, int rowsAccepted, int rowsRejected, AuditActor actor);

    /** Marks a load failed and writes the reason to the log; a call without a reason is rejected. */
    void fail(long loadId, String reason, AuditActor actor);

    /** Writes a package log row; log rows are append-only. */
    void log(
            UUID packageRef,
            String event,
            String fromStatus,
            String toStatus,
            AuditActor actor,
            String note,
            String fileSha);

    /** The current data versions of a source: applied loads only. */
    List<Long> appliedLoadIds(String sourceCode);

    /** A load version by its id. */
    Optional<FndLoad> find(long loadId);
}
