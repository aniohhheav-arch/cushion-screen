package dev.cushionscreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.Playback;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenPermission;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shared timeline arithmetic every client relies on, and the wire formats. */
class PlaybackTest {
    private static final long DUR = 10_000;

    @Test
    void playingAdvancesWithTheClock() {
        Playback p = Playback.STOPPED.play(1000, DUR);
        assertEquals(0, p.positionAt(1000, DUR));
        assertEquals(2500, p.positionAt(3500, DUR));
    }

    @Test
    void pauseFreezesAndResumes() {
        Playback p = Playback.STOPPED.play(0, DUR).pause(4000, DUR);
        assertEquals(4000, p.positionAt(9000, DUR));
        Playback resumed = p.play(20_000, DUR);
        assertEquals(4500, resumed.positionAt(20_500, DUR));
    }

    @Test
    void seekKeepsPlayingState() {
        Playback p = Playback.STOPPED.play(0, DUR).seek(1000, 7000, DUR);
        assertEquals(Playback.Status.PLAYING, p.status());
        assertEquals(7500, p.positionAt(1500, DUR));
        Playback clamped = p.seek(1000, 99_999, DUR);
        assertEquals(DUR, clamped.positionAt(1000, DUR));
    }

    @Test
    void seekWhileStoppedPauses() {
        Playback p = Playback.STOPPED.seek(0, 3000, DUR);
        assertEquals(Playback.Status.PAUSED, p.status());
        assertEquals(3000, p.positionAt(50_000, DUR));
    }

    @Test
    void loopingWraps() {
        Playback p = new Playback(Playback.Status.PLAYING, 0, 0, true);
        assertEquals(500, p.positionAt(10_500, DUR));
        assertFalse(p.hasEnded(25_000, DUR));
    }

    @Test
    void nonLoopingEndsAndClamps() {
        Playback p = Playback.STOPPED.play(0, DUR);
        assertEquals(DUR, p.positionAt(12_000, DUR));
        assertTrue(p.hasEnded(12_000, DUR));
        // pressing play at the end restarts from the beginning
        Playback atEnd = new Playback(Playback.Status.PAUSED, DUR, 0, false);
        assertEquals(0, atEnd.play(100, DUR).positionAt(100, DUR));
    }

    @Test
    void toggleLoopKeepsPosition() {
        Playback p = new Playback(Playback.Status.PLAYING, 0, 0, true);
        Playback off = p.withLoop(false, 13_000, DUR); // looped position is 3000
        assertEquals(3000, off.positionAt(13_000, DUR));
        assertEquals(3500, off.positionAt(13_500, DUR));
    }

    @Test
    void screenDataRoundTripsOverTheWire() {
        MediaInfo media = new MediaInfo("a".repeat(64), "movie night.mp4", 123_456_789L, 5_400_000L, 1920, 1080, 23.976, true, "h264", "");
        ScreenData d = ScreenData.create(UUID.randomUUID(), UUID.randomUUID(), "Steve")
            .withSettings("Cinema", ScreenPermission.OWNER, true, 0.7f, true, 0.5f, 1)
            .withMedia(Optional.of(media), Playback.STOPPED.play(42, media.durationMs()));
        ByteBuf buf = Unpooled.buffer();
        ScreenData.STREAM_CODEC.encode(buf, d);
        assertEquals(d, ScreenData.STREAM_CODEC.decode(buf));
    }

    @Test
    void transferPiecesFitMinecraftsPacketLimits() {
        String fp = "0123456789abcdef".repeat(4);
        io.netty.buffer.ByteBuf up = io.netty.buffer.Unpooled.buffer();
        dev.cushionscreen.network.Payloads.UploadPiece.CODEC.encode(
            new net.minecraft.network.RegistryFriendlyByteBuf(up, net.minecraft.core.RegistryAccess.EMPTY),
            new dev.cushionscreen.network.Payloads.UploadPiece(fp, Long.MAX_VALUE / 2, new byte[dev.cushionscreen.network.Payloads.UPLOAD_PIECE]));
        // the channel id travels with it; serverbound custom payloads may be at most 32767 bytes
        assertTrue(up.readableBytes() + 64 < 32767, "upload piece is " + up.readableBytes() + " bytes");
        io.netty.buffer.ByteBuf down = io.netty.buffer.Unpooled.buffer();
        dev.cushionscreen.network.Payloads.DownloadPiece.CODEC.encode(
            new net.minecraft.network.RegistryFriendlyByteBuf(down, net.minecraft.core.RegistryAccess.EMPTY),
            new dev.cushionscreen.network.Payloads.DownloadPiece(fp, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, new byte[dev.cushionscreen.network.Payloads.DOWNLOAD_PIECE]));
        assertTrue(down.readableBytes() + 64 < 1048576, "download piece is " + down.readableBytes() + " bytes");
    }

    @Test
    void mediaValidationRejectsHostileInput() {
        String fp = "0123456789abcdef".repeat(4);
        assertTrue(new MediaInfo(fp, "a.mp4", 10, 1000, 640, 360, 30, true, "h264", "").isValid());
        assertFalse(new MediaInfo(fp, "../../etc/passwd", 10, 1000, 640, 360, 30, true, "h264", "").isValid());
        assertFalse(new MediaInfo(fp, "C:\\videos\\a.mp4", 10, 1000, 640, 360, 30, true, "h264", "").isValid());
        assertFalse(new MediaInfo("XYZ" + fp.substring(3), "a.mp4", 10, 1000, 640, 360, 30, true, "h264", "").isValid());
        assertFalse(new MediaInfo(fp, "a.mp4", 10, 1000, 0, 360, 30, true, "h264", "").isValid());
        assertFalse(new MediaInfo(fp, "a.mp4", 10, -5, 640, 360, 30, true, "h264", "").isValid());
        assertFalse(new MediaInfo(fp, "\u00a7kobfuscated.mp4", 10, 1000, 640, 360, 30, true, "h264", "").isValid());
        assertFalse(new MediaInfo(fp, "line\nbreak.mp4", 10, 1000, 640, 360, 30, true, "h264", "").isValid());
    }
}
