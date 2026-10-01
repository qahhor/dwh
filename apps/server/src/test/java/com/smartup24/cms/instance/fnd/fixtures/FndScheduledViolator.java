package com.smartup24.cms.instance.fnd.fixtures;

import org.springframework.scheduling.annotation.Scheduled;

/** A violator fixture: a Spring scheduler in the fnd package. The rule must reject it. */
public final class FndScheduledViolator {

    private FndScheduledViolator() {}

    @Scheduled(fixedDelay = 60_000)
    public static void tick() {}
}
