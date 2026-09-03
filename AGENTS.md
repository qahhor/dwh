## Project context

Before repository work, read `docs/ai-context.md` and `docs/README.md`, then
inspect `git status --short --branch`. The AI context is a handoff summary; it
never overrides the canonical technical specification, current ADRs, source,
configuration, or verified test results. Preserve unrelated dirty work and do
not publish local audit drafts as requirements or evidence.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- When Graphify outputs will be committed from a dirty checkout, regenerate them from a clean checkout of the intended source commit so unpublished or unrelated files are not indexed.
