package application;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import application.console.Bar;
import chess.core.Position;
import chess.core.SAN;
import chess.core.Setup;
import chess.debug.LogService;
import chess.debug.SessionCache;
import chess.debug.Printer;
import chess.io.Converter;
import chess.misc.Pgn;
import chess.io.Reader;
import chess.io.Writer;
import chess.uci.Filter;
import chess.uci.Filter.FilterDSL;
import chess.model.Record;
import utility.Argv;

/**
 * Used for providing the CLI entry point and dispatching subcommands.
 *
 * <p>
 * Recognized subcommands are {@code record-to-plain}, {@code record-to-csv},
 * {@code record-to-dataset}, {@code stack-to-dataset}, {@code mine},
 * {@code gen-fens}, {@code eval-fens}, {@code print}, {@code clean}, and {@code help}.
 * Prints usage information when no subcommand is supplied. For unknown
 * subcommands, prints an
 * error and exits with status {@code 2}.
 * 
 * @since 2025
 * @author Lennart A. Conrad
 */
public final class Main {

	/**
	 * Used for the {@code --input | -i} option.
	 */
	private static final String OPT_INPUT = "--input";

	/**
	 * Used for the {@code --output | -o} option.
	 */
	private static final String OPT_OUTPUT = "--output";

	/**
	 * Used for the {@code --verbose | -v} option.
	 */
	private static final String OPT_VERBOSE = "--verbose";

	/**
	 * Used for parsing top-level CLI arguments and delegating to a subcommand
	 * handler.
	 *
	 * <p>
	 * Behavior:
	 * <ul>
	 * <li>Attempts to read the first positional argument as the subcommand.</li>
	 * <li>Delegates remaining positionals to the corresponding {@code run*}
	 * method.</li>
	 * <li>On unknown subcommands, prints help and exits with non-zero status.</li>
	 * </ul>
	 *
	 * @param argv raw command-line arguments; first positional must be a valid
	 *             subcommand.
	 */
	public static void main(String[] argv) {
		Argv a = new Argv(argv);

		List<String> head = a.positionals();

		a.ensureConsumed();

		if (head.isEmpty()) {
			help();
			return;
		}

		String sub = head.get(0);
		String[] tail = head.subList(1, head.size()).toArray(new String[0]);
		Argv b = new Argv(tail);

		switch (sub) {
			case "record-to-plain" -> runConvert(b);
			case "record-to-csv" -> runConvertCsv(b);
			case "record-to-dataset" -> runRecordToDataset(b);
			case "stack-to-dataset" -> runStackToDataset(b);
			case "gen-fens" -> runGenerateFens(b);
			case "eval-fens" -> runEvalFens(b);
			case "mine" -> runMine(b);
			case "print" -> runPrint(b);
			case "clean" -> runClean(b);
			case "help", "-h", "--help" -> help();
			default -> {
				System.err.println("Unknown command: " + sub);
				help();
				System.exit(2);
			}
		}
	}

	/**
	 * Used for handling the {@code record-to-plain} subcommand.
	 *
	 * <p>
	 * Converts a {@code .record} JSON file into a {@code .plain} file. Optionally
	 * filters
	 * records using a Filter-DSL string and/or includes sidelines in the output.
	 *
	 * <p>
	 * Side effects:
	 * <ul>
	 * <li>Reads from the provided input path.</li>
	 * <li>Writes a new file to the output path (derived when omitted).</li>
	 * </ul>
	 *
	 * @param a parsed argument vector for the subcommand. Recognized options:
	 *          <ul>
	 *          <li>{@code -a | --export-all | --sidelines} — include sidelines in
	 *          the output.</li>
	 *          <li>{@code -f | --filter <dsl>} — Filter-DSL used to select
	 *          records.</li>
	 *          <li>{@code -i | --input <path>} — required input {@code .record}
	 *          file.</li>
	 *          <li>{@code -o | --output <path>} — optional output {@code .plain}
	 *          file path.</li>
	 *          </ul>
	 */
	private static void runConvert(Argv a) {
		boolean exportAll = a.flag("--sidelines", "--export-all", "-a");
		String filterDsl = a.string("--filter", "-f");
		Path in = a.pathRequired(OPT_INPUT, "-i");
		Path out = a.path(OPT_OUTPUT, "-o");
		boolean csv = a.flag("--csv");
		Path csvOut = a.path("--csv-output", "-c");
		a.ensureConsumed();

		Filter filter = null;
		if (filterDsl != null && !filterDsl.isEmpty()) {
			filter = FilterDSL.fromString(filterDsl);
		}

		Converter.recordToPlain(exportAll, filter, in, out);
		if (csv || csvOut != null) {
			Converter.recordToCsv(filter, in, csvOut);
		}
	}

