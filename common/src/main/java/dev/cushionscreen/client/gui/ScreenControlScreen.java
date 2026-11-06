package dev.cushionscreen.client.gui;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.client.ClientClock;
import dev.cushionscreen.client.ClientScreens;
import dev.cushionscreen.client.FileDialogs;
import dev.cushionscreen.client.media.FFmpeg;
import dev.cushionscreen.client.media.MediaProbe;
import dev.cushionscreen.client.media.PlayerManager;
import dev.cushionscreen.client.media.ScreenPlayer;
import dev.cushionscreen.client.media.VideoLibrary;
import dev.cushionscreen.client.render.ScreenBlockRenderer;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.Playback;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenShape;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Right-click menu of a screen: pick a video, transport controls, volume, and the way into settings and fullscreen. */
public final class ScreenControlScreen extends Screen {
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 236;
    private static final int OPEN_ANIM_MS = 180;
    private static Path lastDirectory;

    private final BlockPos pos;
    private final long openedAt = System.currentTimeMillis();
    private @Nullable UUID screenId;
    private Component message = Component.empty();
    private boolean busy;

    private Button selectButton, locateButton, ejectButton, playButton, stopButton, loopButton, fullButton, muteButton, settingsButton;
    private ProgressBar progressBar;
    private VolumeSlider volumeSlider;
    private int left, top;

    public ScreenControlScreen(BlockPos pos) {
        super(Component.translatable("cushionscreen.gui.title"));
        this.pos = pos.immutable();
    }

