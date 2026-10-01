package com.smartup24.cms.instance.common.entity.hook;

/**
 * The second file of an entity's author (ADR-0032, 6.5): a bean, one per entity, whose methods the runtime calls at
 * fixed steps of ADR-0032, 6.3. A hook sees values already checked by the declaration and the rules; it writes the data
 * of its own module through its repository and another module's through that module's service, never SQL of its own.
 *
 * <ul>
 *   <li>{@link #beforeSave} and {@link #beforeDelete} — step 9, in the transaction: may change values, add problems
 *       ({@link EntitySave#reject}) or refuse with an {@code ApiException};
 *   <li>{@link #afterSave} and {@link #afterDelete} — step 12, in the same transaction: an exception rolls everything
 *       back;
 *   <li>{@link #afterCommit} — step 14, after the commit: a failure is logged and does not change the answer.
 * </ul>
 */
public interface EntityHooks {

    /** The code of the declared entity ({@code sales.orders}). */
    String entity();

    default void beforeSave(EntitySave save) {}

    default void afterSave(EntitySave save) {}

    default void beforeDelete(EntityDelete delete) {}

    default void afterDelete(EntityDelete delete) {}

    default void afterCommit(EntityCommitted committed) {}
}
