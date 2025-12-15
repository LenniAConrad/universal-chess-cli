package application;

import application.console.Bar;
import chess.core.Position;
import chess.debug.LogService;
import chess.mlp.Encoder;
import chess.uci.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Used for mining Stockfish "eval" outputs for a set of FENs with a pool of worker
 * processes.
 *
 * <p>
 * Each worker owns one engine process (path from protocol TOML), feeds positions,
 * runs {@code eval}, and writes a JSONL line containing the FEN, whether Chess960
 * was enabled, and the raw eval output.
 * </p>
 *
 * @since 2025
 */
public final class EvalMiner {

	private EvalMiner() {
	}

	/**
	 * Used for processing all {@code *.txt} shards in {@code inputDir}, writing one
	 * {@code .eval.jsonl} file per shard into {@code outputDir}.
	 *
	 * @param inputDir     directory with FEN shards (one FEN per line)
	 * @param outputDir    directory for eval JSONL shards
	 * @param workers      number of concurrent engine workers
	 * @param protocolPath path to engine protocol TOML (stockfish path + setup)
	 * @param asciiBar     render ASCII progress bars
	 * @param writeNpy     when true, also emit NPY tensors per shard (features: (N,781), labels: (N,65))
	 * @throws IOException when IO fails or engine setup fails up front
	 */
	public static void mine(Path inputDir, Path outputDir, int workers, String protocolPath, boolean asciiBar)
			throws IOException {
		mine(inputDir, outputDir, workers, protocolPath, asciiBar, false);
	}

	public static void mine(Path inputDir, Path outputDir, int workers, String protocolPath, boolean asciiBar,
			boolean writeNpy)
			throws IOException {
		if (workers <= 0) {
			throw new IllegalArgumentException("workers must be positive");
		}
		Files.createDirectories(outputDir);

		List<Path> shards = listFenShards(inputDir);
		if (shards.isEmpty()) {
			System.out.println("No FEN shards found under " + inputDir.toAbsolutePath());
			return;
		}

		for (Path shard : shards) {
			String stem = stripSuffix(shard.getFileName().toString(), ".txt");
			Path target = outputDir.resolve(stem + ".eval.jsonl");
			System.out.printf("Evaluating shard %s -> %s%n", shard.getFileName(), target.getFileName());
			processShard(shard, target,
					outputDir.resolve(stem + ".labels.npy"),
					outputDir.resolve(stem + ".features.npy"),
					workers, protocolPath, asciiBar, writeNpy);
		}
	}

	private static List<Path> listFenShards(Path inputDir) throws IOException {
		if (!Files.isDirectory(inputDir)) {
			return List.of();
		}
		List<Path> shards = new ArrayList<>();
		try (var stream = Files.list(inputDir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
					.sorted()
					.forEach(shards::add);
		}
		return shards;
	}

	private static void processShard(
			Path shard,
			Path target,
			Path labOut,
			Path featOut,
			int workers,
			String protocolPath,
			boolean asciiBar,
			boolean writeNpy) throws IOException {
		final long total = countLines(shard);
		final Bar bar = new Bar(total > Integer.MAX_VALUE ? 0 : (int) total, shard.getFileName().toString(), asciiBar);
		final BlockingQueue<Task> queue = new ArrayBlockingQueue<>(Math.max(4096, workers * 8));
		final ExecutorService exec = Executors.newFixedThreadPool(workers, r -> {
			Thread t = new Thread(r, "eval-worker");
			t.setDaemon(true);
			return t;
		});
		final Object writeLock = new Object();
		final LabelCollector collector = writeNpy ? new LabelCollector() : null;

		try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
				BufferedReader reader = Files.newBufferedReader(shard, StandardCharsets.UTF_8)) {

			List<Future<?>> futures = startWorkers(queue, exec, protocolPath, writer, bar, writeLock, workers,
					collector);

			String line;
			while ((line = reader.readLine()) != null) {
				String fen = line.trim();
				if (fen.isEmpty()) {
					bar.step();
					continue;
				}
				Position pos;
				try {
					pos = new Position(fen);
				} catch (Exception e) {
					bar.step();
					continue; // skip malformed FENs
				}
				if (pos.inCheck()) {
					bar.step();
					continue; // discard positions where side to move is in check
				}
				queue.put(new Task(fen, pos.isChess960()));
			}

			// poison pills
			for (int i = 0; i < workers; i++) {
				queue.put(Task.POISON);
			}

			// wait for completion
			for (Future<?> f : futures) {
				try {
					f.get();
				} catch (Exception e) {
					LogService.error(e, "Eval worker failed.");
				}
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while processing shard " + shard, ie);
		} finally {
			exec.shutdownNow();
			bar.finish();
			if (collector != null) {
				collector.writeNpy(labOut, featOut);
			}
		}
	}

	private static List<Future<?>> startWorkers(
			BlockingQueue<Task> queue,
			ExecutorService exec,
			String protocolPath,
			BufferedWriter writer,
			Bar bar,
			Object writeLock,
			int workers,
			LabelCollector collector) throws IOException {

		final String protocolToml = Files.readString(Path.of(protocolPath));
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < workers; i++) {
			futures.add(exec.submit(() -> {
				try (EvalEngine engine = new EvalEngine(new Protocol().fromToml(protocolToml))) {
					for (;;) {
						Task task = queue.take();
						if (task == Task.POISON) {
							break;
						}
						try {
							String eval = engine.evaluate(task.fen(), task.chess960());
							ParsedEval parsed = parseEval(eval);
							if (parsed == null || !parsed.complete()) {
								String rawJson = toRawJson(task.fen(), eval);
								synchronized (writeLock) {
									writer.write(rawJson);
									writer.newLine();
								}
								LogService.warn("Wrote raw eval (unparsed) for FEN: " + task.fen());
							} else {
								String json = toJsonLine(task.fen(), parsed);
								synchronized (writeLock) {
									writer.write(json);
									writer.newLine();
								}
								if (collector != null) {
									collector.tryAdd(task.fen(), parsed);
								}
							}
						} catch (Exception e) {
							LogService.error(e, "Eval failed for FEN: %s", task.fen());
							String errJson = toErrorJson(task.fen(), e.getMessage());
							synchronized (writeLock) {
								writer.write(errJson);
								writer.newLine();
							}
						} finally {
							bar.step();
						}
					}
				} catch (Exception e) {
					LogService.error(e, "Eval worker crashed during setup.");
				}
				return null;
			}));
		}
		return futures;
	}

