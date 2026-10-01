package com.smartup24.cms.instance.units.config;

import com.smartup24.cms.instance.common.error.ConstraintCodes;
import com.smartup24.cms.instance.units.api.UnitError;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The units publish their codes to the versioning standard: it writes {@code fnd_unit_coefficient_versions} and
 * translates a violation of the table's own constraints with them (plan 10/10, item 4.2).
 */
@Configuration(proxyBeanMethods = false)
public class UnitsConfig {

    @Bean
    ConstraintCodes unitConstraintCodes() {
        return () -> List.of(UnitError.values());
    }
}
