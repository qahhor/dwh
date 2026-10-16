package com.smartup24.cms.spi.llm;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.io.IOException;

/** Optional, explicitly configured draft generator; never persists or executes its output (ADR-0039). */
@PlatformApi(since = "2.0", stability = Stability.EXPERIMENTAL)
public interface LlmProvider {
    String getProviderCode();

    boolean isConfigured();

    /** Returns JSON text. Implementations must honour interruption and must not log either input or output. */
    String generate(String instructions, String prompt) throws IOException, InterruptedException;
}
