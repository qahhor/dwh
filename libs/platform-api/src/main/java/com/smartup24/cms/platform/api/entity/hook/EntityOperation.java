package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

/** What a save of an entity record does (ADR-0032, 6.5): the hooks and the rules see it. */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public enum EntityOperation {
    CREATE,
    UPDATE,
    /** A declared record action (ADR-0032, 6.7). */
    ACTION
}
