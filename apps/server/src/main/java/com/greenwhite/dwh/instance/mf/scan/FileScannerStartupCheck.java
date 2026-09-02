package com.greenwhite.dwh.instance.mf.scan;

import com.greenwhite.dwh.spi.storage.FileScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FileScannerStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileScannerStartupCheck.class);

    private final List<FileScanner> scanners;
    private final boolean required;

    public FileScannerStartupCheck(
            List<FileScanner> scanners,
            @Value("${dwh.files.scanner.required:false}") boolean required) {
        this.scanners = List.copyOf(scanners);
        this.required = required;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (required && scanners.isEmpty()) {
            throw new IllegalStateException(
                    "File malware scanning is required, but no FileScanner provider is active");
        }
        if (scanners.isEmpty()) {
            log.warn("File malware scanner is not configured; only magic-byte/MIME validation is active");
        } else {
            log.info("File quarantine scanners active: {}", scanners.stream()
                    .map(FileScanner::getProviderCode)
                    .sorted()
                    .toList());
        }
    }
}
