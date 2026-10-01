package com.smartup24.cms.instance.warehouse.fixtures;

import com.smartup24.cms.instance.upl.fixtures.UplModuleFixture;

/** A violator fixture: a warehouse class that depends on an application module. The rule must reject it. */
public final class WarehouseDependsOnUplViolator {

    private WarehouseDependsOnUplViolator() {}

    public static String module() {
        return UplModuleFixture.name();
    }
}
