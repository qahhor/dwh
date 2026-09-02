# S-05 — Webhook SSRF, timeout and secret hardening

**Priority:** P1 (required before GA) · **Effort:** M · **Owner:** TBD · **Status:** In review

## Problem and evidence

The original implementation validated only `http/https`, had no explicit worker
timeout, returned the stored signing secret from list responses, and logged raw
targets. The current candidate closes those direct paths:

- fail-closed switch and exact host allow-list:
  `application.yml:106-115`, `WebhookTargetPolicy.java:33-79`;
- private/special address rejection: `WebhookTargetPolicy.java:98-126`;
- connect/read timeouts and redirects disabled:
  `KwhOutboxWorker.java:37-43`;
- target revalidation immediately before dispatch:
  `KwhOutboxWorker.java:64`;
- query/user-info redaction and one-time creation secret:
  `KwhWebhookService.java:41-69,136-162`.

## Minimal change

- Return the signing secret only once at creation and omit it from list/update
  views and logs — implemented.
- Resolve destination and deny loopback/private/link-local/metadata ranges;
  require exact operator allow-list — implemented.
- Disable redirects; set explicit connect/read timeout; retain bounded retry —
  implemented.
- Disable all webhook polling and delivery by default — implemented.
- Remaining defence-in-depth: enforce host egress firewall/trusted DNS for an
  enabled installation. DNS resolution by policy and by the JDK client is not an
  atomic pin, so the application allow-list is not a substitute for the network
  boundary.

## Verification

Focused tests cover disabled mode, exact allow-list, private/metadata IPv4 and
IPv6, explicit client-owned private opt-in, malformed URLs, create/update
revalidation, secret-once response, URL redaction, dispatch-time revalidation,
and a real slow HTTP endpoint. Evidence: `WebhookTargetPolicyTest.java`,
`KwhWebhookServiceTest.java`, `KwhOutboxWorkerSecurityTest.java`; included in the
194-test `mvn verify` run on 2026-09-02.
