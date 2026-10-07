package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.io.Serial;

/**
 * A hook or an action's handler used the values of a save against their contract (ADR-0032, 6.5): it set a key that is
 * no written field of the form, a value the field's type refuses, or a value of a record that is only read. This is a
 * defect of the module, not of the request, so the answer is 500 {@code internal_error}; the message names the entity,
 * the hook and the field, and the error is logged with it ({@code GlobalExceptionHandler}). A value the request itself
 * makes wrong is the hook's to report with {@code EntitySave.reject} (one 422), not to set.
 */
public final class EntityHookMisuse extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    private EntityHookMisuse(String message, RuntimeException cause) {
        super(message, cause);
    }

    /**
     * Runs a hook or an action's handler of {@code entity}: a misuse of {@link EntityValues} answers as this error with
     * the hook named; any other exception passes unchanged.
     */
    static void guarding(String entity, Object hook, Runnable run) {
        try {
            run.run();
        } catch (IllegalArgumentException | UnsupportedOperationException misuse) {
            if (!thrownByValues(misuse)) throw misuse;
            throw new EntityHookMisuse(
                    "The hook " + hook.getClass().getName() + " of " + entity + " misused the values of the save: "
                            + misuse.getMessage(),
                    misuse);
        }
    }

    /** Whether {@link EntityValues} itself refused: the frame that threw is one of its own. */
    private static boolean thrownByValues(RuntimeException error) {
        StackTraceElement[] frames = error.getStackTrace();
        return frames.length > 0 && EntityValues.class.getName().equals(frames[0].getClassName());
    }
}
