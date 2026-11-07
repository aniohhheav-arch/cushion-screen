package dev.cushionscreen.gametest;

import dev.cushionscreen.block.CushionScreenBlockEntity;
import dev.cushionscreen.client.ClientScreens;
import dev.cushionscreen.client.gui.FullscreenVideoScreen;
import dev.cushionscreen.client.gui.ScreenControlScreen;
import dev.cushionscreen.client.media.MediaProbe;
import dev.cushionscreen.client.media.PlayerManager;
import dev.cushionscreen.client.media.VideoLibrary;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.Playback;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.server.ScreenRegistry;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Plays a real video on a real 16x9 Cushion Screen wall in a singleplayer world and checks the picture in
 * screenshots. The test clip is four one-second colours (red, green, blue, white).
 */
public final class CushionScreenClientGameTest implements FabricClientGameTest {
    /** Bottom-left of the wall as seen by the player: the wall faces north, so "right" is west. */
    private static final BlockPos ORIGIN = new BlockPos(8, -60, 12);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            server.runCommand("time set noon");
            // 16 wide x 9 high, facing north (front towards -z): x from 8 down to -7, y -60..-52
            server.runCommand("fill -7 -60 12 8 -52 12 cushionscreen:cushion_screen[facing=north]");
            server.runCommand("fill -8 -61 13 9 -51 13 cushionscreen:screen_frame");
            server.runCommand("tp @p 0.5 -56 -4 0 0"); // facing south, looking at the wall
            sp.getConnection().waitForChunksRender();
            ctx.waitTicks(30);

            // one screen, one id, for all 144 blocks
            UUID id = server.computeOnServer(s -> {
                var level = s.overworld();
                UUID first = null;
                for (int x = -7; x <= 8; x++) {
                    for (int y = -60; y <= -52; y++) {
                        if (!(level.getBlockEntity(new BlockPos(x, y, 12)) instanceof CushionScreenBlockEntity be)) {
                            throw new AssertionError("missing screen block at " + x + "," + y);
                        }
                        if (first == null) first = be.screenId();
                        if (be.screenId() == null || !be.screenId().equals(first)) throw new AssertionError("wall did not merge at " + x + "," + y);
                    }
                }
                return first;
            });
            ctx.waitFor(mc -> ClientScreens.get(id) != null, 100);
            ctx.waitFor(mc -> ClientScreens.shapeOf(id) != null && ClientScreens.shapeOf(id).complete(), 100);
            check(ClientScreens.shapeOf(id).width() == 16 && ClientScreens.shapeOf(id).height() == 9, "client sees a 16x9 screen");
            check(ClientScreens.shapeOf(id).origin().equals(ORIGIN), "origin " + ClientScreens.shapeOf(id).origin());
            Path idle = ctx.takeScreenshot("cushionscreen_1_no_signal");
            int[] idleCenter = centre(idle);
            check(idleCenter[2] > idleCenter[0], "no-signal screen is a dark blue gradient, got " + rgb(idleCenter));

            // ---- load the video exactly as the menu does: probe locally, remember the file, send only its description
            Path video = copyResource("/videos/colors.mp4");
            MediaInfo info;
            try {
                info = MediaProbe.probe(video);
            } catch (Exception e) {
                throw new AssertionError("probe failed", e);
            }
            ctx.runOnClient(mc -> {
                VideoLibrary.remember(info.fingerprint(), video);
                Platform.INSTANCE.sendToServer(new Payloads.LoadMedia(ORIGIN, info, false));
            });
            ctx.waitFor(mc -> ClientScreens.get(id).media().isPresent(), 100);
            // seek into the blue second and hold there
            ctx.runOnClient(mc -> Platform.INSTANCE.sendToServer(new Payloads.ScreenAction(ORIGIN, Payloads.Action.SEEK, 2500)));
            ctx.waitFor(mc -> ClientScreens.get(id).playback().positionMs() == 2500, 100);
            ctx.waitFor(mc -> PlayerManager.status(id) == PlayerManager.Status.PLAYING, 400);
            ctx.waitTicks(25); // fade-in
            int[] blue = centre(ctx.takeScreenshot("cushionscreen_2_paused_blue"));
            check(blue[2] > 150 && blue[0] < 90 && blue[1] < 90, "paused at 2.5 s shows blue, got " + rgb(blue));

