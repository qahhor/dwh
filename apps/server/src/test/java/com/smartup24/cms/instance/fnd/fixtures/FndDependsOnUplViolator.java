package com.smartup24.cms.instance.fnd.fixtures;

import com.smartup24.cms.instance.upl.fixtures.UplModuleFixture;

/** A violator fixture: a class in the fnd package that depends on an application module. The rule must reject it. */
public final class FndDependsOnUplViolator {

    private FndDependsOnUplViolator() {}

    public static String module() {
        return UplModuleFixture.name();
    }
}
