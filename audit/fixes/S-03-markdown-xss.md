# S-03 — Eliminate stored XSS in markdown

**Priority:** P0 · **Effort:** M · **Owner:** TBD

## Problem and evidence

Viewer/editor interpolate markdown URL into `href` then call `bypassSecurityTrustHtml`: `ui-markdown-view.component.ts:137-142`, `ui-markdown-editor.component.ts:461-466`. Task descriptions/comments render the result.

## Minimal change

- Parse/sanitize links with an explicit scheme allowlist; reject control characters, encoded and mixed-case dangerous schemes.
- Prefer DOM/text binding or a maintained sanitizer; remove blanket trust bypass for user content.
- Add frontend nginx CSP with only required origins; no `unsafe-inline` unless nonce/hash design explicitly requires it.

## Verification

Tests cover `javascript:`, `data:text/html`, `vbscript:`, entity/percent encoding, whitespace/control prefixes and safe HTTPS/mailto policy. Browser click must not execute script. Response has validated CSP header.
