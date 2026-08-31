# S-02 — Disable or authenticate module moderation callback

**Priority:** P0 · **Effort:** S to disable / M to harden · **Owner:** TBD

## Problem and evidence

`MdCustomModuleController.java:78-82` is excluded from auth by `SecurityConfig.java:34` and `WebMvcConfig.java:21-27`. `MdCustomModuleService.java:84-141` accepts status and on approval registers forms/permissions for admin.

## Minimal release change

- Default-off custom-module moderation and remove the public callback from release surface.
- If required now: authenticate CP with mTLS or HMAC over canonical payload; include timestamp/event id, replay store, strict source and explicit allowed state transitions.
- Validate module id/status against server state; never accept arbitrary transition.

## Verification

- Anonymous, tampered, expired and replayed callbacks cannot change module/status/RBAC.
- Only a valid `SUBMITTED → APPROVED|REJECTED` transition succeeds once.
- Audit log records authenticated CP identity and event id.
