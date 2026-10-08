package com.smartup24.cms.instance.mf.api;

/**
 * Whether the file scanner of the instance answers, for the health of its dependencies (plan 10/10, item 0.7). The
 * bean exists only while a scanner daemon is enabled; the wiring sees this contract, not the scanner (item 1.3).
 */
public interface FileScannerProbe {

    /** The daemon answers within its connect timeout. */
    boolean ping();
}
