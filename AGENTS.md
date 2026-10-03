# AGENTS.md — one context for every AI assistant

This file is the single source of instructions for AI coding assistants in this
repository (Codex, Claude Code, Gemini CLI, Cursor, Copilot and others).
`CLAUDE.md` and `GEMINI.md` only import it. Change the rules here, never in a
tool-specific copy. Personal memory of one tool is not shared with the others:
anything every assistant must know belongs in this file or in `docs/`.

## 1. Read first

1. [`docs/ai-context.md`](docs/ai-context.md) — the handoff: product,
   invariants, checks, where work stands; the server modules, their tables,
   permission areas and entry points are in
   [`docs/architecture/module-map.md`](docs/architecture/module-map.md).
2. [`docs/plan-10-10.md`](docs/plan-10-10.md) — the quality roadmap: phases,
   items, acceptance criteria, what is done and what is next.
3. [`docs/README.md`](docs/README.md) — the documentation index and its
   authority order; [`CODE_STYLE.md`](CODE_STYLE.md) — the code rules.
4. `git status --short --branch` — preserve unrelated dirty work.

The handoff never overrides the canonical technical specification
(`docs/technical-specification.md`), current ADRs (`docs/adr/`), source,
configuration or verified test results. When they disagree, check the primary
artifact and record the discrepancy; do not fill a gap with a guess. Do not
publish local audit drafts as requirements or evidence.

## 2. How the user works

- Answer in Russian unless asked otherwise: short, structured, the result
  first. For product or process changes add "Почему важно" and
  "Риски" in a line or two. Never invent facts, figures or decisions; say what
  is unknown and mark assumptions as «предположение».
- Confirm before anything irreversible or outward-facing (publishing, sending,
  widening access, deleting data). Do not download or install tools or
  packages without the user's approval.
- Never print, commit or log passwords, tokens, keys or personal data.
  Disposable test credentials live only in ignored local files.

## 3. Workflow for a change

- One branch per task (`claude/…`, `codex/…` or similar), created from `main`.
- Commit with `git commit -s` (DCO, checked by `dco.yml`) and end the message
  with your tool's attribution trailer in the last paragraph.
- Before a merge, run every CI job **locally** (section 5); a red check is
  compared with the base branch before anyone calls it pre-existing. Merge
  into `main` with `git merge --no-ff`, never rebase or squash `main`
  (`.git-blame-ignore-revs` holds merge-sensitive SHAs).
- Coverage floors (`scripts/quality/test-coverage-floors.ps1`) and every
  baseline (ArchUnit store, comment-language baseline, ESLint suppressions)
  only go down: add tests or fix code, never lower a floor or grow a baseline.
- A breaking API change carries the commit trailer
  `Api-Breaking: <what>` in the **last paragraph** of a commit message (git
  parses trailers only there) or the PR label `api-breaking`, plus an entry
  under `### Changed` in `CHANGELOG.md`.
- **No client installations exist before the final release** (product owner,
  2026-10-01): do not add legacy aliases, transition periods, deprecated API
  forms kept for a release, or migrations that preserve or convert existing
  client data. Record a breaking change in `CHANGELOG.md`; no compatibility
  path is needed. The earlier compatibility layers were removed on 2026-10-01.
- Released Flyway migrations never change (`migration-manifest.sha256`,
  checked by `MigrationManifestTest`). A new migration takes the next free
  `V` number and is appended to the manifest
  (`-Dmigrations.manifest.append=true`).
- After an API change regenerate `docs/api/openapi.json`
  (`-Dtest=OpenApiContractTest -Dopenapi.update=true`) and the web types
  (`npm run api:types` in `apps/web`).
- Update `CHANGELOG.md`, the ADR when a decision changes, and the module guide
  when an extension point changes — in the same branch.
- After merging code, refresh the local knowledge graph (`graphify update .`,
  section 6) and the handoff in `docs/ai-context.md` §7.

## 4. Code rules in one screen

Details and links: `CODE_STYLE.md`, `docs/ai-context.md` §5, ADR-0020…0025.

- Errors: `ApiException` + `ErrorCode` + key `error.<module>.<name>` + params;
  the key exists in ru, uz and en; answers are `application/problem+json`.
