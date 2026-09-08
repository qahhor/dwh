# Audit history

These are dated observations, not current requirements or release approval.
Use the [technical specification](../docs/technical-specification.md),
[current decisions](../docs/README.md) and verified source/test evidence first.
Newer local drafts are not automatically approved or ready for publication.

On 2026-09-08, the user requested publication of all remaining local changes.
The ten previously untracked audit drafts are retained as explicitly unverified
historical material. Their publication does not validate their claims or make
them requirements, current findings, or release evidence.

## Cleanup — 2026-09-08

Removed the superseded 2026-09-03 master, architecture, DevOps, performance,
security and health reports. The 2026-09-04 health snapshot explicitly replaced
its predecessor; later domain reports and the 2026-09-06 readiness review are
retained. Their age does not mean their outstanding findings are resolved.

Removed the old `W-P01.patch` and `W-P11.patch` proposals after checking the
implemented Projects pagination, labels and hit areas. The implementation is
recorded in commits `9ad7997`, `96ac5d5` and `adcb531`; do not reapply the old
baseline patches. Tasks, Projects and Search audits have different scopes and
are retained separately, as are the release tracker, open fix cards and evidence.

The six tracked reports remain available in Git at
`2de02965851852f5ebcba39e1a03150e46b5cdf7`, for example with
`git show <commit>:audit/<filename>`. A byte-verified local backup of all eight
removed documents/patches is retained at
`backups/repository-cleanup-20260908T061816750Z.zip` (ignored by Git).