	/**
	 * Used for handling the {@code record-to-csv} subcommand.
	 *
	 * <p>
	 * Converts a {@code .record} JSON file into a CSV export without also writing
	 * a {@code .plain} file. This mirrors {@link #runConvert(Argv)} but only emits
	 * CSV output.
	 *
	 * @param a parsed argument vector for the subcommand. Recognized options:
	 *          <ul>
	 *          <li>{@code -f | --filter <dsl>} — Filter-DSL used to select
	 *          records.</li>
	 *          <li>{@code -i | --input <path>} — required input {@code .record}
	 *          file.</li>
	 *          <li>{@code -o | --output <path>} — optional output {@code .csv}
	 *          file path.</li>
	 *          </ul>
	 */
	private static void runConvertCsv(Argv a) {
		String filterDsl = a.string("--filter", "-f");
		Path in = a.pathRequired(OPT_INPUT, "-i");
		Path out = a.path(OPT_OUTPUT, "-o");
		a.ensureConsumed();

		Filter filter = null;
		if (filterDsl != null && !filterDsl.isEmpty()) {
			filter = FilterDSL.fromString(filterDsl);
		}

		Converter.recordToCsv(filter, in, out);
	}

	/**
	 * Convert a .record JSON array into Numpy .npy tensors (features/labels).
	 */
	private static void runRecordToDataset(Argv a) {
		Path in = a.pathRequired(OPT_INPUT, "-i");
		Path out = a.path(OPT_OUTPUT, "-o");
		a.ensureConsumed();

		if (out == null) {
			// Default: alongside input with stem + ".dataset"
			String stem = in.getFileName().toString();
			int dot = stem.lastIndexOf('.');
			if (dot > 0) {
				stem = stem.substring(0, dot);
			}
			out = in.resolveSibling(stem + ".dataset");
		}

		try {
			chess.io.RecordDatasetExporter.export(in, out);
			System.out.printf("Wrote %s.features.npy and %s.labels.npy%n", out, out);
		} catch (IOException e) {
			System.err.println("Failed to export dataset: " + e.getMessage());
			System.exit(2);
		}
	}

	/**
	 * Convert a Stack-*.json puzzle dump (JSON array) into NPY tensors.
	 */
	private static void runStackToDataset(Argv a) {
		Path in = a.pathRequired(OPT_INPUT, "-i");
		Path out = a.path(OPT_OUTPUT, "-o");
		a.ensureConsumed();

		if (out == null) {
			String stem = in.getFileName().toString();
			int dot = stem.lastIndexOf('.');
			if (dot > 0) {
				stem = stem.substring(0, dot);
			}
			out = in.resolveSibling(stem + ".dataset");
		}

		try {
			chess.io.StackDatasetExporter.export(in, out);
			System.out.printf("Wrote %s.features.npy and %s.labels.npy%n", out, out);
		} catch (IOException e) {
			System.err.println("Failed to export stack dataset: " + e.getMessage());
			System.exit(2);
		}
	}

	/**
	 * Generate shard files containing random legal FENs (standard or Chess960).
	 *
	 * <p>
	 * Defaults: 1,000 files, 100,000 FENs each, first 100 files Chess960, output
	 * directory {@code all_positions_shards}, batch size 2,048.
	 *
	 * @param a parsed argument vector
	 */
	private static void runGenerateFens(Argv a) {
		final boolean verbose = a.flag(OPT_VERBOSE, "-v");
		Path outDir = a.path(OPT_OUTPUT, "-o");
		final int files = a.integerOr(1_000, "--files");
		final int perFile = a.integerOr(100_000, "--per-file", "--fens-per-file");
		final int chess960Files = a.integerOr(100, "--chess960-files", "--chess960");
		final int batch = a.integerOr(2_048, "--batch");
		final boolean ascii = a.flag("--ascii");

		a.ensureConsumed();

		if (files <= 0) {
			System.err.println("gen-fens: --files must be positive");
			System.exit(2);
		}
		if (perFile <= 0) {
			System.err.println("gen-fens: --per-file must be positive");
			System.exit(2);
		}
		if (batch <= 0) {
			System.err.println("gen-fens: --batch must be positive");
			System.exit(2);
		}
		if (chess960Files < 0 || chess960Files > files) {
			System.err.printf("gen-fens: --chess960-files must be between 0 and %d%n", files);
			System.exit(2);
		}

		if (outDir == null) {
			outDir = Paths.get("all_positions_shards");
		}

		try {
			Files.createDirectories(outDir);
		} catch (IOException e) {
			System.err.println("gen-fens: failed to create output directory: " + e.getMessage());
			if (verbose) {
				e.printStackTrace(System.err);
			}
			System.exit(2);
		}

		final long total = (long) files * (long) perFile;
		final int barTotal = (total > Integer.MAX_VALUE) ? 0 : (int) total;
		final Bar bar = new Bar(barTotal, "fens", ascii);
		final int width = Math.max(4, String.valueOf(Math.max(files - 1, 0)).length());

		for (int i = 0; i < files; i++) {
			boolean useChess960 = i < chess960Files;
			String suffix = useChess960 ? "-960" : "-std";
			String name = String.format("fens-%0" + width + "d%s.txt", i, suffix);
			Path target = outDir.resolve(name);

			try {
				writeFenShard(target, perFile, useChess960, batch, bar);
			} catch (Exception e) {
				System.err.println("gen-fens: failed to write " + target + ": " + e.getMessage());
				if (verbose) {
					e.printStackTrace(System.err);
				}
				System.exit(1);
			}
		}

		bar.finish();
		System.out.printf(
				"gen-fens wrote %d files (%d Chess960) to %s%n",
				files,
				chess960Files,
				outDir.toAbsolutePath());
	}