    private @Nullable ScreenData data() {
        if (minecraft.level == null) return null;
        UUID id = ClientScreens.idAt(minecraft.level, pos);
        screenId = id;
        return ClientScreens.get(id);
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int x = left + 12, w = PANEL_W - 24;

        selectButton = addRenderableWidget(Button.builder(Component.translatable("cushionscreen.gui.select"), b -> selectVideo())
            .bounds(x, top + 104, 140, 20).build());
        locateButton = addRenderableWidget(Button.builder(Component.translatable("cushionscreen.gui.locate"), b -> locateFile())
            .bounds(x + 144, top + 104, 92, 20).build());
        ejectButton = addRenderableWidget(Button.builder(Component.translatable("cushionscreen.gui.eject"), b -> ScreenActions.act(pos, Payloads.Action.EJECT, 0))
            .bounds(x + 240, top + 104, w - 240, 20).build());

        progressBar = addRenderableWidget(new ProgressBar(x, top + 132, w, 10, this::currentProgress, this::seekTo));

        int bw = (w - 12) / 4;
        stopButton = addRenderableWidget(Button.builder(Component.literal("■ ").append(Component.translatable("cushionscreen.gui.stop")),
            b -> ScreenActions.act(pos, Payloads.Action.STOP, 0)).bounds(x, top + 160, bw, 20).build());
        playButton = addRenderableWidget(Button.builder(Component.empty(), b -> togglePlay()).bounds(x + bw + 4, top + 160, bw, 20).build());
        loopButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            ScreenData d = data();
            if (d != null) ScreenActions.act(pos, Payloads.Action.SET_LOOP, d.playback().loop() ? 0 : 1);
        }).bounds(x + (bw + 4) * 2, top + 160, bw, 20).build());
        fullButton = addRenderableWidget(Button.builder(Component.literal("⛶ ").append(Component.translatable("cushionscreen.gui.fullscreen")),
            b -> openFullscreen()).bounds(x + (bw + 4) * 3, top + 160, w - (bw + 4) * 3, 20).build());

        ScreenData d = data();
        volumeSlider = addRenderableWidget(new VolumeSlider(x, top + 186, 120, 20, screenId == null ? 1.0 : PlayerManager.localVolume(screenId)));
        muteButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (screenId != null) PlayerManager.setMutedLocally(screenId, !PlayerManager.isMutedLocally(screenId));
        }).bounds(x + 124, top + 186, 92, 20).build());
        settingsButton = addRenderableWidget(Button.builder(Component.translatable("cushionscreen.gui.settings"),
            b -> minecraft.gui.setScreen(new ScreenSettingsScreen(this, pos))).bounds(x + 220, top + 186, w - 220, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(left + PANEL_W / 2 - 50, top + 212, 100, 18).build());
        refreshWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(pos) instanceof dev.cushionscreen.block.CushionScreenBlockEntity)
            || minecraft.player == null || minecraft.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 64 * 64) {
            onClose(); // the screen was broken or we walked away
            return;
        }
        refreshWidgets();
    }

    private void refreshWidgets() {
        ScreenData d = data();
        boolean has = d != null && d.media().isPresent();
        PlayerManager.Status status = screenId == null ? PlayerManager.Status.IDLE : PlayerManager.status(screenId);
        selectButton.active = d != null && !busy && !FileDialogs.isOpen();
        locateButton.visible = has && (status == PlayerManager.Status.MISSING_FILE || status == PlayerManager.Status.SEARCHING);
        locateButton.active = locateButton.visible && !busy && !FileDialogs.isOpen();
        ejectButton.active = has;
        playButton.active = has;
        stopButton.active = has;
        loopButton.active = d != null;
        fullButton.active = has && status == PlayerManager.Status.PLAYING;
        progressBar.active = has && d.durationMs() > 0;
        boolean playing = d != null && d.playback().status() == Playback.Status.PLAYING;
        playButton.setMessage(playing
            ? Component.literal("❚❚ ").append(Component.translatable("cushionscreen.gui.pause"))
            : Component.literal("▶ ").append(Component.translatable("cushionscreen.gui.play")));
        boolean loop = d != null && d.playback().loop();
        loopButton.setMessage(Component.translatable(loop ? "cushionscreen.gui.loop_on" : "cushionscreen.gui.loop_off"));
        boolean muted = screenId != null && PlayerManager.isMutedLocally(screenId);
        muteButton.setMessage(Component.translatable(muted ? "cushionscreen.gui.unmute" : "cushionscreen.gui.mute"));
    }

    private double currentProgress() {
        ScreenData d = data();
        if (d == null || d.durationMs() <= 0) return 0;
        return d.playback().positionAt(ClientClock.nowMs(), d.durationMs()) / (double) d.durationMs();
    }

    private void seekTo(double fraction) {
        ScreenData d = data();
        if (d != null && d.durationMs() > 0) ScreenActions.act(pos, Payloads.Action.SEEK, Math.round(fraction * d.durationMs()));
    }

    private void togglePlay() {
        ScreenData d = data();
        if (d == null) return;
        boolean playing = d.playback().status() == Playback.Status.PLAYING;
        ScreenActions.act(pos, playing ? Payloads.Action.PAUSE : Payloads.Action.PLAY, 0);
    }

    private void openFullscreen() {
        if (screenId != null) minecraft.gui.setScreen(new FullscreenVideoScreen(this, pos, screenId));
    }

    // ---------------------------------------------------------------- picking files

    private void selectVideo() {
        if (!FFmpeg.isReady() && FFmpeg.hasFailed()) {
            message = Component.translatable("cushionscreen.gui.no_ffmpeg", FFmpeg.failure());
            return;
        }
        Path start = lastDirectory != null ? lastDirectory : VideoLibrary.dropFolder();
        FileDialogs.pickVideo(start, file -> {
            if (file == null) return;
            lastDirectory = file.getParent();
            busy = true;
            message = Component.translatable("cushionscreen.gui.analyzing", file.getFileName().toString());
            CompletableFuture.supplyAsync(() -> {
                try {
                    return MediaProbe.probe(file);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }, VideoLibrary.ioExecutor()).whenComplete((info, err) -> minecraft.execute(() -> {
                busy = false;
                if (err != null) {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    message = Component.translatable("cushionscreen.gui.cannot_play", String.valueOf(cause.getMessage()));
                    CushionScreen.LOG.warn("Rejected {}: {}", file, cause.getMessage());
                    return;
                }
                VideoLibrary.remember(info.fingerprint(), file);
                Platform.INSTANCE.sendToServer(new Payloads.LoadMedia(pos, info, true));
                message = Component.translatable("cushionscreen.gui.loaded", info.fileName());
            }));
        });
    }

    private void locateFile() {
        ScreenData d = data();
        if (d == null || d.media().isEmpty()) return;
        MediaInfo media = d.media().get();
        Path start = lastDirectory != null ? lastDirectory : VideoLibrary.dropFolder();
        FileDialogs.pickVideo(start, file -> {
            if (file == null) return;
            lastDirectory = file.getParent();
            busy = true;
            message = Component.translatable("cushionscreen.gui.checking", file.getFileName().toString());
            CompletableFuture.supplyAsync(() -> {
                try {
                    return VideoLibrary.verifyAndRemember(file, media);
                } catch (Exception e) {
                    return Optional.of("cushionscreen.locate.unreadable");
                }
            }, VideoLibrary.ioExecutor()).thenAccept(problem -> minecraft.execute(() -> {
                busy = false;
                message = problem.map(Component::translatable).orElse(Component.translatable("cushionscreen.locate.ok"));
            }));
        });
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        // keep the world visible so the in-world screen reacts behind the menu
        g.fillGradient(0, 0, width, height, 0x50000000, 0x90000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / (float) OPEN_ANIM_MS);
        float ease = 1 - (1 - t) * (1 - t);
        g.pose().pushMatrix();
        g.pose().translate(0, (1 - ease) * 12);
        drawPanel(g);
        super.extractRenderState(g, mouseX, mouseY, a);
        g.pose().popMatrix();
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        int x = left, y = top;
        g.fill(x - 1, y - 1, x + PANEL_W + 1, y + PANEL_H + 1, 0xFF2A2F3A);
        g.fillGradient(x, y, x + PANEL_W, y + PANEL_H, 0xF0161A22, 0xF00C0E13);
        ScreenData d = data();
        String name = d == null ? "?" : d.displayName();
        ScreenShape shape = screenId == null ? null : ClientScreens.shapeOf(screenId);
        String size = shape == null ? "" : shape.width() + " × " + shape.height();
        g.text(font, Component.literal(name), x + 12, y + 10, 0xFFFFFFFF);
        if (!size.isEmpty()) {
            Component sz = Component.translatable("cushionscreen.gui.blocks", size);
            g.text(font, sz, x + PANEL_W - 12 - font.width(sz), y + 10, 0xFF8C96A8);
        }

        // preview
        int pw = 112, ph = 63, px = x + 12, py = y + 26;
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF2A2F3A);
        g.fill(px, py, px + pw, py + ph, 0xFF000000);
        ScreenPlayer player = screenId == null ? null : PlayerManager.player(screenId);
        if (player != null && player.texture() != null && player.state() == ScreenPlayer.State.READY) {
            float aspect = player.texture().width() / (float) player.texture().height();
            int vw = pw, vh = Math.round(pw / aspect);
            if (vh > ph) {
                vh = ph;
                vw = Math.round(ph * aspect);
            }
            int vx = px + (pw - vw) / 2, vy = py + (ph - vh) / 2;
            g.blit(RenderPipelines.GUI_TEXTURED, player.texture().id(), vx, vy, 0, 0, vw, vh, vw, vh, vw, vh);
        } else {
            g.centeredText(font, statusLine(d), px + pw / 2, py + ph / 2 - 4, 0xFF6C7686);
        }

        // details
        int tx = px + pw + 10, ty = py + 2;
        if (d != null && d.media().isPresent()) {
            MediaInfo m = d.media().get();
            g.text(font, Component.literal(trim(m.fileName(), PANEL_W - pw - 34)), tx, ty, 0xFFE8ECF4);
            g.text(font, Component.translatable("cushionscreen.gui.resolution", m.resolutionLabel()), tx, ty + 13, 0xFF9AA4B4);
            String codec = m.codec().toUpperCase() + (m.hasAudio() ? "" : " · ") + (m.hasAudio() ? "" : Component.translatable("cushionscreen.gui.no_audio").getString());
            if (player != null && !player.decoderName().isEmpty()) codec = player.decoderName();
            g.text(font, Component.literal(codec), tx, ty + 26, 0xFF9AA4B4);
            g.text(font, statusLine(d), tx, ty + 39, statusColor());
        } else {
            g.text(font, Component.translatable("cushionscreen.gui.no_video"), tx, ty, 0xFFE8ECF4);
            g.textWithWordWrap(font, Component.translatable("cushionscreen.gui.no_video_hint"), tx, ty + 13, PANEL_W - pw - 34, 0xFF9AA4B4);
        }

        // time
        long dur = d == null ? 0 : d.durationMs();
        long now = d == null ? 0 : d.playback().positionAt(ClientClock.nowMs(), dur);
        if (progressBar.isDragging()) now = Math.round(progressBar.shownValue() * dur);
        String time = ScreenBlockRenderer.formatTime(now) + " / " + ScreenBlockRenderer.formatTime(dur);
        g.text(font, Component.literal(time), x + 12, y + 146, 0xFFB8C0CE);

        if (!message.getString().isEmpty()) {
            g.text(font, message, x + PANEL_W - 12 - font.width(message), y + 146, 0xFFFFD27A);
        }
    }

    private int statusColor() {
        PlayerManager.Status s = screenId == null ? PlayerManager.Status.IDLE : PlayerManager.status(screenId);
        return switch (s) {
            case PLAYING -> 0xFF7BE08A;
            case MISSING_FILE, FAILED, NO_FFMPEG -> 0xFFFF8070;
            default -> 0xFFFFD27A;
        };
    }

    private Component statusLine(@Nullable ScreenData d) {
        if (d == null) return Component.translatable("cushionscreen.status.unknown");
        if (!d.powered()) return Component.translatable("cushionscreen.status.off");
        if (d.media().isEmpty()) return Component.translatable("cushionscreen.status.no_video");
        PlayerManager.Status s = PlayerManager.status(d.id());
        return Component.translatable("cushionscreen.status." + s.name().toLowerCase(java.util.Locale.ROOT));
    }

    private String trim(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        while (s.length() > 1 && font.width(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 32 && getFocused() == null) { // space toggles playback
            togglePlay();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // pausing a singleplayer world would stop the shared clock
    }

    // ---------------------------------------------------------------- volume

    /** This player's own volume for the screen; nobody else hears the difference. */
    private final class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y, int w, int h, double value) {
            super(x, y, w, h, Component.empty(), value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("cushionscreen.gui.my_volume", Math.round(value * 100)));
        }

        @Override
        protected void applyValue() {
            if (screenId != null) PlayerManager.setLocalVolume(screenId, (float) value);
        }
    }
}
