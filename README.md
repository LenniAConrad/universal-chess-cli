# Universal Chess CLI (ucicli)

Universal Chess CLI is a zero-dependency Java 17 command line toolkit for working with chess engines and curated puzzle data. It can convert Arena-style `.record` files, mine tactical positions by driving a UCI engine (e.g., Stockfish), and pretty-print arbitrary FENs for inspection.

---

## Highlights

- Pure Java 17 CLI—no external build tooling or runtime dependencies.
- Drives any UCI-compatible engine via a TOML protocol description (Stockfish template included).
- Converts `.record` JSON to `.plain` or `.csv` using the bundled converters.
- Mines single-solution puzzles from random seeds or custom FEN lists, with Chess960 support and a configurable filter DSL.
  Lines in a FEN list may contain one FEN (position only) or two FENs; when two are present the first is treated as the parent and the second as the position.
- Prints ASCII boards for any FEN to quickly visualize problem statements without a GUI.

---

## Requirements

- **Java 17+ JDK** (JRE is not enough when building from source).
- A **UCI chess engine** reachable on `$PATH` or pointed to via `config/default.engine.toml`.
- (Optional) `config/book.eco.toml` for ECO name lookups when printing or logging.

You may use `install.sh` on Debian/Ubuntu to install OpenJDK 17 and Stockfish automatically.

---

## Build & Run

### 1. Compile the sources

```bash
mkdir -p out
javac --release 17 -d out $(find src -name "*.java")
```

This emits `.class` files under `out/` with `application.Main` as the entry point.

### 2. (Optional) Package a runnable JAR

```bash
jar --create --file ucicli.jar --main-class application.Main -C out .
```

Run it anywhere:

```bash
java -jar ucicli.jar help
```

### 3. Run directly from classes

```bash
java -cp out application.Main help
java -cp out application.Main <command> [options]
```

### 4. Install a launcher (Linux only)

Execute `./install.sh` to compile, create `out/`, and place `/usr/local/bin/ucicli`. Afterwards run `ucicli ...` from any directory. The script will prompt before installing dependencies.

---

## Configuration Overview

All defaults live in `config/`:

| File                      | Purpose                                                                                         |
|---------------------------|-------------------------------------------------------------------------------------------------|
| `config/cli.config.toml`  | Application defaults (engine instances, node/time caps, output directories, filter DSL strings) |
| `config/default.engine.toml` | Describes how to speak to your UCI engine (path, option commands, go commands, Chess960 toggle, etc.) |
| `config/book.eco.toml`    | Optional ECO dictionary used when printing or logging                                          |

At runtime the CLI loads `cli.config.toml`. Any CLI flag overrides its counterpart in the file for that invocation. If the file is missing, it will be generated from hard-coded defaults the next time you run the tool.

### `config/cli.config.toml`

| Key                | Controls                                                                                                    | Default value                     | CLI override flag(s)                 |
|--------------------|-------------------------------------------------------------------------------------------------------------|-----------------------------------|--------------------------------------|
| `protocol-path`    | Path to the engine protocol TOML that declares UCI commands.                                                | `config/default.engine.toml`      | `--protocol-path`, `-P`              |
| `output`           | Default root for mined JSON outputs (directory or filename stem).                                          | `dump/`                           | `--output`, `-o` (mine command)      |
| `engine-instances` | Number of UCI engine processes to launch concurrently.                                                     | `2` (see file)                    | `--engine-instances`, `-e`           |
| `max-nodes`        | Per-position node cap issued as `go nodes <n>`.                                                            | `50_000_000`                      | `--max-nodes`                        |
| `max-duration`     | Per-position wall-clock limit (milliseconds) issued as `go movetime <ms>`.                                 | `1_000_000`                       | `--max-duration 60s|2m|60000`        |
| `puzzle-quality`   | Filter-DSL snippet to ensure minimum analysis depth per PV before accepting a candidate.                   | Require ≥50M nodes in PV1 & PV2   | `--puzzle-quality`                   |
| `puzzle-winning`   | Filter-DSL forcing PV1 to be winning (e.g., ≥ +300 cp) and PV2 to fail to match.                           | +300 / ≤ 0 centipawns             | `--puzzle-winning`                   |
| `puzzle-drawing`   | Filter-DSL forcing PV1 to hold equality while PV2 collapses (single drawing resource).                     | ≥ 0 / ≤ −300 centipawns           | `--puzzle-drawing`                   |
| `puzzle-accelerate`| Cheap pre-filter to reject hopeless nodes quickly before expensive verification.                           | ≥2M nodes + relaxed eval guards   | `--puzzle-accelerate`                |

Tips:

- `output` accepts a directory (`dump/`) or a file-like stem (`results.json` → produces `results.puzzles.json` and `results.nonpuzzles.json`).
- Durations accept plain milliseconds or Java `Duration` literals (`45s`, `2m`, `500ms`) when provided as CLI flags.
- Filter DSL syntax matches `chess.uci.Filter` (see `src/application/Config.java` for examples). Override one filter and the verifier is rebuilt using your mix.

Example customization:

