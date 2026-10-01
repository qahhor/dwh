package com.smartup24.cms.instance.fnd.migration;

/**
 * The class name the migrate step had before plan 10/10, item 4.2: a Compose file of an earlier release still starts
 * {@code java -cp app.jar com.smartup24.cms.instance.fnd.migration.MigrateMain}. The launcher finds the inherited
 * {@code main}, so the alias runs the same step as
 * {@link com.smartup24.cms.instance.warehouse.migration.MigrateMain}. It is removed after the transition period of
 * the configuration names (2026-12-31, ADR-0027, ADR-0030).
 *
 * @deprecated start {@code com.smartup24.cms.instance.warehouse.migration.MigrateMain}
 */
@Deprecated(forRemoval = true)
public final class MigrateMain extends com.smartup24.cms.instance.warehouse.migration.MigrateMain {

    MigrateMain() {}
}
