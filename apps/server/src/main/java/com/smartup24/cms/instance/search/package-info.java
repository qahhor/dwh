/**
 * Search: full-text search over a derived Typesense index, its projections and generations, reconciliation with the
 * source data and search settings. The index is never a source of authorization. It owns the {@code search_*} tables
 * and the {@code search} permission area (ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.search;
