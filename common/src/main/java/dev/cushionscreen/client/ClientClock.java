package dev.cushionscreen.client;

import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.platform.Platform;

/**
 * The client's estimate of the server's media clock. Pings every few seconds; the sample with the lowest
 * round-trip time among recent ones wins, offset by half that round trip (NTP-style). Between samples the estimate
 * advances with the local monotonic clock. While a singleplayer game is paused the server clock stops, so the local
 * estimate freezes too and re-synchronises on resume.
 */
public final class ClientClock {
    private static final long PING_INTERVAL_MS = 4000;
    private static final int SAMPLES = 6;

    private static final long[] offsets = new long[SAMPLES];
    private static final long[] rtts = new long[SAMPLES];
    private static int sampleCount;
    private static int nextSlot;
    private static long offsetMs;
    private static boolean synced;
    private static long lastPingMs = Long.MIN_VALUE;
    private static long frozenAt = -1;

    private ClientClock() {}

    public static long localMs() {
        return System.nanoTime() / 1_000_000L;
    }

    public static void reset() {
        sampleCount = 0;
        nextSlot = 0;
        synced = false;
        offsetMs = 0;
        lastPingMs = Long.MIN_VALUE;
        frozenAt = -1;
    }

    public static boolean isSynced() {
        return synced;
    }

    /** Estimated server media clock now. */
    public static long nowMs() {
        if (frozenAt >= 0) return frozenAt;
        return localMs() + offsetMs;
    }

    public static void tick(boolean gamePaused) {
        if (gamePaused) {
            if (frozenAt < 0) frozenAt = localMs() + offsetMs;
            return;
        }
        if (frozenAt >= 0) {
            frozenAt = -1;
            sampleCount = 0; // the server clock stood still too; take fresh samples
            lastPingMs = Long.MIN_VALUE;
        }
        long now = localMs();
        long interval = sampleCount < 3 ? 400 : PING_INTERVAL_MS;
        // (Long.MIN_VALUE marks "never pinged": the subtraction would overflow, so test for it first)
        if (lastPingMs == Long.MIN_VALUE || now - lastPingMs >= interval) {
            lastPingMs = now;
            Platform.INSTANCE.sendToServer(new Payloads.ClockPing(now));
        }
    }

    public static void onPong(Payloads.ClockPong pong) {
        long now = localMs();
        long rtt = Math.max(0, now - pong.clientTimeMs());
        if (rtt > 5000) return;
        offsets[nextSlot] = pong.serverClockMs() + rtt / 2 - now;
        rtts[nextSlot] = rtt;
        nextSlot = (nextSlot + 1) % SAMPLES;
        sampleCount = Math.min(SAMPLES, sampleCount + 1);
        int best = 0;
        for (int i = 1; i < sampleCount; i++) if (rtts[i] < rtts[best]) best = i;
        long target = offsets[best];
        // Snap on first sync or big jumps; otherwise slew gently so playback never visibly jumps.
        if (!synced || Math.abs(target - offsetMs) > 250) offsetMs = target;
        else offsetMs += (target - offsetMs) / 4;
        synced = true;
    }
}