            // close up and at a steep angle the whole picture must still show (it used to lose one triangle)
            server.runCommand("tp @p 5 -57.6 9.2 56 0");
            ctx.waitTicks(10);
            Path angled = ctx.takeScreenshot("cushionscreen_2b_angled_close");
            for (int dx : new int[]{-330, -220, -110, 0, 100}) {
                int[] c = sample(angled, dx, -40);
                check(c[2] > 140 && c[0] < 100, "angled view shows the picture at x offset " + dx + ", got " + rgb(c));
            }
            server.runCommand("tp @p 0.5 -56 -4 0 0");
            ctx.waitTicks(10);

            // the block lights up while it shows a video
            boolean lit = server.computeOnServer(s -> s.overworld().getBlockState(ORIGIN).getValue(dev.cushionscreen.block.CushionScreenBlock.LIT));
            check(lit, "screen emits light");

            // ---- seek back to green and play: after about a second we are into blue, and the timeline is running
            ctx.runOnClient(mc -> Platform.INSTANCE.sendToServer(new Payloads.ScreenAction(ORIGIN, Payloads.Action.SEEK, 1100)));
            ctx.waitFor(mc -> ClientScreens.get(id).playback().positionMs() == 1100, 100);
            ctx.waitTicks(10);
            int[] green = centre(ctx.takeScreenshot("cushionscreen_3_paused_green"));
            check(green[1] > 150 && green[0] < 90 && green[2] < 90, "paused at 1.1 s shows green, got " + rgb(green));
            ctx.runOnClient(mc -> Platform.INSTANCE.sendToServer(new Payloads.ScreenAction(ORIGIN, Payloads.Action.PLAY, 0)));
            ctx.waitFor(mc -> ClientScreens.get(id).playback().status() == Playback.Status.PLAYING, 100);
            check(ctx.computeOnClient(mc -> dev.cushionscreen.client.ClientClock.isSynced()), "client clock synchronised with the server");
            // game-test ticks are not real time; wait until the shared timeline itself is inside the blue second
            ctx.waitFor(mc -> position(id) >= 2350, 600);
            ctx.waitTicks(2);
            int[] playing = centre(ctx.takeScreenshot("cushionscreen_4_playing"));
            check(playing[2] > 150 && playing[0] < 90, "playing advanced into blue, got " + rgb(playing));

            // ---- the end of a non-looping video holds the last frame (white) and pauses on the server
            ctx.waitFor(mc -> ClientScreens.get(id).playback().status() == Playback.Status.PAUSED, 200);
            ctx.waitTicks(10);
            int[] end = centre(ctx.takeScreenshot("cushionscreen_5_ended_white"));
            check(end[0] > 180 && end[1] > 180 && end[2] > 180, "ended on the white frame, got " + rgb(end));

