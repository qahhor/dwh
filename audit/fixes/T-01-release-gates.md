# T-01 — Reproducible release gates

**Priority:** P0 · **Effort:** M · **Owner:** TBD

## Problem and evidence

Current workspace is not an identifiable RC. Backend ArchUnit reports controller→repository violation; web-instance unit run reports 12 failures; frontend CI only builds (`.github/workflows/ci.yml:56-66`). Full evidence: `audit/evidence/verification-2026-08-31.md`.

## Minimal change

1. Name one RC SHA and build only from a clean checkout.
2. Route `SystemLicenseController` through a service.
3. Repair the 6 failing frontend spec/component contracts.
4. CI required checks: Maven verify; web-instance/web-cp unit+build+typecheck; E2E; security; production-compose smoke.
5. Pin Node version through `.node-version`/CI and Spring Boot plugin version in root pluginManagement.

## Verification

- An intentionally failing frontend spec blocks merge.
- Clean checkout of RC produces all green jobs and immutable artifacts.
- No skipped critical scenarios; reports attached to RC.

## Rollback

CI-only changes are reverted as one commit if runner compatibility breaks; required checks must not be relaxed—replace the broken runner/toolchain first.
