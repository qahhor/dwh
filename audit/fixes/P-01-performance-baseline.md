# P-01 — Establish evidence-based performance baseline

**Priority:** P1 · **Effort:** M · **Owner:** TBD

## Problem and evidence

No representative data, load test, runtime SLO or query plan evidence was found. PostgreSQL search uses `%query% ILIKE` (`SearchService.java:58-120`); analytics is new; initial web bundle is near its warning budget.

## Minimal change

- Agree four pilot journeys and a non-sensitive representative dataset.
- Capture p50/p95/error rate plus CPU, memory, DB pool/locks/query plans for login, task list/write, search and upload.
- Add explicit webhook timeouts and date/result caps before test.
- Optimize only queries/chunks proven above threshold; record baseline and threshold in CI/nightly job.

## Verification

Versioned report names commit, dataset shape, commands, environment and results. Thresholds are signed by product/SRE owner; regression job fails above them. No invented universal target.
