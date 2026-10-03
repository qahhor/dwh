package com.smartup24.cms.spi.storage;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.io.InputStream;

/**
 * Pluggable malware scanner for objects held under a non-public quarantine key.
 * Implementations must fail with an exception when a conclusive verdict cannot
 * be produced; the upload pipeline treats scanner errors as fail-closed.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public interface FileScanner {

    String getProviderCode();

    ScanResult scan(InputStream content, long sizeBytes, String contentType);

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    enum Verdict {
        CLEAN,
        INFECTED
    }

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record ScanResult(Verdict verdict, String threatName) {
        public static ScanResult clean() {
            return new ScanResult(Verdict.CLEAN, null);
        }

        public static ScanResult infected(String threatName) {
            return new ScanResult(Verdict.INFECTED, threatName);
        }
    }
}
