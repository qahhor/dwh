# S-04 — Enforce entity permissions and data scope in global search

**Priority:** P0 · **Effort:** M · **Owner:** TBD

## Problem and evidence

`SearchController.java:22-29` checks only `platform.search.view`; `SearchService.java:58-120` and `TypesenseClient.java:88-96,181-190` return unscoped data including user email/login. The normal user repository uses `ScopeFilter` (`MdUserRepository.java:153-210`).

## Minimal change

1. Immediately omit each search type unless the principal has that entity view permission; omit sensitive fields not needed for result rendering.
2. Apply the same server-side data-scope predicate in PostgreSQL and Typesense `filter_by`.
3. Until scoped Typesense documents/filters are proven, disable affected types for non-admins rather than filtering after an oversized query.

## Verification

Permission×scope integration matrix: no permission, own, subordinate, department, all. Assert both result identifiers and absence of PII. Run the same contract against PostgreSQL fallback and Typesense.
