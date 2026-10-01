package com.smartup24.cms.instance.fnd;

/** Foundation constants: migration locations and the second database's qualifier. */
public final class FndPref {

    /** Location of the OLTP migrations. */
    public static final String OLTP_MIGRATIONS = "db/migration";
    /** Location of the pg-dwh migrations. */
    public static final String DWH_MIGRATIONS = "db/dwh";
    /** Qualifier of the second database's beans; used only inside {@code ..instance.fnd..}. */
    public static final String DWH = "dwh";
    /** Process exit code when the database schema does not match the expected version. */
    public static final int EXIT_SCHEMA_MISMATCH = 3;

    private FndPref() {}
}
