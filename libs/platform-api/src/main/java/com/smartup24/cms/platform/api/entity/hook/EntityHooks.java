package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

/**
 * The second file of an entity's author (ADR-0032, 6.5): a bean, one per entity, whose methods the runtime calls at
 * fixed steps of ADR-0032, 6.3. A hook sees values already checked by the declaration and the rules; it writes the data
 * of its own module through its repository and another module's through that module's service, never SQL of its own.
 *
 * <ul>
 *   <li>{@link #beforeSave} and {@link #beforeDelete} — step 9, in the transaction: may change values, add problems
 *       ({@link EntitySave#reject}) or refuse with an {@link EntityRefusal};
 *   <li>{@link #beforeArchive} — before an archive or a restore is written, in the transaction: may refuse it with an
 *       {@link EntityRefusal} (ADR-0032, 5.4);
 *   <li>{@link #afterSave} and {@link #afterDelete} — step 12, in the same transaction: an exception rolls everything
 *       back;
 *   <li>{@link #afterCommit} — step 14, after the commit: a failure is logged and does not change the answer.
 * </ul>
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public interface EntityHooks {

    /** The code of the declared entity ({@code sales.orders}). */
    String entity();

    default void beforeSave(EntitySave save) {}

    default void afterSave(EntitySave save) {}

    default void beforeDelete(EntityDelete delete) {}

    default void afterDelete(EntityDelete delete) {}

    default void beforeArchive(EntityArchive archive) {}

    default void afterCommit(EntityCommitted committed) {}
}
