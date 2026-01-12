# Roadmap / ideas

This page is a lightweight backlog of **proposed** additions. Anything listed here may change, and is not implemented unless it appears in `wiki/command-reference.md`.

## Proposed CLI subcommands

- `doctor`: sanity-check Java, engine discovery, config resolution, and optional CUDA backend availability.
- `puzzles-to-pgn`: export mined puzzles (puzzle JSON / `Stack-*.json`) to PGN (with solution line(s), tags, and optional “fail move”).
- `dump-filter` / `dump-grep`: apply Filter DSL to `.record` / puzzle dumps and emit JSONL/CSV subsets.
- `dump-merge` + `dump-dedupe`: merge shards and deduplicate by `(fen, bestmove[, pv])` with stable ordering.
- `uci-shell`: interactive UCI REPL (send commands, parse `info`, show PVs/WDL, quick presets like `go nodes` / `go movetime`).
- `arena`: engine-vs-engine matches (time controls, opening suite, PGN output, Elo summary).

## Developer tooling (optional)

- `just` (or a `Makefile`): one-liner entrypoints for `build`, `release`, `install`, and quick smoke tests.
- `pre-commit` hooks: run `shellcheck` on `install.sh` / `scripts/` and apply `google-java-format` consistently.
- `jlink` / `jpackage`: optional self-contained distributions so users can run without a system JDK.