```toml
protocol-path    = "config/my.engine.toml"
output           = "/data/ucicli/"
engine-instances = 4
max-nodes        = 75_000_000
max-duration     = 90_000

puzzle-winning = """
gate=AND;
leaf[eval>=250];
leaf[break=2;null=false;eval<=-50];
"""
```

### `config/default.engine.toml`

This file maps logical engine actions to the exact text commands sent over UCI. Important keys:

| Key                 | Description                                                                                           |
|---------------------|-------------------------------------------------------------------------------------------------------|
| `path`              | Executable name or absolute path to your engine (e.g., `"/opt/stockfish/stockfish"`).                 |
| `settings`          | Free-form string to describe how you configure the engine (optional, informational).                  |
| `setThreadAmount`   | Command template for setting thread counts (`setoption name Threads value %d`).                       |
| `setMultiPivotAmount` | Template for `multipv` (must be ≥ 2 when mining puzzles).                                         |
| `searchNodes`/`searchTime`/`searchDepth` | Templates for `go` commands with `%d` placeholders.                         |
| `setChess960`       | Template toggling Chess960 (used when `--chess960` is passed).                                        |
| `setup` array       | Optional list of commands executed once after `uci`/`isready` (set hash size, multipv, nets, etc.).   |

If you install a custom engine, duplicate this file (e.g., `config/dragon.engine.toml`), change `path`, update `setup` commands, and point `protocol-path` at it.

---

## Filter DSL

The `mine` command evaluates every analyzed position with small Boolean programs written in a compact DSL (used by `puzzle-quality`, `puzzle-winning`, `puzzle-drawing`, and `puzzle-accelerate`). A DSL string is a list of `;`-separated tokens:

```
gate=AND;null=false;empty=false;break=1;nodes>=50000000;
leaf[gate=AND;break=2;eval<=0]
```

Tokens and meaning (order does not matter; `;` separates tokens):

- `gate=<AND|OR|NOT_AND|NOT_OR|XOR|X_NOT_OR|SAME|NOT_SAME>`: how to combine all predicates and child leaves in this block.
- `null=<true|false>`: return value when the selected PV is missing/invalid. Defaults to `false`.
- `empty=<true|false>`: return value when a block has no predicates or leaves. Defaults to `false`.
- `break=<n>`: which MultiPV line this block inspects (1 = best PV, 2 = second-best, etc.). If omitted and predicates exist, PV#1 is used.
- Comparison predicates use `<metric><op><value>` with ops `> >= = <= <`:
  - `nodes`, `nps`, `tbhits`, `time` (ms), `depth`, `seldepth`, `multipv`, `hashfull` (0..1000).
  - `eval`: centipawns or mate, e.g., `300` or `#-2` (mate in 2 for the opponent).
  - `chances`: win/draw/loss triple; accepts `wdl 790 200 10`, `79 20 1`, or `1000/0/0` and normalizes to 0..1000 scale.
- `leaf[ ... ]`: nested block evaluated separately; its result is combined with the parent via the parent’s `gate`.
- `predicates=<n>`: informational only (emitted when serializing); it is ignored while parsing.
- Whitespace is ignored; TOML triple quotes let you split the string across lines.

---

## Commands & Flags

General form:

```bash
java -cp out application.Main <command> [options]
ucicli <command> [options]                # when install.sh is used
```

### Shared flags

| Flag                            | Meaning                                                                 | Default source           |
|---------------------------------|-------------------------------------------------------------------------|--------------------------|
| `--protocol-path`, `-P`         | Override engine protocol TOML path.                                     | `config/cli.config.toml` |
| `--engine-instances`, `-e`      | Override the number of engine workers to spin up.                       | config                   |
| `--max-nodes <n>`               | Override per-position node cap.                                         | config                   |
| `--max-duration <dur>`          | Override per-position time cap (`60s`, `2m`, `4500ms`, etc.).           | config                   |
| `--verbose`, `-v`               | Print stack traces for unexpected failures (supported by `print` & `mine`). | off                  |

### `record-to-plain`

Convert `.record` JSON → `.plain` (compact) while optionally filtering or exporting sidelines.

| Flag(s)                               | Description                                                      | Default |
|---------------------------------------|------------------------------------------------------------------|---------|
| `--input`, `-i <file.record>`         | Source Arena `.record` JSON (required).                          | —       |
| `--output`, `-o <file.plain>`         | Output path (defaults to input name with `.plain`).              | derived |
| `--filter`, `-f "<dsl>"`              | Filter-DSL string to keep only matching records.                 | none    |
| `--sidelines`, `--export-all`, `-a`   | Include sidelines/secondary variations when exporting.           | off     |

Example:

```bash
java -cp out application.Main record-to-plain \
  --input data/training.record \
  --output dump/training.plain \
  --sidelines
```

### `record-to-csv`

Convert `.record` JSON → `.csv` without emitting a `.plain` file. Shares the same filter DSL as `record-to-plain`.