- API: DTOs in the module `api` package; 201 + `Location`; `@ResponseStatus`
  declared; collections paged (`KeysetPage`, `TimePage`, 422 above the max).
- Changes of revisioned records take `If-Match` (428 without, 409 stale).
- JSON columns through `JsonColumns`; one application `ObjectMapper`.
- A `catch` rethrows, logs (warn/error for broad catches) or uses the error.
- Classes ≤ 400 lines, methods ≤ 60 (Checkstyle, no waivers).
- Code comments in English; references only to ADR-NNNN, FR/NFR ids or
  "plan 10/10, item N" (`docs/plan-10-10.md`). Docs and ADRs stay Russian.
- UI texts: ru is the source catalog, uz and en complete; `npm run i18n:sync-ru`.
- Authorization only on the server (`@RequiresPermission`, data scope).

## 5. Checks (run all before a merge)

The full list with commands is in `docs/ai-context.md` §6. In short:

- Server: `mvn -B clean spotless:check verify`, then
  `scripts/quality/test-coverage-floors.ps1`.
- Web (`apps/web`): `npm run -s typecheck|lint|i18n:audit|aria:audit|contrast:audit|signals:audit|api:audit|comments:audit|test|build`.
- Accessibility: `cd e2e && npm run test:a11y`; E2E: `scripts/dev/test-e2e.ps1`
  on a fresh Compose stack, both shards (`-Shard 1/2`, `-Shard 2/2`).
- API contract: `scripts/api/test-api-contract.ps1 -BaseRef main`.
- Repository: `scripts/docs/test-repository-hygiene.ps1`,
  `scripts/docs/test-public-docs.ps1`; secrets: gitleaks over the branch's
  commits (`gitleaks git --log-opts="main..HEAD"`).
- Developer CLI (`tools/cms-cli`): `npm test` there; after changing the CLI or
  what it generates, `scripts/dev/test-cms-cli.ps1` (generates a module in a
  temporary copy and builds it).

## 6. Environment notes (Windows workstation)

- Local tooling, when present, lives in the ignored `.tools/` folder (JDK,
  Maven wrapper script, e2e helper scripts). `.tools/mvn.ps1` changes directory
  to the main checkout: in a git worktree run `mvnw.cmd` from the worktree with
  the same `JAVA_HOME`/`TEMP`.
- A worktree needs `apps/web/node_modules` (and `e2e/node_modules`) as a
  junction to the main checkout; remove a junction with `cmd /c rmdir`, never
  by deleting through it.
- PowerShell 5.1 reads BOM-less `.ps1` as ANSI: keep scripts ASCII and write
  non-ASCII regex characters as `\uXXXX`.
- From Git Bash, prefix docker commands with `MSYS_NO_PATHCONV=1`, or paths
  such as `/repo` are rewritten.
- `-pl apps/server` alone builds against the installed `libs/*` jars; add
  `-am` after changing a library.
- E2E shard logs written by PowerShell are UTF-16 (`iconv -f utf-16`).
- New modules, entities, fields and their migrations come from the CLI:
  `node tools/cms-cli/bin/cms.mjs <module new|entity new|entity add-field|migration diff|doctor>`
  (Node standard library only, same on Windows and Linux; `cms doctor` checks
  the JDK, Maven wrapper, git and Docker). Do not hand-copy an entity.

## 7. graphify (local knowledge graph)

The graph lives in `graphify-out/` (not committed) and is built with
`graphify update .` (install: `py -m pip install graphifyy`; run as
`py -m graphify` when the command is not on PATH).

- For codebase questions, first run `graphify query "<question>"` when
  `graphify-out/graph.json` exists; `graphify path "<A>" "<B>"` for
  relationships, `graphify explain "<concept>"` for one concept.
- Use `graphify-out/wiki/index.md` for broad navigation when it exists; read
  `graphify-out/GRAPH_REPORT.md` only for architecture reviews.
- Dirty `graphify-out/` files after updates are expected and no reason to skip
  it. After modifying code, run `graphify update .` (AST only, no API cost).
- When the user types `/graphify`, use the installed graphify skill or
  instructions first.
