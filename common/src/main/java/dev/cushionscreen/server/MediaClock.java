package dev.cushionscreen.server;

/**
 * The server's playback clock in milliseconds. It advances with real time while the server ticks and stands still
 * while it doesn't (a paused singleplayer game), so videos pause along with the world instead of skipping ahead.
 * Stalls longer than {@link #MAX_STEP_MS} count as {@link #MAX_STEP_MS}.
 */
public final class MediaClock {
    private static final long MAX_STEP_MS = 250;
    private static long accumulatedMs;
    private static long lastTickNanos = -1;

    private MediaClock() {}

    public static synchronized void reset() {
        accumulatedMs = 0;
        lastTickNanos = -1;
    }

    /** Called once per server tick. */
    public static synchronized void tick() {
        long now = System.nanoTime();
        if (lastTickNanos >= 0) {
            accumulatedMs += Math.min(MAX_STEP_MS, Math.max(0, (now - lastTickNanos) / 1_000_000));
        }
        lastTickNanos = now;
    }

    /** Current clock, interpolated between ticks; callable from the network thread. */
    public static synchronized long nowMs() {
        if (lastTickNanos < 0) return accumulatedMs;
        long since = (System.nanoTime() - lastTickNanos) / 1_000_000;
        return accumulatedMs + Math.min(MAX_STEP_MS, Math.max(0, since));
    }
}