| Flag(s)                       | Description                                                      | Default |
|-------------------------------|------------------------------------------------------------------|---------|
| `--input`, `-i <file.record>` | Source Arena `.record` JSON (required).                          | —       |
| `--output`, `-o <file.csv>`   | Output path (defaults to input name with `.csv`).                | derived |
| `--filter`, `-f "<dsl>"`      | Filter-DSL string to keep only matching records.                 | none    |

Example:

```bash
java -cp out application.Main record-to-csv \
  --input data/training.record \
  --output dump/training.csv
```

### `print`

Pretty-print any FEN as ASCII for quick inspection.

| Flag(s)          | Description                                                |
|------------------|------------------------------------------------------------|
| `--fen "<FEN>"`  | Complete FEN string. You may also pass it positionally.    |
| `--verbose`, `-v`| Emit stack traces if the FEN cannot be parsed.             |

```bash
java -cp out application.Main print --fen "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
```

### `mine`

Drive the engine over seeds, enforce puzzle filters, and export JSON lines for both puzzles and rejects. When no `--input` is provided the CLI generates random legal positions (Chess960-aware with `--chess960`).

**Input & output**

| Flag(s)                         | Description                                                                                     | Default           |
|---------------------------------|-------------------------------------------------------------------------------------------------|-------------------|
| `--input`, `-i <fen-list.txt>`  | UTF-8 text file with one FEN per line (comments allowed with `#`/`//`).                         | Random seeds      |
| `--output`, `-o <dir|file>`     | Directory or file stem for JSON outputs (see `output` config).                                  | `dump/`           |
| `--chess960`, `-9`              | Treat seeds/random positions as Chess960 positions.                                             | off               |

**Random generation & limits**

| Flag                     | Description                                                                                   | Default |
|-------------------------|-----------------------------------------------------------------------------------------------|---------|
| `--random-count <n>`    | Number of seeds to generate when refilling the frontier.                                      | 100     |
| `--random-infinite`     | Keep refilling seeds forever (ignores `--max-waves` and `--max-total`).                       | off     |
| `--max-waves <n>`       | Stop after this many expansion waves (ignored with `--random-infinite`).                      | 100     |
| `--max-frontier <n>`    | Hard cap on frontier size per wave to bound RAM usage.                                        | 5,000   |
| `--max-total <n>`       | Abort after processing this many records (ignored with `--random-infinite`).                  | 500,000 |

**Puzzle gates**

| Flag                    | Purpose                                                                                           | Default source |
|------------------------|---------------------------------------------------------------------------------------------------|----------------|
| `--puzzle-quality`     | Require minimum depth/nodes before spending verification time.                                    | config         |
| `--puzzle-winning`     | DSL that defines “single winning move” positions.                                                  | config         |
| `--puzzle-drawing`     | DSL that defines “single drawing move” positions.                                                  | config         |
| `--puzzle-accelerate`  | Lightweight pre-filter to short-circuit hopeless lines early.                                     | config         |

The DSL uses `gate`, `leaf`, `eval`, `nodes`, and `break` keywords (see `Config.java` for ready-made snippets).

Outputs are JSON (one object per line). When you pass a directory, files are timestamped (`standard-<ts>.puzzles.json`, `...nonpuzzles.json`). Passing `--output results.jsonl` yields `results.puzzles.json` and `results.nonpuzzles.json`.

---

## Usage Examples

```bash
# 1) Inspect a single FEN quickly
java -cp out application.Main print --fen "8/8/8/2k5/2Pp4/1P1K4/8/8 w - - 0 1"

# 2) Convert a record file and keep sidelines
java -cp out application.Main record-to-plain -i data/source.record -o dump/source.plain --sidelines

# 3) Export a record file to CSV only
java -cp out application.Main record-to-csv -i data/source.record -o dump/source.csv

# 4) Mine 500 random seeds with higher limits
java -cp out application.Main mine \
  --random-count 500 \
  --engine-instances 8 \
  --max-nodes 75_000_000 \
  --max-duration 90s \
  --output dump/

# 5) Mine random Chess960 positions, infinite refill
java -cp out application.Main mine \
  --chess960 \
  --random-infinite \
  --output dump/chess960.json

# 6) Mine from a FEN text file (one or two FENs per line)
java -cp out application.Main mine \
  --input seeds/tactics.txt \
  --output dump/tactics.json
  # Lines may contain:
  #   - one FEN (used as the position, parent left null), or
  #   - two FENs (first = parent, second = position). Extra tokens are ignored if two FENs are present.

# 7) Mine from a PGN file (extracts positions from mainline + variations)
java -cp out application.Main mine \
  --input seeds/games.pgn \
  --output dump/pgn-derived.json

# 8) Clean session cache/logs
java -cp out application.Main clean
```

---

## Outputs & Logs

- `dump/` (default) collects your `*.puzzles.json` and `*.nonpuzzles.json` lines files. Change via config or `--output`.
- `session/` stores structured logs (`application.log`, etc.) generated by `chess.debug.LogService`.
- Clean up old session logs anytime with `java -cp out application.Main clean` (removes files under `session/`).
- Converter outputs respect the destination you pass via `--output`; otherwise files appear adjacent to the source.

---

## License

Distributed under the terms of `LICENSE.txt`.