            // ---- the menu and the fullscreen view
            ctx.setScreen(() -> new ScreenControlScreen(ORIGIN));
            ctx.waitTicks(10);
            ctx.takeScreenshot("cushionscreen_6_menu");
            ctx.setScreen(() -> new FullscreenVideoScreen(null, ORIGIN, id));
            ctx.waitTicks(10);
            int[] full = centre(ctx.takeScreenshot("cushionscreen_7_fullscreen"));
            check(full[0] > 180 && full[1] > 180 && full[2] > 180, "fullscreen shows the frame, got " + rgb(full));
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);

            // ---- turning it off goes dark and stops decoding
            ctx.runOnClient(mc -> {
                ScreenData d = ClientScreens.get(id);
                Platform.INSTANCE.sendToServer(new Payloads.Configure(ORIGIN, "Test Cinema", d.permission(), false, d.brightness(),
                    d.audioEnabled(), d.volume(), d.rotation()));
            });
            ctx.waitFor(mc -> !ClientScreens.get(id).powered(), 100);
            ctx.waitTicks(80); // power-off animation, then the decoder's grace period
            check(ctx.computeOnClient(mc -> PlayerManager.player(id)) == null, "player disposed when the screen is off");
            int[] off = centre(ctx.takeScreenshot("cushionscreen_8_off"));
            check(off[0] < 60 && off[1] < 60 && off[2] < 70, "switched-off screen is dark glass, got " + rgb(off));
            check("Test Cinema".equals(ClientScreens.get(id).name()), "settings saved");

            // ---- break a column out of the middle: the wall splits into two screens, the bigger one keeps the video
            server.runCommand("fill 3 -60 12 3 -52 12 air");
            ctx.waitTicks(5);
            UUID[] parts = server.computeOnServer(s -> {
                var level = s.overworld();
                return new UUID[]{
                    ((CushionScreenBlockEntity) level.getBlockEntity(new BlockPos(8, -60, 12))).screenId(),
                    ((CushionScreenBlockEntity) level.getBlockEntity(new BlockPos(2, -60, 12))).screenId()};
            });
            check(!parts[0].equals(parts[1]), "the two halves are different screens");
            boolean keptVideo = server.computeOnServer(s -> {
                ScreenRegistry reg = ScreenRegistry.get(s.overworld());
                return reg.screen(parts[1]).orElseThrow().media().isPresent() && reg.screen(parts[0]).orElseThrow().media().isEmpty();
            });
            check(keptVideo, "the larger half (10 columns) kept the video, the smaller (5) starts empty");
            ctx.waitTicks(10);
            ctx.takeScreenshot("cushionscreen_9_split");
        }
    }

    private static long position(UUID id) {
        ScreenData d = ClientScreens.get(id);
        return d.playback().positionAt(dev.cushionscreen.client.ClientClock.nowMs(), d.durationMs());
    }

    private static Path copyResource(String name) {
        try (InputStream in = CushionScreenClientGameTest.class.getResourceAsStream(name)) {
            if (in == null) throw new AssertionError("missing test resource " + name);
            Path dir = Files.createTempDirectory("cushionscreen-test");
            Path out = dir.resolve(Path.of(name).getFileName().toString());
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Average colour of a small patch in the middle of a screenshot (where the screen is). */
    private static int[] centre(Path png) {
        try {
            BufferedImage img = ImageIO.read(png.toFile());
            int cx = img.getWidth() / 2 + 40, cy = img.getHeight() / 2 - 30; // beside the crosshair, still on the screen
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = cy - 6; y <= cy + 6; y++) {
                for (int x = cx - 6; x <= cx + 6; x++) {
                    int p = img.getRGB(x, y);
                    r += (p >> 16) & 255;
                    g += (p >> 8) & 255;
                    b += p & 255;
                    n++;
                }
            }
            return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Average colour of a small patch at an offset from the middle of a screenshot. */
    private static int[] sample(Path png, int dx, int dy) {
        try {
            BufferedImage img = ImageIO.read(png.toFile());
            int cx = img.getWidth() / 2 + dx, cy = img.getHeight() / 2 + dy;
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = cy - 4; y <= cy + 4; y++) {
                for (int x = cx - 4; x <= cx + 4; x++) {
                    int p = img.getRGB(x, y);
                    r += (p >> 16) & 255;
                    g += (p >> 8) & 255;
                    b += p & 255;
                    n++;
                }
            }
            return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String rgb(int[] c) {
        return c[0] + "," + c[1] + "," + c[2];
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
