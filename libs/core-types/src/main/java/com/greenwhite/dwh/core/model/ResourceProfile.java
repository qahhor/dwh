package com.greenwhite.dwh.core.model;

/**
 * Resource profiles for single-tenant instances according to ADR-0004 / ADR-0010.
 */
public enum ResourceProfile {
    S("Small", 2, 4, 100),
    M("Medium", 4, 8, 500),
    L("Large", 8, 16, 2000);

    private final String title;
    private final int cpuCores;
    private final int ramGb;
    private final int targetUsers;

    ResourceProfile(String title, int cpuCores, int ramGb, int targetUsers) {
        this.title = title;
        this.cpuCores = cpuCores;
        this.ramGb = ramGb;
        this.targetUsers = targetUsers;
    }

    public String getTitle() {
        return title;
    }

    public int getCpuCores() {
        return cpuCores;
    }

    public int getRamGb() {
        return ramGb;
    }

    public int getTargetUsers() {
        return targetUsers;
    }
}
