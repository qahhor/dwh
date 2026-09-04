# Task and File Data-Scope Completion Plan

**Goal:** Close P0-AUTH-1 by enforcing one explicit server-side row-visibility contract for tasks, comments, task attachments, and files without changing the product surface.

**Decision:** Preserve ADR-0013 role precedence and backwards compatibility. `ALL` sees every row in the single installation. `SELF` sees tasks where the user is author, reporter, responsible, executor, or observer. `UNITS` and `SUBTREE` see tasks with at least one participant in the materialized effective organization scope. A narrowed user sees a file when the file is owned inside that scope or is attached to a visible task/comment; `SELF` uses ownership or participation. Standalone files therefore remain owner-only for `SELF`. Inaccessible direct identifiers return `404` so existence is not disclosed.

**Architecture:** Keep scope policy in `MdScopeService` and immutable internal `ScopeFilter` factories. Repositories apply predicates in SQL so list pagination and counts remain correct. Services require the same scope before reads and mutations; controller permissions remain the action-level boundary. Search remains fail-closed for administrators until its own scoped Typesense contract exists.

## Task 1: Lock the policy with failing PostgreSQL tests

- [x] Add task visibility tests for `ALL`, `SELF`, `UNITS`, and `SUBTREE`.
- [x] Add file visibility tests for ownership, direct task attachments, comment attachments, and inaccessible standalone files.
- [x] Prove an adjacent organization branch never appears in list/count results.
- [x] Prove a role replacement recalculates effective scope in the same transaction.

## Task 2: Apply task scope at every server entry point

- [x] Add reusable task predicates to `ScopeFilter` and expose them through `MdScopeService`.
- [x] Scope task list, detail, project statistics, subtasks, ancestors, comments, attachments, status changes, and member changes.
- [x] Validate every newly assigned task participant against the actor's data scope.
- [x] Preserve unrestricted overloads only for trusted internal workers/tests; HTTP paths must always pass the authenticated user id.

## Task 3: Apply file scope at every server entry point

- [x] Add reusable file predicates for ownership and visible task/comment linkage.
- [x] Scope file list, metadata, download, delete, and task/comment attachment validation.
- [x] Keep physical SHA-256 deduplication private and never use another owner's metadata as authorization.

## Task 4: Prove direct-API behavior and publish the decision

- [x] Add endpoint tests for permitted and forbidden roles plus out-of-scope direct identifiers.
- [x] Update ADR-0013, threat model, technical specification, and release checklist with the exact contract.
- [x] Run focused mutation checks, full Maven verification, documentation/architecture gates, and Graphify update.
- [ ] Commit, push, wait for green CI, rebuild Docker, and rerun task/file browser smoke.
