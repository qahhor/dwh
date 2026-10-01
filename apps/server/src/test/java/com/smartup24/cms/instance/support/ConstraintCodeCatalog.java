package com.smartup24.cms.instance.support;

import com.smartup24.cms.instance.common.actor.ActorError;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import com.smartup24.cms.instance.common.error.ConstraintErrors;
import com.smartup24.cms.instance.common.versioning.VersionError;
import com.smartup24.cms.instance.jobs.api.JobError;
import com.smartup24.cms.instance.units.api.UnitError;
import com.smartup24.cms.instance.warehouse.api.WarehouseError;
import java.util.List;
import java.util.Map;

/**
 * Every code of the modules split out of the former foundation (plan 10/10, item 4.2, ADR-0030), and the table prefix
 * each module owns, for the tests that hold the codes against the schema and the catalogs.
 */
public final class ConstraintCodeCatalog {

    /** The owner's codes by the prefix of its tables; the longest matching prefix wins. */
    public static final Map<String, List<ConstraintCode>> BY_TABLE_PREFIX = Map.of(
            "fnd_job_", List.of(JobError.values()),
            "fnd_unit", List.of(UnitError.values()),
            "fnd_load", List.of(WarehouseError.values()));

    private ConstraintCodeCatalog() {}

    /** All codes: the jobs, units and warehouse rules, the versioning standard and the audit trigger. */
    public static List<ConstraintCode> all() {
        return ConstraintErrors.codes(
                JobError.values(),
                UnitError.values(),
                WarehouseError.values(),
                VersionError.values(),
                ActorError.values());
    }
}
