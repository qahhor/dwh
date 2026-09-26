package com.smartup24.cms.instance.fnd.fixtures;

import com.smartup24.cms.instance.upl.fixtures.UplModuleFixture;

/** Фикстура-нарушитель AC-37: класс в пакете fnd, зависящий от прикладного модуля. Правило обязано его отвергнуть. */
public final class FndDependsOnUplViolator {

    private FndDependsOnUplViolator() {
    }

    public static String module() {
        return UplModuleFixture.name();
    }
}
