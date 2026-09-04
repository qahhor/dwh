# Contributing to SmartupCMS

Thank you for improving SmartupCMS. The project optimizes for a small, reliable
product surface: fix the underlying workflow and preserve module boundaries
before adding new concepts.

By participating, you agree to follow the
[Code of Conduct](CODE_OF_CONDUCT.md). Report vulnerabilities privately through
[SECURITY.md](SECURITY.md), not in issues or pull requests.

## Before starting

Search existing issues and pull requests. For a material behavior or API
change, open an issue that identifies the user problem, measurable outcome,
smallest scope, and non-goals. Architecture changes require an ADR.

Prerequisites:

- JDK 25 and Maven 3.9+
- Node.js version from [`.node-version`](.node-version) and npm
- Docker Engine 26+ with Docker Compose v2

## Development workflow

Create a short-lived branch from the default branch. The repository uses
Conventional Commit-style subjects such as `fix(web): ...` and `docs(oss): ...`.
Keep commits reviewable and avoid mixing unrelated cleanup with the change.

Every commit must certify the [Developer Certificate of Origin](DCO):

```bash
git commit -s -m "fix(scope): describe the change"
```

The resulting commit message must contain a valid `Signed-off-by: Name
<email>` trailer. If an existing local commit is yours and lacks the trailer,
amend it with `git commit --amend -s`; do not rewrite other contributors'
commits without their permission. A CLA is not required.

## Required verification

Run the checks relevant to the change and record exact commands and outcomes in
the pull request.

```bash
mvn -B verify
```

```bash
cd apps/web
npm ci
npm run i18n:sync-ru
npm run i18n:audit
npm test
npm run typecheck
npm run build
```

For changes to user workflows, Compose, authentication, routing, or deployment,
also run the affected Playwright and release-configuration checks. CI remains
the authoritative gate.

## Engineering rules

- Enforce permissions on the server; hiding a UI action is not authorization.
- Preserve module boundaries checked by ArchUnit.
- Use structured errors and do not place credentials or personal data in logs.
- Keep static UI copy behind localization keys. Russian is the canonical
  source; synchronize the packaged web fallback and run the localization audit.
- Keep database migrations forward-only and compatible with the previous
  release during the expand phase. Never silently rewrite an applied migration.
- Add tests for changed invariants, authorization rules, failure behavior, and
  migrations.
- For UI changes, cover keyboard navigation, accessible names, loading, empty,
  error, success, and narrow-viewport states.
- Update public documentation when commands, configuration, APIs, migrations,
  or operating procedures change.
- Do not commit `.env`, files under `.secrets`, customer data, database dumps,
  decrypted backups, or generated `graphify-out` artifacts.

## Pull requests

Use the pull request template. Explain the problem and scope, provide test
evidence, identify data/security/deployment risk, and document rollback. Keep a
pull request focused; if it cannot be reviewed independently, split it by
behavior rather than by technical layer.

Maintainers may request additional security, migration, browser, or clean-deploy
evidence before merge. Acceptance and release decisions follow
[GOVERNANCE.md](GOVERNANCE.md).
