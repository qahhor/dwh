package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

/**
 * What a declared record action does (ADR-0032, 6.7): a bean per action, called at step 9 of ADR-0032, 6.3 in place of
 * {@code beforeSave}, with the record read in its scope for update and the revision of If-Match checked. It changes the
 * record through {@link EntityActionCall#values()}; the runtime writes, audits and publishes as for any save. A declared
 * action other than create, update, archive and delete without its handler fails the start.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public interface EntityActionHandler {

    /** The code of the declared entity. */
    String entity();

    /** The code of the declared action ({@code pin}). */
    String action();

    void run(EntityActionCall call);
}
