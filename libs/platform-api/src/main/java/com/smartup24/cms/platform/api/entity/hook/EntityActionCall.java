package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.Map;

/**
 * A record action as its handler sees it (ADR-0032, 6.7): the save of the record with the action's code, and the
 * parameters the request sent.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public interface EntityActionCall extends EntitySave {

    /** The parameters of the action, as the request's JSON object sent them. */
    Map<String, Object> params();
}
