package com.smartup24.cms.instance.warehouse.fixtures;

import org.springframework.scheduling.annotation.Scheduled;

/** A violator fixture: a Spring scheduler in the warehouse package. The rule must reject it. */
public final class WarehouseScheduledViolator {

    private WarehouseScheduledViolator() {}

    @Scheduled(fixedDelay = 60_000)
    public static void tick() {}
}
