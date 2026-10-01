package com.smartup24.cms.instance.fnd.api;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Versions with an effective date for every module that declares a versions table with
 * {@code fnd_versioning_enable}. Lifecycle: {@link #createDraft} → {@link #updateDraft} → {@link #publish} (closes
 * the previous version) → {@link #supersede} if the version was published by mistake. Rule violations are
 * {@link ConstraintViolationException} with a {@code fnd_version_*} code; a stale lock is
 * {@link StaleVersionException}.
 *
 * <p>Part of the foundation's contract (plan 10/10, item 4.2): callers depend on this interface, not on its
 * implementation, which may move to another package.
 */
public interface FndVersions {

    /** Creates a draft with the next number; at most one draft per header. Returns the new version number. */
    int createDraft(String versionsTable, long headerId, FndActor actor);

    /** Publishes a draft and closes the open end of the previous version on the day before {@code validFrom}. */
    void publish(
            String versionsTable, long headerId, int version, LocalDate validFrom, LocalDate validTo, FndActor actor);

    /** Withdraws a version published by mistake: its interval is freed and the row stays as history. */
    void supersede(String versionsTable, long headerId, int version, FndActor actor);

    /** Updates the module's own columns of a draft under optimistic locking. */
    void updateDraft(
            String versionsTable,
            long headerId,
            int version,
            int expectedLockVersion,
            Map<String, Object> columns,
            FndActor actor);

    /** The number of the version in effect on the date; drafts and withdrawn versions are ignored. */
    Optional<Integer> versionAt(String versionsTable, long headerId, LocalDate date);

    /** A version row as stored. */
    Optional<FndVersion> find(String versionsTable, long headerId, int version);
}
