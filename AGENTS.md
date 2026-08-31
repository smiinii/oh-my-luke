# OML Development Guidance

Use the core constraints in this file for ordinary implementation, tests, refactoring, and documentation changes. Do not preload every planning document.

Load additional context only when the task requires it:

- For product scope, roadmap, user experience, provider policy, or architecture-boundary decisions, consult `2026-08-23-Oh My Luke 통합 기획안.md`.
- For benchmarks, token measurement, isolation, evaluation methodology, or portfolio claims, consult `2026-08-25-OML-실험과-포트폴리오.md`.
- For ordinary implementation work, read only the repository files directly relevant to the task.
- If a decision is unclear or conflicts with these constraints, consult the relevant source note before proceeding.
- If the local Obsidian notes are unavailable, use the relevant file under `docs/` and call out any assumption that could change product scope or experiment validity.

Keep repository documents aligned with accepted decisions, but do not read or rewrite unrelated documents merely for completeness.

Core constraints:

- Use Java 21 and Gradle Kotlin DSL.
- Keep one graph kernel; Direct and Loop are graph presets, not separate engines.
- Keep the graph core testable without an AI runtime or network.
- Prefer deterministic Java validation before AI review.
- Treat Codex CLI as the first BYOR adapter, not as the core architecture.
- Keep public distribution self-contained: GitHub Releases first, then Homebrew and WinGet; do not require user-installed Java or Node.js and do not add npm distribution without revisiting the product decision.
- Do not add Spring Boot, a database, a GUI, dynamic graphs, or parallel AI execution to the MVP.
- Follow [the product scope](docs/product.md) for deferring OML-managed sub-agent orchestration and skill loading beyond the MVP; this does not restrict agents or skills used to develop and review OML.
- Never claim token savings without reproducible benchmark evidence.
- Keep Baseline and OML benchmark arms mutually isolated while giving both the same external capabilities.
- After completing a milestone, add a concise numbered Markdown file under `docs/how-it-works/` and link it from `docs/how-it-works/README.md`. Explain the flow, purpose, verification evidence, and current limitations for non-programmers. Do not turn it into a class-by-class code reference.
- Keep `docs/architecture.md` as an approximately 100-line overview and reading map. Update the relevant topic document instead of appending milestone implementation details; preserve unique technical evidence when reorganizing.
- Read `docs/core-runtime.md` for graph execution, checkpoint, recovery, or policy-counter changes. Other topic documents remain selective context, not a mandatory reading chain.

Benchmark isolation:

- Baseline and OML worker agents receive the same task packet, the same target-project context, and access to the same common files.
- A worker agent must not read only the opposing arm's workspace, artifacts, state, or logs.
- Do not add different file or capability restrictions to one arm. Any common project document available to one arm must be available to the other.
- Enforce the opposing-arm restriction through separate workspaces and explicit filesystem visibility; do not rely only on prompt instructions.
