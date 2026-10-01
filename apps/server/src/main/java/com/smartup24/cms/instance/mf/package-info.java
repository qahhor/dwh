/**
 * Files. The {@code mf} prefix is the Biruni convention for files: upload, download and deletion of stored files,
 * content inspection and virus scanning, and storage on local disk or an S3-compatible service through the provider
 * contract. It owns the {@code mf_*} tables, publishes {@code mf_pub_files} and guards its endpoints with the
 * {@code mf} permission area (ADR-0026, ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.mf;
