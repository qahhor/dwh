package com.smartup24.cms.instance.config.module;

import com.smartup24.cms.instance.common.module.ModuleManifestException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Prints why the modules cannot start (ADR-0033, 6.3) as the cause of the failed start, instead of a stack trace of the
 * configuration parser: the module, the version it needs and the version installed.
 */
public class ModuleManifestFailureAnalyzer extends AbstractFailureAnalyzer<ModuleManifestException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ModuleManifestException cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                "Install a platform whose API the module supports, or a version of the module built for this platform"
                        + " (its META-INF/smartupcms/modules/<code>.json states minPlatform and its dependencies;"
                        + " ADR-0033, 6.3).",
                cause);
    }
}
