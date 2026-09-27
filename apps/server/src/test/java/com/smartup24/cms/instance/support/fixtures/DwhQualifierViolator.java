package com.smartup24.cms.instance.support.fixtures;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;

/** Фикстура-нарушитель AC-5: квалификатор {@code "dwh"} вне пакета fnd. Не бин (нет @Component). */
@SuppressWarnings("unused")
public class DwhQualifierViolator {

    @Qualifier("dwh")
    private DataSource leaked;

    public DwhQualifierViolator(@Qualifier("dwh") DataSource viaConstructor) {
        this.leaked = viaConstructor;
    }
}
