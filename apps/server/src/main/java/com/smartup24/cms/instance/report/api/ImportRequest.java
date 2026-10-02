package com.smartup24.cms.instance.report.api;

import java.util.UUID;

/**
 * What the client asks to import (ADR-0032, 10.1): the xlsx file it uploaded to the files module, the mode —
 * {@code dry_run} checks every row and writes nothing, {@code apply} upserts by the entity's import key — and the
 * language of the problems' texts and of the report.
 */
public record ImportRequest(UUID fileId, String mode, String lang) {}
