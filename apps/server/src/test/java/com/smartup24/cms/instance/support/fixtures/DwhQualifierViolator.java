package com.smartup24.cms.instance.support.fixtures;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;

/** A violator fixture: the {@code "dwh"} qualifier outside the fnd package. Not a bean (no @Component). */
@SuppressWarnings("unused")
public class DwhQualifierViolator {

    @Qualifier("dwh")
    private DataSource leaked;

    public DwhQualifierViolator(@Qualifier("dwh") DataSource viaConstructor) {
        this.leaked = viaConstructor;
    }
}
