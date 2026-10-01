/**
 * Master data. The {@code md} prefix is the Biruni convention for master data: users, roles and rights, the
 * organisation tree and data scope, platform settings, custom fields, languages and translations, saved list views,
 * the navigation menu and the module registry. It owns the {@code md_*} tables (except {@code md_sso_providers}) and
 * the {@code md} permission area, and publishes the read view {@code md_pub_users} (ADR-0026, ADR-0028). Module map:
 * {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.md;
