package com.smartup24.cms.instance.common.entity.hook;

import java.util.Map;

/**
 * A record action as its handler sees it (ADR-0032, 6.7): the save of the record with the action's code, and the
 * parameters the request sent.
 */
public interface EntityActionCall extends EntitySave {

    /** The parameters of the action, as the request's JSON object sent them. */
    Map<String, Object> params();
}
