package dev.cushionscreen.screen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * The shared, server-controlled timeline. While playing, the position at media-clock time {@code t} is
 * {@code positionMs + (t - anchorClockMs)}; every client evaluates that against its synchronised copy of the
 * server's media clock, so nobody streams positions continuously.
 */
public record Playback(Status status, long positionMs, long anchorClockMs, boolean loop) {
    public enum Status { STOPPED, PAUSED, PLAYING }

    public static final Playback STOPPED = new Playback(Status.STOPPED, 0, 0, false);

    public static final Codec<Playback> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.xmap(Status::valueOf, Status::name).fieldOf("status").forGetter(Playback::status),
        Codec.LONG.fieldOf("position").forGetter(Playback::positionMs),
        Codec.BOOL.optionalFieldOf("loop", false).forGetter(Playback::loop)
    ).apply(i, (s, p, l) -> new Playback(s == Status.PLAYING ? Status.PAUSED : s, p, 0, l)));
    // A world that is reloaded resumes paused: the media clock restarts with the server.

    public static final StreamCodec<ByteBuf, Playback> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeEnum(p.status);
            b.writeVarLong(p.positionMs);
            b.writeLong(p.anchorClockMs);
            b.writeBoolean(p.loop);
        },
        buf -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            return new Playback(b.readEnum(Status.class), b.readVarLong(), b.readLong(), b.readBoolean());
        });

    /** Position in the media at the given media-clock time, wrapped for loops and clamped to the duration. */
    public long positionAt(long clockMs, long durationMs) {
        long pos = status == Status.PLAYING ? positionMs + Math.max(0, clockMs - anchorClockMs) : positionMs;
        if (durationMs > 0) {
            if (loop && status == Status.PLAYING) pos = Math.floorMod(pos, durationMs);
            else pos = Math.min(pos, durationMs);
        }
        return Math.max(0, pos);
    }

    public boolean hasEnded(long clockMs, long durationMs) {
        return status == Status.PLAYING && !loop && durationMs > 0
            && positionMs + (clockMs - anchorClockMs) >= durationMs;
    }

    public Playback play(long clockMs, long durationMs) {
        long start = positionAt(clockMs, durationMs);
        if (durationMs > 0 && start >= durationMs) start = 0; // pressing play at the end restarts
        return new Playback(Status.PLAYING, start, clockMs, loop);
    }

    public Playback pause(long clockMs, long durationMs) {
        return new Playback(Status.PAUSED, positionAt(clockMs, durationMs), clockMs, loop);
    }

    public Playback stop() {
        return new Playback(Status.STOPPED, 0, 0, loop);
    }

    public Playback seek(long clockMs, long targetMs, long durationMs) {
        long clamped = Math.max(0, durationMs > 0 ? Math.min(targetMs, durationMs) : targetMs);
        Status s = status == Status.STOPPED ? Status.PAUSED : status;
        return new Playback(s, clamped, clockMs, loop);
    }

    public Playback withLoop(boolean newLoop, long clockMs, long durationMs) {
        // re-anchor so a wrapped looping position stays where it is
        return new Playback(status, positionAt(clockMs, durationMs), clockMs, newLoop);
    }
}
