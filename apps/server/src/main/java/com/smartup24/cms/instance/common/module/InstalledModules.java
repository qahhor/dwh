package com.smartup24.cms.instance.common.module;

/**
 * The switch of an installed module as the platform needs it (ADR-0032, 2 and 6.3, step 1): an entity whose module is
 * switched off answers as an unknown one. The md module keeps the registry of modules and implements this, so
 * {@code common} depends on no module.
 */
public interface InstalledModules {

    /** Whether the module with this code is installed and switched on. */
    boolean active(String module);
}