	private static long countLines(Path shard) throws IOException {
		try (var stream = Files.lines(shard)) {
			return stream.count();
		}
	}

	private record ParsedEval(Float scalar, float[] grid64) {
		boolean complete() {
			return scalar != null && grid64 != null && grid64.length == 64;
		}
	}

	private static ParsedEval parseEval(String eval) {
		List<String> lines = splitLines(eval);

		double scalar = Double.NaN;
		float[][] matrix = null;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);

			if (Double.isNaN(scalar) && line.toLowerCase().contains("final evaluation")) {
				Double v = extractFirstDouble(line);
				if (v != null) {
					scalar = v;
				}
			}
			if (matrix == null && line.contains("NNUE derived piece values")) {
				matrix = parseNnueMatrix(lines, i + 1);
			}
			if (!Double.isNaN(scalar) && matrix != null) {
				break;
			}
		}
		if (Double.isNaN(scalar) || matrix == null) {
			return null;
		}
		float[] flat = new float[64];
		int k = 0;
		for (int r = 0; r < 8; r++) {
			for (int c = 0; c < 8; c++) {
				flat[k++] = matrix[r][c];
			}
		}
		return new ParsedEval((float) scalar, flat);
	}

	private static List<String> splitLines(String s) {
		String[] arr = s.split("\\R", -1);
		return java.util.Arrays.asList(arr);
	}

	private static float[][] parseNnueMatrix(List<String> lines, int startIdx) {
		float[][] out = new float[8][8];
		for (int r = 0; r < 8; r++) {
			java.util.Arrays.fill(out[r], 0.0f);
		}

		int rank = 0;
		for (int i = startIdx; i < lines.size() && rank < 8; i++) {
			String l = lines.get(i);
			if (!l.startsWith("|")) {
				continue;
			}

			String[] pieceCells = splitRowCells8(l);
			if (pieceCells == null) {
				continue;
			}

			int j = i + 1;
			while (j < lines.size() && !lines.get(j).startsWith("|")) {
				j++;
			}
			if (j >= lines.size()) {
				break;
			}

			String[] valueCells = splitValueCells8(lines.get(j));
			for (int file = 0; file < 8; file++) {
				String p = pieceCells[file].trim();
				if (p.isEmpty() || p.equalsIgnoreCase("k")) {
					out[rank][file] = 0.0f;
				} else {
					out[rank][file] = (float) parseCellDoubleOrZero(valueCells[file]);
				}
			}

			rank++;
			i = j;
		}
		return rank == 8 ? out : null;
	}

	private static String[] splitRowCells8(String line) {
		String[] cells = splitCells8(line);
		if (cells == null) {
			return null;
		}
		for (int i = 0; i < 8; i++) {
			String c = cells[i].trim();
			if (c.isEmpty()) {
				continue;
			}
			if (c.length() != 1) {
				return null;
			}
			char ch = c.charAt(0);
			if ("prnbqkPRNBQK".indexOf(ch) < 0) {
				return null;
			}
		}
		return cells;
	}

	private static String[] splitValueCells8(String line) {
		String[] cells = splitCells8(line);
		if (cells == null) {
			String[] z = new String[8];
			java.util.Arrays.fill(z, "");
			return z;
		}
		return cells;
	}

	private static String[] splitCells8(String line) {
		String[] raw = line.split("\\|", -1);
		if (raw.length < 10) {
			return null;
		}
		String[] cells = new String[8];
		for (int i = 0; i < 8; i++) {
			cells[i] = raw[i + 1];
		}
		return cells;
	}

	private static double parseCellDoubleOrZero(String cell) {
		String t = cell.trim();
		if (t.isEmpty()) {
			return 0.0;
		}
		try {
			return Double.parseDouble(t);
		} catch (NumberFormatException e) {
			Double v = extractFirstDouble(t);
			return v != null ? v : 0.0;
		}
	}

	private static Double extractFirstDouble(String s) {
		int n = s.length();
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if (c == '+' || c == '-' || Character.isDigit(c)) {
				int start = i;
				int j = i;
				if (s.charAt(j) == '+' || s.charAt(j) == '-') {
					j++;
				}
				boolean seenDigit = false;
				while (j < n && Character.isDigit(s.charAt(j))) {
					j++;
					seenDigit = true;
				}
				if (j < n && s.charAt(j) == '.') {
					j++;
					while (j < n && Character.isDigit(s.charAt(j))) {
						j++;
						seenDigit = true;
					}
				}
				if (!seenDigit) {
					continue;
				}
				String num = s.substring(start, j);
				try {
					return Double.parseDouble(num);
				} catch (NumberFormatException ignore) {
					// keep scanning
				}
				i = j - 1;
			}
		}
		return null;
	}

	/**
	 * Collects feature/label rows and writes NPY tensors.
	 */
	private static final class LabelCollector {
		private final List<float[]> labels = new ArrayList<>();
		private final List<float[]> features = new ArrayList<>();

		void tryAdd(String fen, ParsedEval parsed) {
			try {
				if (parsed == null || !parsed.complete()) {
					return;
				}
				Position pos = new Position(fen);
				float[] feat = Encoder.encode(pos);
				float[] lab = new float[65];
				lab[0] = parsed.scalar();
				System.arraycopy(parsed.grid64(), 0, lab, 1, 64);
				synchronized (this) {
					labels.add(lab);
					features.add(feat);
				}
			} catch (Exception ignore) {
				// skip malformed eval
			}
		}

		void writeNpy(Path labPath, Path featPath) throws IOException {
			float[] labFlat;
			float[] featFlat;
			int n;
			synchronized (this) {
				n = labels.size();
				labFlat = new float[n * 65];
				featFlat = new float[n * Encoder.FEATURE_LENGTH];
				for (int i = 0; i < n; i++) {
					System.arraycopy(labels.get(i), 0, labFlat, i * 65, 65);
					System.arraycopy(features.get(i), 0, featFlat, i * Encoder.FEATURE_LENGTH,
							Encoder.FEATURE_LENGTH);
				}
			}
			if (n == 0) {
				LogService.warn("No parsed eval rows; skipping NPY write for " + labPath.getFileName());
				return;
			}
			writeNpyFloat(labPath, labFlat, n, 65);
			writeNpyFloat(featPath, featFlat, n, Encoder.FEATURE_LENGTH);
			System.out.printf("Wrote %s (%d rows)%n", labPath.getFileName(), n);
			System.out.printf("Wrote %s (%d rows)%n", featPath.getFileName(), n);
		}
	}

	/**
	 * Write a float32 numpy array. If {@code cols < 0}, shape is (rows,); otherwise
	 * (rows, cols).
	 */
	private static void writeNpyFloat(Path path, float[] data, int rows, int cols) throws IOException {
		String shape = cols < 0 ? "(" + rows + ",)" : "(" + rows + ", " + cols + ",)";
		String header = "{'descr': '<f4', 'fortran_order': False, 'shape': " + shape + ", }";
		// Pad header to 16-byte alignment
		int headerLen = header.length() + 1; // + newline
		int pad = (16 - ((10 + headerLen) % 16)) % 16;

		byte[] magic = new byte[] { (byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0 };
		int totalHeaderLen = headerLen + pad;

		try (var out = Files.newOutputStream(path);
				var dataOut = new java.io.DataOutputStream(out)) {
			dataOut.write(magic);
			dataOut.writeShort(totalHeaderLen);
			dataOut.write(header.getBytes(StandardCharsets.US_ASCII));
			for (int i = 0; i < pad; i++) {
				dataOut.writeByte(0x20); // spaces
			}
			dataOut.writeByte(0x0A); // newline

			ByteBuffer buf = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (float v : data) {
				buf.putFloat(v);
			}
			dataOut.write(buf.array());
		}
	}

	private static String stripSuffix(String name, String suffix) {
		return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
	}

	private static String toJsonLine(String fen, ParsedEval parsed) {
		StringBuilder sb = new StringBuilder(256);
		sb.append("{\"fen\":\"").append(escapeJson(fen)).append("\",");
		sb.append("\"scalar\":").append(parsed.scalar()).append(",");
		sb.append("\"grid\":[");
		float[] g = parsed.grid64();
		for (int i = 0; i < g.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(g[i]);
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String escapeJson(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 32);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String toRawJson(String fen, String evalText) {
		return "{\"fen\":\"" + escapeJson(fen) + "\",\"raw_eval\":\"" + escapeJson(evalText) + "\"}";
	}

	private static String toErrorJson(String fen, String message) {
		String msg = message == null ? "error" : message;
		return "{\"fen\":\"" + escapeJson(fen) + "\",\"error\":\"" + escapeJson(msg) + "\"}";
	}

	/**
	 * Task holder for work queue.
	 */
	private record Task(String fen, boolean chess960) {
		static final Task POISON = new Task("", false);
	}

	/**
	 * Engine wrapper for issuing Stockfish "eval" and capturing the output.
	 */
	private static final class EvalEngine implements AutoCloseable {
		private final Process process;
		private final BufferedReader input;
		private final PrintWriter output;
		private final Protocol protocol;
		private boolean chess960Enabled = false;

		EvalEngine(Protocol protocol) throws IOException {
			this.protocol = Objects.requireNonNull(protocol);
			this.process = new ProcessBuilder(protocol.getPath())
					.redirectErrorStream(true)
					.start();
			this.input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			this.output = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
			initialize();
		}

		private void initialize() throws IOException {
			// Basic UCI handshake.
			output.println("uci");
			waitForToken("uciok", Duration.ofSeconds(10));
			if (protocol.getSetChess960() != null && !protocol.getSetChess960().isBlank()) {
				output.printf((protocol.getSetChess960()) + "%n", true);
				chess960Enabled = true;
			}
			String[] setup = protocol.getSetup();
			if (setup != null) {
				for (String cmd : setup) {
					output.println(cmd);
				}
			}
			ready();
		}

		private void ready() throws IOException {
			output.println(protocol.getIsready());
			waitForToken(protocol.getReadyok(), Duration.ofSeconds(10));
		}

		String evaluate(String fen, boolean chess960) throws IOException {
			setChess960(chess960);
			setPosition(fen);
			output.println("eval");
			output.println(protocol.getIsready());
			return readUntilReadyok();
		}

		private void setChess960(boolean chess960) throws IOException {
			if (protocol.getSetChess960() == null || protocol.getSetChess960().isBlank()) {
				return;
			}
			if (chess960Enabled == chess960) {
				return;
			}
			output.printf((protocol.getSetChess960()) + "%n", chess960);
			ready();
			chess960Enabled = chess960;
		}

		private void setPosition(String fen) {
			output.printf((protocol.getSetPosition()) + "%n", fen);
		}

		private String readUntilReadyok() throws IOException {
			final Duration timeout = Duration.ofSeconds(30);
			final long deadline = System.nanoTime() + timeout.toNanos();
			StringBuilder sb = new StringBuilder(1024);
			for (;;) {
				if (System.nanoTime() > deadline) {
					throw new IOException("Timed out waiting for readyok after eval");
				}
				if (!input.ready()) {
					try {
						Thread.sleep(2L);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted", ie);
					}
					continue;
				}

				String line = input.readLine();
				if (line == null) {
					throw new IOException("Engine terminated unexpectedly");
				}
				if (protocol.getReadyok().equalsIgnoreCase(line.trim())) {
					return sb.toString().trim();
				}
				sb.append(line).append('\n');
			}
		}

		private void waitForToken(String token, Duration timeout) throws IOException {
			final long deadline = System.nanoTime() + timeout.toNanos();
			String line;
			for (;;) {
				long now = System.nanoTime();
				if (now > deadline) {
					throw new IOException("Timed out waiting for: " + token);
				}
				if (!input.ready()) {
					try {
						Thread.sleep(2L);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted", ie);
					}
					continue;
				}
				line = input.readLine();
				if (line == null) {
					throw new IOException("Engine closed while waiting for token: " + token);
				}
				if (token.equalsIgnoreCase(line.trim())) {
					return;
				}
			}
		}

		@Override
		public void close() {
			try {
				output.println("quit");
			} catch (Exception ignore) {
				// ignored
			}
			try {
				process.waitFor(200, TimeUnit.MILLISECONDS);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
			process.destroyForcibly();
		}
	}
}
