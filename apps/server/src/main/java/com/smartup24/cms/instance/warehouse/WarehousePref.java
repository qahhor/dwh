package com.smartup24.cms.instance.warehouse;

/** Warehouse constants: migration locations, the second database's qualifier and the migrate step's exit code. */
public final class WarehousePref {

    /** Location of the OLTP migrations. */
    public static final String OLTP_MIGRATIONS = "db/migration";
    /** Location of the pg-dwh migrations. */
    public static final String WAREHOUSE_MIGRATIONS = "db/dwh";
    /**
     * Qualifier of the second database's beans; used only inside {@code ..instance.warehouse..}. The value, the bean
     * names, the pool name and the health component stay {@code dwh}: dashboards and alerts know them (ADR-0030).
     */
    public static final String QUALIFIER = "dwh";
    /** Process exit code when the database schema does not match the expected version. */
    public static final int EXIT_SCHEMA_MISMATCH = 3;

    private WarehousePref() {}
}
