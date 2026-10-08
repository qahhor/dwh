/**
 * Limits for reading uploaded xlsx workbooks (plan 10/10, item 7.6; NFR-SEC-01): a workbook is a zip of XML
 * parts, so a small file can unpack into gigabytes, declare millions of shared strings or address cells far beyond the
 * sheet. {@link com.smartup24.cms.instance.common.xlsx.XlsxGuard} checks a workbook on disk against
 * {@link com.smartup24.cms.instance.common.xlsx.XlsxLimits} before any reader opens it.
 */
@NullMarked
package com.smartup24.cms.instance.common.xlsx;

import org.jspecify.annotations.NullMarked;
