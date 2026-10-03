package com.smartup24.cms.instance.common.module;

import java.io.Serial;

/**
 * The modules on the classpath cannot start (ADR-0033, 6.3): a malformed manifest, a platform too old or of another
 * major version, a missing or too old dependency. The application does not start; the message names every module.
 */
public class ModuleManifestException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ModuleManifestException(String message) {
        super(message);
    }

    public ModuleManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
