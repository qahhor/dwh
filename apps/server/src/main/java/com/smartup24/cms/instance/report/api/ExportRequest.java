package com.smartup24.cms.instance.report.api;

import java.util.List;
import java.util.Map;

/** What the client asks to export: the list as it stands on screen (ADR-0018). */
public record ExportRequest(
        String list,
        String filter,
        String sort,
        String q,
        List<String> columns,
        Map<String, String> options,
        String lang) {}
