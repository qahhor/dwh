/**
 * The import of entity records from a file (ADR-0032, 10.1; plan 10/10, item 5.8): the declared key of the upsert
 * ({@link com.smartup24.cms.platform.api.entity.importing.EntityImportSpec}), the contract the report module runs
 * an import through ({@link com.smartup24.cms.instance.common.entity.importing.EntityImporter}) and the reading of a
 * cell as the value of a field. The journal, the job and the files are the report module's; every row goes through the
 * steps of a save of the general runtime. Null-checked by NullAway (plan 10/10, item 1.2).
 */
@NullMarked
package com.smartup24.cms.instance.common.entity.importing;

import org.jspecify.annotations.NullMarked;
