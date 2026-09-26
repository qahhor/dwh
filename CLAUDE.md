## Project context

Before repository work, read `docs/ai-context.md` and `docs/README.md`, then
inspect `git status --short --branch`. Treat `docs/ai-context.md` as a handoff
summary, not as authority over the canonical technical specification, current
ADRs, source, configuration, or verified tests. Preserve unrelated dirty work
and keep unpublished audit drafts out of committed evidence.

## graphify

This project keeps a local knowledge graph at graphify-out/ (god nodes, communities, cross-file relationships). It is built with `graphify update .` and is not committed.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- If graphify-out/ is missing, build it with `graphify update .` (install: `py -m pip install graphifyy`).
