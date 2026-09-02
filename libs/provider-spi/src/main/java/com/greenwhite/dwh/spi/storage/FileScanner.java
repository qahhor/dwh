package com.greenwhite.dwh.spi.storage;

import java.io.InputStream;

/**
 * Pluggable malware scanner for objects held under a non-public quarantine key.
 * Implementations must fail with an exception when a conclusive verdict cannot
 * be produced; the upload pipeline treats scanner errors as fail-closed.
 */
public interface FileScanner {

    String getProviderCode();

    ScanResult scan(InputStream content, long sizeBytes, String contentType);

    enum Verdict {
        CLEAN,
        INFECTED
    }

    record ScanResult(Verdict verdict, String threatName) {
        public static ScanResult clean() {
            return new ScanResult(Verdict.CLEAN, null);
        }

        public static ScanResult infected(String threatName) {
            return new ScanResult(Verdict.INFECTED, threatName);
        }
    }
}
