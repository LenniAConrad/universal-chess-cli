package application.console;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used for drawing a textual progress bar with ETA information.
 *
 * <p>
 * Designed for multi-threaded producers that call {@link #step()} whenever a
 * unit of work completes. All rendering is serialized so concurrent bars do not
 * interleave their output.
 * </p>
 * 
 * @since 2025
 * @author Lennart A. Conrad
 */
public final class Bar {

	/** Used for serializing console writes so different bars do not interleave. */
	private static final Object RENDER_LOCK = new Object();

	/** Used for controlling how many characters make up the visual bar. */
	private static final int BAR_WIDTH = 64;

	/** Used for tracking the total units of work being monitored. */
	private final int total;

	/** Used for counting the number of completed units. */
	private final AtomicInteger completed = new AtomicInteger();

	/** Used for tracking the length of the last rendered line for cleanup. */
	private int lastRenderLength = 0;

	/** Used for capturing the start timestamp for elapsed/ETA calculations. */
	private final long startNs;

	/**
	 * @param total total amount of work to track; when zero or negative, rendering
	 *              stays disabled
	 */
	public Bar(int total) {
		this.total = Math.max(0, total);
		this.startNs = System.nanoTime();
		if (this.total > 0) {
			render(0);
		}
	}

	/**
	 * Used for advancing the bar by one unit and re-rendering the output.
	 */
	public void step() {
		if (total <= 0) {
			return;
		}
		int done = completed.incrementAndGet();
		render(done);
	}

	/**
	 * Used for printing a trailing newline once the bar is complete.
	 */
	public void finish() {
		if (total <= 0) {
			return;
		}
		synchronized (RENDER_LOCK) {
			System.out.println();
			lastRenderLength = 0;
		}
	}

	/**
	 * Used for re-rendering the textual progress bar with percentage, elapsed time,
	 * and ETA.
	 *
	 * @param done completed units of work
	 */
	private void render(int done) {
		final int clamped = Math.min(done, total);
		final double fraction = (total == 0) ? 0.0 : (double) clamped / total;
		final int filled = (int) Math.floor(fraction * BAR_WIDTH);
		final boolean inProgress = clamped < total;
		final int pointerIndex = Math.min(filled, BAR_WIDTH - 1);
		final StringBuilder bar = new StringBuilder(BAR_WIDTH);
		for (int i = 0; i < BAR_WIDTH; i++) {
			if (i < filled) {
				bar.append('=');
			} else if (inProgress && i == pointerIndex) {
				bar.append('>');
			} else {
				bar.append(' ');
			}
		}
		final double percent = fraction * 100.0;
		final String eta = formatDuration(computeEta(clamped));
		final String elapsed = formatDuration(computeElapsed());
		final String line = String.format("[%s] %d/%d (%.1f%%) ETA %s | Elapsed %s",
				bar,
				clamped,
				total,
				percent,
				eta,
				elapsed);
		synchronized (RENDER_LOCK) {
			System.out.print("\r");
			System.out.print(line);
			int padding = Math.max(0, lastRenderLength - line.length());
			if (padding > 0) {
				System.out.print(" ".repeat(padding));
			}
			lastRenderLength = line.length();
			System.out.flush();
		}
	}

	/**
	 * Used for computing the estimated time remaining for completion.
	 *
	 * @param done completed units
	 * @return duration until completion or {@code null} when not enough data
	 */
	private Duration computeEta(int done) {
		if (done <= 0 || done > total) {
			return null;
		}
		final long elapsedNs = Math.max(0L, System.nanoTime() - startNs);
		if (elapsedNs == 0) {
			return null;
		}
		final int remaining = total - done;
		if (remaining <= 0) {
			return Duration.ZERO;
		}
		final double meanPerTask = elapsedNs / (double) done;
		final long etaNs = (long) (meanPerTask * remaining);
		return etaNs <= 0 ? Duration.ZERO : Duration.ofNanos(etaNs);
	}

	/**
	 * Used for computing how long the bar has been running.
	 *
	 * @return elapsed duration since construction
	 */
	private Duration computeElapsed() {
		long elapsedNs = Math.max(0L, System.nanoTime() - startNs);
		return Duration.ofNanos(elapsedNs);
	}

	/**
	 * Used for formatting elapsed/ETA durations into HH:MM:SS (or MM:SS) strings.
	 *
	 * @param duration duration to format; {@code null} returns {@code "--:--"}
	 * @return formatted duration string
	 */
	private static String formatDuration(Duration duration) {
		if (duration == null) {
			return "--:--";
		}
		long seconds = Math.max(0, duration.getSeconds());
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long secs = seconds % 60;
		if (hours > 0) {
			return String.format("%d:%02d:%02d", hours, minutes, secs);
		}
		return String.format("%02d:%02d", minutes, secs);
	}
}
