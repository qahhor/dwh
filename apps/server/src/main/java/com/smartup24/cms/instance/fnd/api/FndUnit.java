package com.smartup24.cms.instance.fnd.api;

/** An instance unit; {@code nameI18n} is returned as JSON text, since the core does not care what it contains. */
public record FndUnit(long id, String code, String nameI18n, String baseUnitCode) {}
