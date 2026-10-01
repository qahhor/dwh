/**
 * Uploads: data sources and their file formats, upload packages, parsing and validation, and the apply step that writes
 * the rows into the warehouse through {@code warehouse.api}. It owns the {@code upl_*} tables and the {@code upl}
 * permission area (ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.upl;
