package com.smartup24.cms.instance.jobs.fixtures;

import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;

/** A violator fixture: a class of the job queue that knows a warehouse type. The rule must reject it. */
public final class JobsDependsOnWarehouseViolator {

    private final WarehouseLoads loads;

    public JobsDependsOnWarehouseViolator(WarehouseLoads loads) {
        this.loads = loads;
    }

    public boolean applied(String source) {
        return !loads.appliedLoadIds(source).isEmpty();
    }
}
