package com.smartup24.cms.instance.common.versioning;

import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Versions with an effective date for every module that declares a versions table with
 * {@code fnd_versioning_enable}. Lifecycle: {@link #createDraft} → {@link #updateDraft} → {@link #publish} (closes
 * the previous version) → {@link #supersede} if the version was published by mistake. Rule violations are
 * {@link ConstraintViolationException} with a {@link VersionError} code; a stale lock is
 * {@link StaleVersionException}. A violation of the module's own constraints on its versions table is left
 * untranslated for the module to translate with its codes.
 *
 * <p>A platform contract (plan 10/10, item 4.2): callers depend on this interface, not on its implementation.
 */
public interface Versions {

    /** Creates a draft with the next number; at most one draft per header. Returns the new version number. */
    int createDraft(String versionsTable, long headerId, AuditActor actor);

    /** Publishes a draft and closes the open end of the previous version on the day before {@code validFrom}. */
    void publish(
            String versionsTable,
            long headerId,
            int version,
            LocalDate validFrom,
            @Nullable LocalDate validTo,
            AuditActor actor);

    /** Withdraws a version published by mistake: its interval is freed and the row stays as history. */
    void supersede(String versionsTable, long headerId, int version, AuditActor actor);

    /** Updates the module's own columns of a draft under optimistic locking. */
    void updateDraft(
            String versionsTable,
            long headerId,
            int version,
            int expectedLockVersion,
            Map<String, Object> columns,
            AuditActor actor);

    /** The number of the version in effect on the date; drafts and withdrawn versions are ignored. */
    Optional<Integer> versionAt(String versionsTable, long headerId, LocalDate date);

    /** A version row as stored. */
    Optional<Version> find(String versionsTable, long headerId, int version);
}
