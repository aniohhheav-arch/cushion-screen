package dev.cushionscreen.client.gui;

import dev.cushionscreen.client.ClientClock;
import dev.cushionscreen.client.ClientScreens;
import dev.cushionscreen.client.media.PlayerManager;
import dev.cushionscreen.client.media.ScreenPlayer;
import dev.cushionscreen.client.render.ScreenBlockRenderer;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.screen.Playback;
import dev.cushionscreen.screen.ScreenData;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The same synchronised playback, filling the game window. Sound switches to direct stereo while it is open.
 * Space: play/pause, arrows: seek 10 s, Esc: back.
 */
public final class FullscreenVideoScreen extends Screen {
    private final Screen parent;
    private final BlockPos pos;
    private final UUID screenId;
    private final long openedAt = System.currentTimeMillis();
    private long lastMouseMove = System.currentTimeMillis();
    private double lastMouseX, lastMouseY;

    public FullscreenVideoScreen(Screen parent, BlockPos pos, UUID screenId) {
        super(Component.translatable("cushionscreen.gui.fullscreen"));
        this.parent = parent;
        this.pos = pos;
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        PlayerManager.setFullscreen(screenId);
    }

    @Override
    public void removed() {
        PlayerManager.setFullscreen(null);
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, 0xFF000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            lastMouseMove = System.currentTimeMillis();
        }
        ScreenData d = ClientScreens.get(screenId);
        ScreenPlayer player = PlayerManager.player(screenId);
        float fadeIn = Math.min(1f, (System.currentTimeMillis() - openedAt) / 300f);
        if (player != null && player.texture() != null && player.state() == ScreenPlayer.State.READY) {
            int tw = player.texture().width(), th = player.texture().height();
            float aspect = d != null && d.media().isPresent() ? d.media().get().width() / (float) d.media().get().height() : tw / (float) th;
            int vw = width, vh = Math.round(width / aspect);
            if (vh > height) {
                vh = height;
                vw = Math.round(height * aspect);
            }
            int x = (width - vw) / 2, y = (height - vh) / 2;
            int alpha = (int) (fadeIn * 255) << 24;
            g.blit(RenderPipelines.GUI_TEXTURED, player.texture().id(), x, y, 0, 0, vw, vh, vw, vh, vw, vh, alpha | 0xFFFFFF);
        } else {
            Component msg = Component.translatable("cushionscreen.status." + PlayerManager.status(screenId).name().toLowerCase(java.util.Locale.ROOT));
            g.centeredText(font, msg, width / 2, height / 2 - 4, 0xFF8C96A8);
        }

        // controls overlay, shown while the mouse moves
        long idle = System.currentTimeMillis() - lastMouseMove;
        float overlay = idle < 2500 ? 1f : Math.max(0f, 1f - (idle - 2500) / 400f);
        if (overlay > 0 && d != null) {
            int oa = (int) (overlay * 0xB0);
            g.fillGradient(0, height - 44, width, height, 0, oa << 24);
            long dur = d.durationMs();
            long pos = d.playback().positionAt(ClientClock.nowMs(), dur);
            int barY = height - 26, x0 = 16, x1 = width - 16;
            int ta = (int) (overlay * 255) << 24;
            g.fill(x0, barY, x1, barY + 3, (int) (overlay * 0x55) << 24 | 0xFFFFFF);
            if (dur > 0) g.fill(x0, barY, x0 + (int) ((x1 - x0) * Math.min(1.0, pos / (double) dur)), barY + 3, ta | 0xF0584A);
            boolean playing = d.playback().status() == Playback.Status.PLAYING;
            String left = (playing ? "❚❚  " : "▶  ") + ScreenBlockRenderer.formatTime(pos) + " / " + ScreenBlockRenderer.formatTime(dur);
            g.text(font, Component.literal(left), x0, height - 16, ta | 0xFFFFFF);
            Component name = Component.literal(d.media().map(m -> m.fileName()).orElse(d.displayName()));
            g.text(font, name, x1 - font.width(name), height - 16, ta | 0xB8C0CE);
            Component hint = Component.translatable("cushionscreen.gui.fullscreen_hint");
            g.text(font, hint, width - 8 - font.width(hint), 8, ((int) (overlay * 0x90) << 24) | 0xFFFFFF);
        }
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ScreenData d = ClientScreens.get(screenId);
        lastMouseMove = System.currentTimeMillis();
        if (d != null && d.media().isPresent()) {
            long pos = d.playback().positionAt(ClientClock.nowMs(), d.durationMs());
            switch (event.key()) {
                case 32 -> { // space
                    ScreenActions.act(this.pos, d.playback().status() == Playback.Status.PLAYING ? Payloads.Action.PAUSE : Payloads.Action.PLAY, 0);
                    return true;
                }
                case 262 -> { // right arrow
                    ScreenActions.act(this.pos, Payloads.Action.SEEK, pos + 10_000);
                    return true;
                }
                case 263 -> { // left arrow
                    ScreenActions.act(this.pos, Payloads.Action.SEEK, Math.max(0, pos - 10_000));
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        ScreenData d = ClientScreens.get(screenId);
        if (d != null && d.durationMs() > 0 && event.y() >= height - 32 && event.y() <= height - 18) {
            double f = Math.max(0, Math.min(1, (event.x() - 16) / (width - 32)));
            ScreenActions.act(pos, Payloads.Action.SEEK, Math.round(f * d.durationMs()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}
