package com.smartup24.cms.instance.md.api;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Wire format of {@code /api/v1/list-views/{listCode}}. */
public final class MdListViewDtos {

    private MdListViewDtos() {}

    public record ViewRequest(String name, JsonNode state, Boolean isDefault, Integer lockVersion) {}

    public record ViewResponse(
            long id, String name, JsonNode state, boolean isDefault, int lockVersion, Instant modifiedAt) {}
}
