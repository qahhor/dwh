# D-01 — Make production topology executable

**Priority:** P0 · **Effort:** M · **Owner:** TBD

## Problem and evidence

Production proxy points to UI port 80 (`deploy/nginx/nginx.prod.conf:66-73`), while both UI images listen 8080. Fleet publishes 443 (`docker-compose.fleet.prod.yml:43-45`), while nginx has only an HTTP listener (`nginx.prod.conf:76-80`).

## Minimal change

- Point upstreams to `web:8080` and `web-cp:8080`.
- Declare one TLS topology. If an external LB terminates TLS, publish only the internal HTTP contract and validate forwarded headers. Otherwise mount managed certs and configure 443 + redirect.
- Add proxy healthcheck and production-compose test profile with generated ephemeral certificates when applicable.

## Verification

On a clean host from release digests: `/`, `/cp/`, instance API, CP API, SSE and upload route pass; HTTP/TLS policy and security headers match the documented contract. A wrong upstream port must fail CI.
