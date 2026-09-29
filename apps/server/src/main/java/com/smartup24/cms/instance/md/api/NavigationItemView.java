package com.smartup24.cms.instance.md.api;

import com.smartup24.cms.instance.common.web.Revisioned;
import java.time.Instant;

/** A menu item as the API answers it; its revision is what a change of it names (plan item 3.6). */
public record NavigationItemView(
        Long id,
        String code,
        String title,
        String titleKey,
        String sectionId,
        Long parentId,
        String icon,
        String targetType,
        String url,
        boolean openInIframe,
        String requiredPermission,
        int sortOrder,
        String state,
        Long createdBy,
        Long modifiedBy,
        Instant createdAt,
        Instant modifiedAt,
        long revision)
        implements Revisioned {}
