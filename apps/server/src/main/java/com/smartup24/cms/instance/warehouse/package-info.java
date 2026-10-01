/**
 * Warehouse: the second database (its data source, migrations and the {@code MigrateMain} step, the raw layer and mart
 * reads) and the ledger of what was loaded into it. Other modules reach it only through {@code warehouse.api}. It owns
 * the {@code fnd_load*} tables of the CMS database, which keep their names, and the schemas of the second database
 * (ADR-0001, ADR-0020, ADR-0030). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.warehouse;
