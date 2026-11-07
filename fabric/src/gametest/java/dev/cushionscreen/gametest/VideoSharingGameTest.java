package dev.cushionscreen.gametest;

import dev.cushionscreen.block.CushionScreenBlockEntity;
import dev.cushionscreen.client.ClientScreens;
import dev.cushionscreen.client.media.ClientShare;
import dev.cushionscreen.client.media.MediaProbe;
import dev.cushionscreen.client.media.PlayerManager;
import dev.cushionscreen.client.media.VideoLibrary;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.server.MediaShare;
import dev.cushionscreen.share.VideoFingerprint;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Video sharing end to end, with real packets through the integrated server. One client plays both parts: it
 * loads a 6 MB video and uploads it when the server asks; then it forgets and deletes its copy, and must get the
 * video back from the server by itself, byte for byte, and play it.
 */
public final class VideoSharingGameTest implements FabricClientGameTest {
    private static final BlockPos ORIGIN = new BlockPos(3, -60, 10);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            server.runCommand("time set noon");
            server.runCommand("fill -4 -60 10 3 -56 10 cushionscreen:cushion_screen[facing=north]"); // 8 x 5
            server.runCommand("tp @p -0.5 -57 1 0 0");
            sp.getConnection().waitForChunksRender();
            ctx.waitTicks(20);
            UUID id = server.computeOnServer(s -> ((CushionScreenBlockEntity) s.overworld().getBlockEntity(ORIGIN)).screenId());
            ctx.waitFor(mc -> ClientScreens.get(id) != null, 100);

            // ---- the loading player's own copy
            Path original;
            MediaInfo info;
            try (InputStream in = getClass().getResourceAsStream("/videos/share.mp4")) {
                Path dir = Files.createTempDirectory("cushionscreen-share");
                original = dir.resolve("share.mp4");
                Files.copy(in, original);
                info = MediaProbe.probe(original);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            check(info.contentHash().length() == 64, "probe computes the full content hash");
            check(info.sizeBytes() > 4L << 20, "test video is big enough to need flow control");
            ctx.runOnClient(mc -> {
                VideoLibrary.remember(info.fingerprint(), original);
                Platform.INSTANCE.sendToServer(new Payloads.LoadMedia(ORIGIN, info, true));
            });
            ctx.waitFor(mc -> ClientScreens.get(id).media().isPresent(), 100);

            // ---- upload: someone needs it, so the server asks this player (the only source) for it
            server.runOnServer(s -> MediaShare.get().debugAddWaiter(info.fingerprint(), UUID.randomUUID()));
            Path cached = server.computeOnServer(s -> MediaShare.get().cacheFolder().resolve(info.fingerprint() + ".video"));
            ctx.waitFor(mc -> Files.exists(cached), 2400);
            check(sha(cached).equals(info.contentHash()), "server's copy is byte-identical to the original");

            // ---- download: this client loses its copy and must get it back from the server on its own
            ctx.runOnClient(mc -> {
                PlayerManager.closeAll();
                VideoLibrary.forget(info.fingerprint());
            });
            try {
                Files.delete(original);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            ctx.waitFor(mc -> PlayerManager.status(id) == PlayerManager.Status.DOWNLOADING
                || PlayerManager.status(id) == PlayerManager.Status.OPENING
                || PlayerManager.status(id) == PlayerManager.Status.PLAYING, 600);
            ctx.takeScreenshot("cushionscreen_share_1_fetching");
            Path[] got = new Path[1];
            ctx.waitFor(mc -> VideoLibrary.lookup(info, got) == VideoLibrary.Lookup.FOUND, 2400);
            check(got[0].startsWith(ClientShare.downloadFolder()), "the copy came from the server: " + got[0]);
            check(sha(got[0]).equals(info.contentHash()), "downloaded copy is byte-identical");
            ctx.waitFor(mc -> PlayerManager.status(id) == PlayerManager.Status.PLAYING, 600);
            ctx.waitTicks(20);
            ctx.takeScreenshot("cushionscreen_share_2_playing_shared_copy");
        }
    }

    private static String sha(Path file) {
        try {
            return VideoFingerprint.contentHash(file);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
