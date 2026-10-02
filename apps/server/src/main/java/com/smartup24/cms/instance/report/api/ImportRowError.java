package com.smartup24.cms.instance.report.api;

/**
 * A problem of a row of an import (ADR-0032, 10.1): the row's number in the file, the address of the problem
 * ({@code rows[17].qty}, {@code rows[17]} for the whole row), its code and its text in the import's language.
 */
public record ImportRowError(int row, String field, String code, String message) {}
