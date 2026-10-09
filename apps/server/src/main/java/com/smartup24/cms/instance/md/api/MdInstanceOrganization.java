package com.smartup24.cms.instance.md.api;

/**
 * The organization an instance serves, recorded at its first start (FR-INST-1): what the system information page shows
 * without reading md tables itself (ADR-0026).
 */
public record MdInstanceOrganization(String code, String name, String resourceProfile) {}