	/**
	 * Used for writing a shard file of random FENs to disk.
	 *
	 * @param target    output path
	 * @param fenCount  number of FENs to write
	 * @param chess960  whether to seed from Chess960 starts
	 * @param batchSize how many random positions to generate per batch
	 * @param bar       progress bar (disabled when total is zero)
	 * @throws IOException when writing fails
	 */
	private static void writeFenShard(
			Path target,
			int fenCount,
			boolean chess960,
			int batchSize,
			Bar bar) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(target)) {
			int remaining = fenCount;
			while (remaining > 0) {
				int chunk = Math.min(batchSize, remaining);
				List<Position> positions = Setup.getRandomPositions(chunk, chess960);
				for (Position p : positions) {
					writer.write(p.toString());
					writer.newLine();
					bar.step();
				}
				remaining -= chunk;
			}
		}
	}

	/**
	 * Evaluate FEN shards with Stockfish "eval" and write JSONL outputs.
	 *
	 * Options:
	 * <ul>
	 * <li>{@code --input|-i} input directory with FEN shard .txt files (default
	 * {@code all_positions_shards})</li>
	 * <li>{@code --output|-o} output directory (default {@code eval_shards})</li>
	 * <li>{@code --workers|-e} number of engine workers (default Config
	 * engine-instances)</li>
	 * <li>{@code --protocol-path|-P} TOML describing engine path/options (default
	 * Config protocol-path)</li>
	 * <li>{@code --write-npy} also emit {@code .labels.npy} per shard
	 * (float32, shape (N,65): scalar then 64-cell grid)</li>
	 * <li>{@code --ascii} ASCII progress bars</li>
	 * </ul>
	 */
	private static void runEvalFens(Argv a) {
		Path input = a.path(OPT_INPUT, "-i");
		Path output = a.path(OPT_OUTPUT, "-o");
		int workers = a.integerOr(Config.getEngineInstances(), "--workers", "-e");
		String proto = optional(a.string("--protocol-path", "-P"), Config.getProtocolPath());
		boolean ascii = a.flag("--ascii");
		boolean writeNpy = a.flag("--write-npy");
		final boolean verbose = a.flag(OPT_VERBOSE, "-v");
		a.ensureConsumed();

		if (input == null) {
			input = Paths.get("all_positions_shards");
		}
		if (output == null) {
			output = Paths.get("eval_shards");
		}

		try {
			EvalMiner.mine(input, output, workers, proto, ascii, writeNpy);
		} catch (Exception e) {
			System.err.println("eval-fens failed: " + e.getMessage());
			if (verbose) {
				e.printStackTrace(System.err);
			}
			System.exit(1);
		}
	}

	/**
	 * Used for handling the {@code print} subcommand.
	 *
	 * <p>
	 * Parses a FEN supplied via {@code --fen} or as a single positional argument
	 * after the
	 * subcommand and pretty-prints the position. Exits with status {@code 2} when
	 * no FEN is provided.
	 *
	 * <p>
	 * Options:
	 * <ul>
	 * <li>{@code --fen "<FEN...>"} — FEN string; may also be provided
	 * positionally.</li>
	 * <li>{@code --verbose} or {@code -v} — also print stack traces to stderr on
	 * errors.</li>
	 * </ul>
	 */
	private static void runPrint(Argv a) {
		boolean verbose = a.flag(OPT_VERBOSE, "-v");
		String fen = a.string("--fen");
		List<String> rest = a.positionals();
		if (fen == null && !rest.isEmpty()) {
			fen = String.join(" ", rest);
		}

		a.ensureConsumed();

		if (fen == null || fen.isEmpty()) {
			System.err.println("print requires a FEN (use --fen or positional)");
			System.exit(2);
			return;
		}

		try {
			Position pos = new Position(fen.trim());
			Printer.board(pos);
		} catch (IllegalArgumentException ex) {
			// Invalid FEN or position construction error
			System.err.println("Error: invalid FEN. " + (ex.getMessage() == null ? "" : ex.getMessage()));
			LogService.error(ex, "print: invalid FEN", "FEN: " + fen);
			if (verbose) {
				ex.printStackTrace(System.err);
			}
			System.exit(3);
		} catch (Exception t) {
			System.err.println("Error: failed to print position. " + (t.getMessage() == null ? "" : t.getMessage()));
			LogService.error(t, "print: unexpected failure while printing position", "FEN: " + fen);
			if (verbose) {
				t.printStackTrace(System.err);
			}
			System.exit(3);
		}
	}

	/**
	 * Used for handling the {@code clean} subcommand.
	 *
	 * <p>
	 * Deletes cached session artifacts under the default session directory while
	 * preserving the directory itself.
	 *
	 * @param a parsed argument vector for the subcommand. Recognized options:
	 *          <ul>
	 *          <li>{@code --verbose} or {@code -v} — print stack traces on
	 *          failure.</li>
	 *          </ul>
	 */
	private static void runClean(Argv a) {
		boolean verbose = a.flag(OPT_VERBOSE, "-v");
		a.ensureConsumed();

		try {
			SessionCache.clean();
			SessionCache.ensureDirectory();
			System.out.println("Session cache cleared: " + SessionCache.directory().toAbsolutePath());
		} catch (Exception ex) {
			System.err.println("Failed to clean session cache: " + ex.getMessage());
			if (verbose) {
				ex.printStackTrace(System.err);
			}
			System.exit(1);
		}
	}

	/**
	 * Used for printing usage information to standard output.
	 *
	 * <p>
	 * Includes brief explanations for each subcommand and the most relevant flags.
	 * Intended for
	 * interactive use from the command line.
	 */
	private static void help() {
		System.out.println(
				"""
						usage: app <command> [options]

						commands:
						  record-to-plain Convert .record JSON to .plain
						  record-to-csv  Convert .record JSON to CSV (no .plain output)
						  record-to-dataset Convert .record JSON to NPY tensors (features/labels)
						  stack-to-dataset Convert Stack-*.json puzzle dumps to NPY tensors
						  gen-fens  Generate random legal FEN shards (standard + Chess960 mix)
						  eval-fens Evaluate FEN shards with Stockfish \"eval\" (JSONL per shard)
						  mine      Mine chess puzzles (supports Chess960 / PGN / FEN list / random)
						  print     Pretty-print a FEN
						  clean     Delete session cache/logs

						record-to-plain options:
						  --input|-i <path>          Input .record file (required)
						  --output|-o <path>         Output .plain file (optional; default derived)
						  --csv                      Also emit a CSV export (default path derived)
						  --csv-output|-c <path>     CSV output path (enables CSV export)
						  --filter|-f <dsl>          Filter-DSL string for selecting records
						  --sidelines|--export-all   Include sidelines in output

						record-to-csv options:
						  --input|-i <path>          Input .record file (required)
						  --output|-o <path>         Output .csv file (optional; default derived)
						  --filter|-f <dsl>          Filter-DSL string for selecting records

						record-to-dataset options:
						  --input|-i <path>          Input .record file (required, JSON array)
						  --output|-o <path>         Output stem (writes <stem>.features.npy, <stem>.labels.npy)

						stack-to-dataset options:
						  --input|-i <path>          Input Stack-*.json file (required, JSON array)
						  --output|-o <path>         Output stem (writes <stem>.features.npy, <stem>.labels.npy)

						gen-fens options:
						  --output|-o <dir>          Output directory (default all_positions_shards)
						  --files <n>                Number of files to generate (default 1000)
						  --per-file <n>             FENs per file (default 100000)
						  --chess960-files <n>       Files to seed from Chess960 (default 100)
						  --batch <n>                Random positions per batch (default 2048)
						  --ascii                    Render ASCII progress bar
						  --verbose|-v               Print stack trace on failure

						eval-fens options:
						  --input|-i <dir>           Input shard directory (default all_positions_shards)
						  --output|-o <dir>          Output directory (default eval_shards)
						  --workers|-e <n>           Engine workers (default config engine-instances)
						  --protocol-path|-P <toml>  Engine protocol TOML (default config)
						  --write-npy                Also emit <shard>.labels.npy (shape (N,65): scalar + 64 grid)
						  --ascii                    Render ASCII progress bars
						  --verbose|-v               Print stack trace on failure

						mine options (overrides & inputs):
						  --chess960|-9               Enable Chess960 mining
						  --input|-i <path>           PGN or TXT with FENs; omit to use random
						  --output|-o <path>          Output path/dir for puzzles

						  --protocol-path|-P <toml>   Override Config.getProtocolPath()
						  --engine-instances|-e <n>   Override Config.getEngineInstances()
						  --max-nodes <n>             Override Config.getMaxNodes()
						  --max-duration <dur>        Override Config.getMaxDuration(), e.g. 60s, 2m, 60000

						  --random-count <n>          Random seeds to generate (default 100)
						  --random-infinite           Continuously add random seeds (ignores waves/total caps)
						  --max-waves <n>             Override maximum waves (default 4; ignored with --random-infinite)
						  --max-frontier <n>          Override frontier cap (default 1_000)
						  --max-total <n>             Override total processed cap (default 500_000; ignored with --random-infinite)

						  --puzzle-quality <dsl>      Override quality gate DSL
						  --puzzle-winning <dsl>      Override winning gate DSL
						  --puzzle-drawing <dsl>      Override drawing gate DSL
						  --puzzle-accelerate <dsl>   Override accelerate prefilter DSL

						print options:
						  --fen "<FEN...>"            FEN string (or supply as positional)
						  --verbose|-v                Print stack trace on failure (parsing errors)

						clean options:
						  --verbose|-v                Print stack trace on failure
						""");
	}

	/**
	 * Used for handling the {@code mine} subcommand.
	 *
	 * <p>
	 * Resolves runtime configuration and filters, loads or generates seed
	 * positions,
	 * runs batched engine analysis in bounded waves, expands verified puzzles, and
	 * appends
	 * JSONL outputs for puzzles and non-puzzles.
	 *
	 * @param a parsed argument vector for the subcommand
	 */
	private static void runMine(Argv a) {
		final boolean verbose = a.flag(OPT_VERBOSE, "-v");
		final boolean chess960 = a.flag("--chess960", "-9");
		final Path input = a.path(OPT_INPUT, "-i");
		final String outRoot = optional(a.string(OPT_OUTPUT, "-o"), Config.getOutput());
		final String proto = optional(a.string("--protocol-path", "-P"), Config.getProtocolPath());
		final long engineInstances = optional(a.lng("--engine-instances", "-e"), Config.getEngineInstances());
		final long nodesCap = Math.max(1, optional(a.lng("--max-nodes"), Config.getMaxNodes()));
		final long durMs = Math.max(
				1,
				optionalDurationMs(a.duration("--max-duration"), Config.getMaxDuration()));
		final Long randomSeedOverride = a.lng("--random-count");
		final boolean randomInfinite = a.flag("--random-infinite");
		final Long maxWavesOverride = a.lng("--max-waves");
		final Long maxFrontierOverride = a.lng("--max-frontier");
		final Long maxTotalOverride = a.lng("--max-total");

		final String qGate = a.string("--puzzle-quality");
		final String wGate = a.string("--puzzle-winning");
		final String dGate = a.string("--puzzle-drawing");
		final String accelDsl = a.string("--puzzle-accelerate");

		final Filter accel = filterOrDefault(accelDsl, Config::getPuzzleAccelerate);
		final Filter qF = filterOrDefault(qGate, Config::getPuzzleQuality);
		final Filter wF = filterOrDefault(wGate, Config::getPuzzleWinning);
		final Filter dF = filterOrDefault(dGate, Config::getPuzzleDrawing);
		final boolean anyOverride = (qGate != null) || (wGate != null) || (dGate != null);
		final Filter verify = anyOverride ? Config.buildPuzzleVerify(qF, wF, dF) : Config.getPuzzleVerify();

		a.ensureConsumed();

		final int randomSeeds = Math.toIntExact(Math.max(1, optional(randomSeedOverride, DEFAULT_RANDOM_SEEDS)));
		int maxWaves = Math.toIntExact(Math.max(1, optional(maxWavesOverride, DEFAULT_MAX_WAVES)));
		int maxFrontier = Math.toIntExact(Math.max(1, optional(maxFrontierOverride, DEFAULT_MAX_FRONTIER)));
		long maxTotal = Math.max(1, optional(maxTotalOverride, DEFAULT_MAX_TOTAL));

		if (randomInfinite) {
			maxWaves = Integer.MAX_VALUE;
			maxTotal = Long.MAX_VALUE;
		}

		OutputTargets outs = resolveOutputs(outRoot, chess960);
		List<Record> seeds;

		try {
			if (input != null) {
				seeds = loadRecordsFromInput(input);
			} else {
				seeds = wrapSeeds(Setup.getRandomPositionSeeds(randomSeeds, chess960));
			}
		} catch (Exception ex) {
			LogService.error(ex, "Failed to load seed positions (input=%s)", String.valueOf(input));
			System.out.println("Failed to load seed positions; see log for details.");
			if (verbose) {
				ex.printStackTrace(System.out);
			}
			System.exit(2);
			return;
		}

		final List<Record> frontier = seeds;
		final MiningConfig config = new MiningConfig(
				accel,
				verify,
				nodesCap,
				durMs,
				outs,
				randomInfinite,
				chess960,
				randomSeeds,
				maxFrontier,
				maxWaves,
				maxTotal);

		try (Pool pool = Pool.create(Math.toIntExact(Math.max(1, engineInstances)), proto)) {
			// Touch output files up front so incremental flushes can append safely.
			flushJsonLines(outs.puzzles, List.of());
			flushJsonLines(outs.nonpuzzles, List.of());

			mine(pool, frontier, config);
		} catch (Exception e) {
			LogService.error(e, "Failed during mining (pool/create/analyse/flush)");
			System.out.println("Mining failed; see log for details.");
			if (verbose) {
				e.printStackTrace(System.out);
			}
			System.exit(1);
		}
	}

	/**
	 * Used for wrapping positions into mining records (with null parents).
	 *
	 * @param seeds source positions
	 * @return records initialized with positions
	 */
	private static List<Record> wrapSeeds(List<Setup.PositionSeed> seeds) {
		final List<Record> out = new ArrayList<>(seeds.size());
		for (Setup.PositionSeed seed : seeds) {
			out.add(new Record()
					.withPosition(seed.position())
					.withParent(seed.parent()));
		}
		return out;
	}

	/**
	 * Used for performing bounded multi-wave mining and expansion.
	 *
	 * <p>
	 * Applies an accelerate pre-filter, verifies puzzles, expands best-move
	 * replies,
	 * and prevents cycles via canonical FEN de-duplication.
	 *
	 * @param pool        shared engine pool
	 * @param frontier    initial frontier records
	 * @param accel       accelerate pre-filter
	 * @param verify      puzzle verification filter
	 * @param nodesCap    max nodes per position
	 * @param durMs       max duration per position (ms)
	 * @param outs        output targets for incremental persistence
	 * @param infinite    whether to keep generating random seeds when frontier is
	 *                    empty
	 * @param chess960    whether to generate Chess960 random seeds (when infinite)
	 * @param randomSeeds number of random seeds to generate per refill
	 * @param maxFrontier cap on frontier size per wave
	 * @param maxWaves    maximum waves to execute (Integer.MAX_VALUE for unbounded)
	 * @param maxTotal    maximum records to process (Long.MAX_VALUE for unbounded)
	 */
	private static void mine(
			Pool pool,
			List<Record> frontier,
			MiningConfig config) throws IOException {
		final Set<String> seenFen = new HashSet<>(frontier.size() * 2);
		final Set<String> analyzedFen = new HashSet<>(frontier.size() * 2);

		int waves = 0;
		int processed = 0;

		while (true) {
			if (frontier.isEmpty() && config.infinite()) {
				frontier = wrapSeeds(Setup.getRandomPositionSeeds(config.randomSeeds(), config.chess960()));
			}

			frontier = deduplicateFrontier(frontier, seenFen, analyzedFen);
			if (frontier.isEmpty()) {
				if (processed >= config.maxTotal() || waves >= config.maxWaves()) {
					break;
				}
				if (config.infinite()) {
					continue;
				}
			}

			if (shouldStop(frontier, waves, processed, config.maxWaves(), config.maxTotal())) {
				break;
			}

			frontier = capFrontier(frontier, config.maxFrontier());
			analyzeWave(pool, frontier, config.accel(), config.nodesCap(), config.durMs());

			final WaveState state = processFrontier(
					frontier,
					config.verify(),
					seenFen,
					analyzedFen,
					processed,
					config.maxTotal());

			if (!state.wavePuzzles.isEmpty()) {
				flushJsonLines(config.outs().puzzles, state.wavePuzzles);
			}
			if (!state.waveNonPuzzles.isEmpty()) {
				flushJsonLines(config.outs().nonpuzzles, state.waveNonPuzzles);
			}

			frontier = state.next;
			processed = state.processed;
			waves++;
		}
	}

	/**
	 * Used for holding per-wave results.
	 *
	 * @param next      next frontier
	 * @param processed total processed count so far
	 */
	private static final class WaveState {
		final List<Record> next;
		final int processed;
		final List<Record> wavePuzzles;
		final List<Record> waveNonPuzzles;

		WaveState(List<Record> next, int processed, List<Record> wavePuzzles, List<Record> waveNonPuzzles) {
			this.next = next;
			this.processed = processed;
			this.wavePuzzles = wavePuzzles;
			this.waveNonPuzzles = waveNonPuzzles;
		}
	}

	private record MiningConfig(
			Filter accel,
			Filter verify,
			long nodesCap,
			long durMs,
			OutputTargets outs,
			boolean infinite,
			boolean chess960,
			int randomSeeds,
			int maxFrontier,
			int maxWaves,
			long maxTotal) {
	}

	/** Used for default limiting the number of waves executed. */
	private static final int DEFAULT_MAX_WAVES = 100;

	/** Used for default capping the number of records per frontier. */
	private static final int DEFAULT_MAX_FRONTIER = 5_000;

	/** Used for default capping the total number of processed records. */
	private static final long DEFAULT_MAX_TOTAL = 500_000;

	/** Used for default random seed count when none are provided. */
	private static final int DEFAULT_RANDOM_SEEDS = 100;

	/**
	 * Used for deciding whether the mining loop should terminate.
	 *
	 * @param frontier  current frontier
	 * @param waves     waves completed
	 * @param processed total processed count
	 * @param maxWaves  maximum waves permitted
	 * @param maxTotal  maximum records permitted
	 * @return true when the loop must end
	 */
	private static boolean shouldStop(List<Record> frontier, int waves, int processed, int maxWaves, long maxTotal) {
		return frontier.isEmpty() || waves >= maxWaves || processed >= maxTotal;
	}

	/**
	 * Used for capping the frontier size to a fixed maximum.
	 *
	 * @param frontier current frontier
	 * @param limit    maximum allowed size
	 * @return possibly trimmed frontier
	 */
	private static List<Record> capFrontier(List<Record> frontier, int limit) {
		if (frontier.size() <= limit) {
			return frontier;
		}
		return new ArrayList<>(frontier.subList(0, limit));
	}

	/**
	 * Used for filtering out already-processed or duplicate positions from the
	 * frontier.
	 *
	 * @param frontier    current frontier
	 * @param seenFen     global de-duplication set (mutated to register queued
	 *                    positions)
	 * @param analyzedFen positions that have already been fully analyzed
	 * @return possibly trimmed frontier
	 */
	private static List<Record> deduplicateFrontier(
			List<Record> frontier,
			Set<String> seenFen,
			Set<String> analyzedFen) {
		if (frontier.isEmpty()) {
			return frontier;
		}

		final List<Record> unique = new ArrayList<>(frontier.size());
		final Set<String> waveSeen = new HashSet<>(frontier.size() * 2);

		for (Record rec : frontier) {
			final Position pos = rec.getPosition();
			if (pos == null) {
				continue;
			}
			final String fen = pos.toString();
			if (analyzedFen.contains(fen) || !waveSeen.add(fen)) {
				continue;
			}
			seenFen.add(fen); // Register for child de-duplication across waves.
			unique.add(rec);
		}

		return (unique.size() == frontier.size()) ? frontier : unique;
	}

	/**
	 * Used for analyzing a wave of records via the engine pool.
	 *
	 * @param pool     engine pool
	 * @param frontier current frontier
	 * @param accel    accelerate filter
	 * @param nodesCap nodes limit
	 * @param durMs    duration limit (ms)
	 */
	private static void analyzeWave(
			Pool pool,
			List<Record> frontier,
			Filter accel,
			long nodesCap,
			long durMs) {
		pool.analyseAll(frontier, accel, nodesCap, durMs);
	}

	/**
	 * Used for processing the analyzed frontier and building the next wave.
	 *
	 * @param frontier   current frontier
	 * @param verify     puzzle verification filter
	 * @param seenFen    FEN de-duplication set
	 * @param analyzedFen processed FEN set for skipping re-analysis
	 * @param puzzles    collected puzzles
	 * @param nonPuzzles collected non-puzzles
	 * @param processed  processed count so far
	 * @param maxTotal   maximum records permitted
	 * @return next frontier and updated processed count
	 */
	private static WaveState processFrontier(
			List<Record> frontier,
			Filter verify,
			Set<String> seenFen,
			Set<String> analyzedFen,
			int processed,
			long maxTotal) {
		final List<Record> next = new ArrayList<>(frontier.size() * 2);
		final List<Record> wavePuzzles = new ArrayList<>();
		final List<Record> waveNonPuzzles = new ArrayList<>();

		for (Record r : frontier) {
			processed++;
			final Position pos = r.getPosition();
			if (pos != null) {
				analyzedFen.add(pos.toString());
			}
			if (verify.apply(r.getAnalysis())) {
				wavePuzzles.add(r);
				expandBestMoveChildren(r, seenFen, analyzedFen, next, processed, maxTotal);
			} else {
				waveNonPuzzles.add(r);
			}
			if (processed >= maxTotal) {
				break;
			}
		}

		return new WaveState(next, processed, wavePuzzles, waveNonPuzzles);
	}

	/**
	 * Used for expanding a record's best move and queuing all child replies.
	 *
	 * @param r         analyzed record
	 * @param seenFen   de-duplication set
	 * @param analyzedFen processed FEN set for skipping re-analysis
	 * @param next      accumulator for next frontier
	 * @param processed processed count so far
	 * @param maxTotal  maximum records permitted
	 */
	private static void expandBestMoveChildren(
			Record r,
			Set<String> seenFen,
			Set<String> analyzedFen,
			List<Record> next,
			int processed,
			long maxTotal) {
		final short best = r.getAnalysis().getBestMove();
		final Position parent = r.getPosition().copyOf().play(best);

		for (Position child : parent.generateSubPositions()) {
			final String fen = child.toString(); // assumes FEN canonicalization
			if (analyzedFen.contains(fen)) {
				continue;
			}
			if (seenFen.add(fen)) {
				next.add(new Record().withPosition(child).withParent(parent));
				if (processed + next.size() >= maxTotal) {
					break;
				}
			}
		}
	}

	/**
	 * Used for returning {@code value} when non-null, otherwise the
	 * {@code def}ault.
	 *
	 * @param value candidate value
	 * @param def   default value
	 * @return chosen value
	 */
	private static String optional(String value, String def) {
		return (value == null || value.isEmpty()) ? def : value;
	}

	/**
	 * Used for returning {@code value} when non-null, otherwise the
	 * {@code def}ault.
	 *
	 * @param value candidate value
	 * @param def   default value
	 * @return chosen value
	 */
	private static long optional(Long value, long def) {
		return (value == null) ? def : value;
	}

	/**
	 * Used for converting the optional duration to milliseconds with a default.
	 *
	 * @param value candidate duration
	 * @param defMs default duration in milliseconds
	 * @return chosen duration in milliseconds
	 */
	private static long optionalDurationMs(Duration value, long defMs) {
		return (value == null) ? defMs : value.toMillis();
	}

	/**
	 * Used for grouping output target paths for puzzles and non-puzzles.
	 */
	private static final class OutputTargets {
		Path puzzles;
		Path nonpuzzles;

		/**
		 * Used for holding both puzzle and non-puzzle output targets.
		 *
		 * @param p path for puzzle JSONL output
		 * @param n path for non-puzzle JSONL output
		 */
		OutputTargets(Path p, Path n) {
			this.puzzles = p;
			this.nonpuzzles = n;
		}
	}

	/**
	 * Used for resolving output file paths from a root path or filename.
	 *
	 * <p>
	 * When {@code outputRoot} is file-like ({@code .json} or {@code .jsonl}), the
	 * method derives sibling {@code .puzzles.jsonl} and {@code .nonpuzzles.jsonl}
	 * files. Otherwise, generates timestamped filenames inside the provided
	 * directory, prefixed with the chess variant.
	 *
	 * @param outputRoot directory or file-like root specified on the CLI
	 * @param chess960   whether to tag outputs for Chess960
	 * @return resolved pair of output targets
	 */
	private static OutputTargets resolveOutputs(String outputRoot, boolean chess960) {
		boolean isFileLike = outputRoot.endsWith(".json") || outputRoot.endsWith(".jsonl");
		Path basePath = Paths.get(outputRoot);
		String baseStem;

		if (isFileLike) {
			String fn = basePath.getFileName().toString();
			int dot = fn.lastIndexOf('.');
			baseStem = (dot > 0) ? fn.substring(0, dot) : fn;
			Path dir = basePath.getParent() == null
					? Paths.get(".")
					: basePath.getParent();
			Path puzzles = dir.resolve(baseStem + ".puzzles.json");
			Path nonpuzzle = dir.resolve(baseStem + ".nonpuzzles.json");
			return new OutputTargets(puzzles, nonpuzzle);
		} else {
			String tag = chess960 ? "chess960" : "standard";
			String ts = String.valueOf(System.currentTimeMillis());
			Path dir = basePath;
			Path puzzles = dir.resolve(tag + "-" + ts + ".puzzles.json");
			Path nonpuzzle = dir.resolve(tag + "-" + ts + ".nonpuzzles.json");
			return new OutputTargets(puzzles, nonpuzzle);
		}
	}

	/**
	 * Used for loading seed records from a supported input file.
	 *
	 * @param input path to a {@code .txt} or {@code .pgn} file
	 * @return list of parsed records (position + optional parent)
	 * @throws java.io.IOException when the input is unsupported or unreadable
	 */
	private static List<Record> loadRecordsFromInput(Path input) throws java.io.IOException {
		String name = input.getFileName().toString().toLowerCase();

		if (name.endsWith(".txt")) {
			return Reader.readPositionRecords(input);
		}

		if (name.endsWith(".pgn")) {
			return loadRecordsFromPgn(input);
		}

		throw new IOException("Unsupported input file (expect .txt or .pgn): " + input);
	}

	/**
	 * Reads a PGN file and extracts all mainline positions (after each ply) for
	 * every game.
	 *
	 * @param input PGN file
	 * @return list of records parsed from PGN movetext (variations preserved)
	 * @throws IOException if reading fails
	 */
	private static List<Record> loadRecordsFromPgn(Path input) throws IOException {
		List<chess.model.Game> games = Pgn.read(input);
		List<Record> positions = new ArrayList<>();
		for (chess.model.Game g : games) {
			positions.addAll(extractRecordsWithVariations(g));
		}
		return positions;
	}

	private static List<Record> extractRecordsWithVariations(chess.model.Game game) {
		List<Record> positions = new ArrayList<>();
		Position start = game.getStartPosition() != null
				? game.getStartPosition().copyOf()
				: new Position(chess.model.Game.STANDARD_START_FEN);

		record Work(chess.model.Game.Node node, Position pos) {
		}

		java.util.ArrayDeque<Work> stack = new java.util.ArrayDeque<>();
		if (game.getMainline() != null) {
			stack.push(new Work(game.getMainline(), start.copyOf()));
		}
		for (chess.model.Game.Node rootVar : game.getRootVariations()) {
			stack.push(new Work(rootVar, start.copyOf()));
		}

		while (!stack.isEmpty()) {
			Work work = stack.pop();
			Position current = work.pos();
			chess.model.Game.Node cur = work.node();
			while (cur != null) {
				Position parent = current.copyOf();
				Position child;
				try {
					short move = SAN.fromAlgebraic(parent, cur.getSan());
					child = parent.copyOf().play(move);
				} catch (IllegalArgumentException ex) {
					break; // stop this line on illegal SAN
				}
				positions.add(new Record().withParent(parent).withPosition(child.copyOf()));
				for (chess.model.Game.Node variation : cur.getVariations()) {
					stack.push(new Work(variation, child.copyOf()));
				}
				current = child;
				cur = cur.getNext();
			}
		}
		return positions;
	}

	/**
	 * Used for writing records as JSON Lines to the target path (touching the file
	 * when empty).
	 *
	 * @param target  output path
	 * @param records puzzle or non-puzzle records to persist
	 * @throws IOException when writing fails
	 */
	private static void flushJsonLines(Path target, List<Record> records) throws IOException {
		if (records.isEmpty()) {
			ensureParentDir(target);
			// still touch file so downstream tooling can find it
			Files.createDirectories(target.getParent() == null ? Paths.get(".") : target.getParent());
			if (!Files.exists(target))
				Files.createFile(target);
			return;
		}
		// Convert records to JSON strings (assumes Record::toJson exists; otherwise
		// adapt here).
		List<String> jsons = new ArrayList<>(records.size());
		for (Record r : records) {
			jsons.add(r.toJson());
		}
		ensureParentDir(target);
		Writer.appendJsonObjects(target, jsons);
	}

	/**
	 * Used for ensuring the parent directory of a target path exists.
	 *
	 * @param p path whose parent should be created if missing
	 * @throws IOException when directory creation fails
	 */
	private static void ensureParentDir(Path p) throws IOException {
		Path parent = p.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	/**
	 * Parses a Filter-DSL string or returns a default Filter when the CLI value is
	 * null.
	 * 
	 * @param cliValue CLI-provided Filter-DSL string; may be null
	 * @param def      supplier of the default Filter to use when {@code cliValue}
	 *                 is absent
	 * @return the parsed Filter or the default value
	 */
	private static Filter filterOrDefault(String cliValue, Supplier<Filter> def) {
		if (cliValue == null)
			return def.get();
		try {
			return FilterDSL.fromString(cliValue);
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException("Invalid filter expression: " + cliValue, ex);
		}
	}
}
