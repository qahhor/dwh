package com.smartup24.cms.instance.jobs.api;

/**
 * Whether the runner may take jobs now. The application reads the switch {@code jobs_enabled} from the instance
 * settings through the settings' owner ({@code config.jobs.JobSwitchConfiguration}, ADR-0026); a runner built by
 * hand runs always.
 */
@FunctionalInterface
public interface JobSwitch {

    /** The switch key in the instance settings; with no such setting, jobs run. */
    String KEY = "jobs_enabled";

    /** A switch that never stops the runner, for runners built by hand. */
    JobSwitch ON = () -> true;

    /** True while jobs may run; asked before every claim. */
    boolean jobsEnabled();

    /** The stored value of the switch read as the runner reads it: anything but {@code false} lets jobs run. */
    static boolean enabled(String stored) {
        return stored == null || !"false".equalsIgnoreCase(stored.trim());
    }
}
