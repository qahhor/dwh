package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.mf.scan.FileScannerStartupCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileScannerStartupCheckTest {

    @Test
    @DisplayName("Production-режим не запускается без активного файлового сканера")
    void requiredScannerFailsClosedWhenNoProviderIsActive() {
        var check = new FileScannerStartupCheck(List.of(), true);

        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no FileScanner provider is active");
    }

    @Test
    @DisplayName("Локальная разработка может запускаться только с magic-byte проверкой")
    void optionalScannerAllowsLocalDevelopment() {
        var check = new FileScannerStartupCheck(List.of(), false);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }
}
