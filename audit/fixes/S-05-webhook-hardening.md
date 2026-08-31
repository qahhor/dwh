# S-05 — Webhook SSRF, timeout and secret hardening

**Priority:** P1 (required before GA) · **Effort:** M · **Owner:** TBD

## Problem and evidence

Only `http/https` is validated (`KwhWebhookService.java:122-132`), worker client has no explicit timeout (`KwhOutboxWorker.java:25-57`), and repository list records include signing secret (`KwhSubscriptionRepository.java:45-52,103-112`).

## Minimal change

- Return signing secret only once at creation; store hash/encrypted value as protocol allows; rotate existing secrets.
- Resolve destination and deny loopback/private/link-local/metadata ranges for every connection; prevent redirects and DNS rebinding.
- Explicit connect/read/call timeout, bounded retries+jitter and delivery metrics.
- Redact URL query/credentials and secret from API/logs.

## Verification

Integration tests cover private IP, metadata endpoint, redirect to private IP, slow endpoint, DNS change, secret omission and signature validation.
