package dev.cushionscreen.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.block.CushionScreenBlockEntity;
import dev.cushionscreen.client.ClientClock;
import dev.cushionscreen.client.ClientConfig;
import dev.cushionscreen.client.ClientScreens;
import dev.cushionscreen.client.media.ClientShare;
import dev.cushionscreen.client.media.PlayerManager;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.client.media.ScreenPlayer;
import dev.cushionscreen.client.media.VideoTexture;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.Playback;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenShape;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Draws a whole screen from its bottom-left block as one seamless surface, so there are no seams between blocks.
 * Every layer is a quad in the plane of the front faces, nudged forward a hair per layer.
 */
public final class ScreenBlockRenderer implements BlockEntityRenderer<CushionScreenBlockEntity, ScreenRenderState> {
    private static final Identifier WHITE = CushionScreen.id("textures/misc/white.png");
    private static final Identifier GLOW_EDGE = CushionScreen.id("textures/misc/glow_edge.png");
    private static final Identifier GLOW_CORNER = CushionScreen.id("textures/misc/glow_corner.png");
    private static final Identifier DOT = CushionScreen.id("textures/misc/dot.png");
    private static final int FULL_BRIGHT = 15728880;
    private static final float LAYER = 0.0012f;

    private final Font font;

    public ScreenBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public ScreenRenderState createRenderState() {
        return new ScreenRenderState();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true; // a big screen stays drawn when its origin block is off-screen; we cull the whole surface ourselves
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public boolean shouldRender(CushionScreenBlockEntity be, Vec3 cameraPosition) {
        ScreenShape shape = ClientScreens.shapeAt(be.getBlockPos());
        if (shape == null) return false;
        if (shape.complete() && !shape.isOrigin(be.getBlockPos())) return false;
        double max = ClientConfig.get().maxViewDistance + 32;
        if (shape.bounds().distanceToSqr(cameraPosition) > max * max) return false;
        var frustum = Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum();
        return frustum == null || frustum.isVisible(shape.bounds().inflate(1));
    }

    @Override
    public void extractRenderState(CushionScreenBlockEntity be, ScreenRenderState s, float partialTicks, Vec3 cameraPos,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, partialTicks, cameraPos, breakProgress);
        ScreenShape shape = ClientScreens.shapeAt(be.getBlockPos());
        s.mode = ScreenRenderState.Mode.SKIP;
        if (shape == null) return;
        s.facing = shape.facing();
        long now = System.currentTimeMillis();
        s.timeSeconds = (now % 3_600_000L) / 1000f;
        if (!shape.complete()) {
            s.mode = ScreenRenderState.Mode.INCOMPLETE_TILE;
            return;
        }
        if (!shape.isOrigin(be.getBlockPos())) return;
        UUID id = be.screenId();
        ScreenData d = ClientScreens.get(id);
        if (d == null) return;

        s.mode = ScreenRenderState.Mode.SCREEN;
        s.width = shape.width();
        s.height = shape.height();
        s.powered = d.powered();
        s.brightness = d.brightness();
        s.rotation = d.rotation();
        s.status = PlayerManager.status(d.id());

        long sincePower = ScreenFx.sincePowerChange(d.id(), d.powered(), now);
        s.powerAnim = d.powered() ? Math.min(1f, sincePower / 650f) : 1f;
        s.powerOffAnim = !d.powered() && sincePower < 380 ? sincePower / 380f : -1f;

        MediaInfo media = d.media().orElse(null);
        long duration = d.durationMs();
        long pos = d.playback().positionAt(ClientClock.nowMs(), duration);
        s.playing = d.playback().status() == Playback.Status.PLAYING;
        s.progress = media != null && duration > 0 ? Math.min(1f, pos / (float) duration) : -1f;

        ScreenPlayer player = PlayerManager.player(d.id());
        s.videoTexture = null;
        s.videoAlpha = 0;
        if (player != null && player.texture() != null && player.state() == ScreenPlayer.State.READY) {
            VideoTexture tex = player.texture();
            s.videoTexture = tex.id();
            s.videoAspect = tex.width() / (float) tex.height();
            if (media != null && media.height() > 0) s.videoAspect = media.width() / (float) media.height();
            s.videoAlpha = Math.min(1f, player.msSinceFirstFrame() / 450f);
            s.glowColor = player.averageColor();
        }

        s.fetchProgress = -1;
        s.title = d.displayName();
        s.subtitle = "";
        s.hint = "";
        Component title = null, sub = null, hint = null;
        switch (s.status) {
            case IDLE -> {
                title = Component.translatable("cushionscreen.screen.no_signal");
                sub = Component.literal(d.displayName());
                hint = Component.translatable("cushionscreen.screen.hint_select");
            }
            case MISSING_FILE -> {
                title = Component.translatable("cushionscreen.screen.missing");
                sub = Component.literal(media == null ? "" : media.fileName());
                ClientShare.Progress share = media == null ? null : ClientShare.progress(media.fingerprint());
                String why = share == null ? "hint_locate" : switch (share.state()) {
                    case NO_SOURCE -> "hint_no_source";
                    case DISABLED -> "hint_share_disabled";
                    case TOO_LARGE -> "hint_too_large";
                    case FAILED -> "hint_share_failed";
                    default -> "hint_locate";
                };
                hint = Component.translatable("cushionscreen.screen." + why);
            }
            case DOWNLOADING -> {
                ClientShare.Progress share = media == null ? null : ClientShare.progress(media.fingerprint());
                int percent = share == null ? 0 : Math.round(share.fraction() * 100);
                if (share != null && share.state() == Payloads.ShareState.DOWNLOADING) {
                    title = Component.translatable("cushionscreen.screen.downloading", percent);
                    hint = Component.translatable("cushionscreen.screen.download_size", share.done() >> 20, share.total() >> 20);
                } else {
                    String from = share == null || share.from().isEmpty() ? "?" : share.from();
                    title = Component.translatable("cushionscreen.screen.fetching", percent);
                    hint = Component.translatable("cushionscreen.screen.fetching_from", from);
                }
                sub = Component.literal(media == null ? "" : media.fileName());
                s.fetchProgress = share == null ? 0f : share.fraction();
            }
            case SEARCHING -> title = Component.translatable("cushionscreen.screen.searching");
            case NO_FFMPEG -> {
                title = Component.translatable("cushionscreen.screen.no_decoder");
                hint = Component.translatable("cushionscreen.screen.no_decoder_hint");
            }
            case FAILED -> {
                title = Component.translatable("cushionscreen.screen.failed");
                sub = Component.literal(player != null && player.error() != null ? player.error() : "");
            }
            case LOADING_NATIVES, OPENING -> title = Component.translatable("cushionscreen.screen.loading");
            default -> {
            }
        }
        if (title != null) s.title = title.getString();
        if (sub != null) s.subtitle = sub.getString();
        if (hint != null) s.hint = hint.getString();

        // on-screen display after any change, fading out
        long sinceChange = now - ClientScreens.changedAt(d.id());
        s.osdAlpha = media == null ? 0f : sinceChange < 2500 ? 1f : Math.max(0f, 1f - (sinceChange - 2500) / 500f);
        String icon = switch (d.playback().status()) {
            case PLAYING -> "▶";
            case PAUSED -> "❚❚";
            case STOPPED -> "■";
        };
        s.osdText = icon + "  " + formatTime(pos) + " / " + formatTime(duration);
    }

    public static String formatTime(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600, m = (total / 60) % 60, sec = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void submit(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out, CameraRenderState camera) {
        switch (s.mode) {
            case SKIP -> {
            }
            case INCOMPLETE_TILE -> drawIncompleteTile(s, poseStack, out);
            case SCREEN -> drawScreen(s, poseStack, out);
        }
    }

    /** Moves the pose into the display plane: x = right, y = up (in blocks), z = towards the viewer. */
    private static void enterPlane(PoseStack poseStack, Direction facing) {
        Direction r = ScreenShape.right(facing), u = ScreenShape.up(facing);
        float bx = 0.5f + 0.5f * facing.getStepX() - 0.5f * r.getStepX() - 0.5f * u.getStepX();
        float by = 0.5f + 0.5f * facing.getStepY() - 0.5f * r.getStepY() - 0.5f * u.getStepY();
        float bz = 0.5f + 0.5f * facing.getStepZ() - 0.5f * r.getStepZ() - 0.5f * u.getStepZ();
        Matrix4f m = new Matrix4f(
            r.getStepX(), r.getStepY(), r.getStepZ(), 0,
            u.getStepX(), u.getStepY(), u.getStepZ(), 0,
            facing.getStepX(), facing.getStepY(), facing.getStepZ(), 0,
            bx, by, bz, 1);
        poseStack.mulPose(m);
    }

    private void drawIncompleteTile(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out) {
        poseStack.pushPose();
        enterPlane(poseStack, s.facing);
        float pulse = 0.55f + 0.25f * (float) Math.sin(s.timeSeconds * 3);
        quad(out, poseStack, WHITE, 0.04f, 0.04f, 0.96f, 0.96f, LAYER, argb(0.85f, 0.10f, 0.03f, 0.03f));
        quad(out, poseStack, WHITE, 0.08f, 0.44f, 0.92f, 0.56f, LAYER * 2, argb(pulse, 0.85f, 0.25f, 0.15f));
        text(out, poseStack, Component.translatable("cushionscreen.screen.incomplete").getString(), 0.5f, 0.3f, 0.09f, 0.9f, LAYER * 3, 0xFFE0A090);
        poseStack.popPose();
    }

    private void drawScreen(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out) {
        float W = s.width, H = s.height;
        poseStack.pushPose();
        enterPlane(poseStack, s.facing);

        if (!s.powered) {
            if (s.powerOffAnim >= 0) drawPowerOff(s, poseStack, out, W, H);
            poseStack.popPose();
            return; // a switched-off screen is just its dark glass
        }

        // CRT-style power on: a bright line opens into the picture
        float p = s.powerAnim;
        float sx = p < 0.4f ? ease(p / 0.4f) : 1f;
        float sy = p < 0.4f ? 0.012f : Math.max(0.012f, ease((p - 0.4f) / 0.6f));
        float flash = p < 0.4f ? 1f : 1f - (p - 0.4f) / 0.6f;
        float cx = W / 2, cy = H / 2;
        float x0 = cx - W * sx / 2, x1 = cx + W * sx / 2, y0 = cy - H * sy / 2, y1 = cy + H * sy / 2;
        float b = s.brightness;

        // 1. panel
        boolean showVideo = s.videoTexture != null && s.videoAlpha > 0;
        if (showVideo) {
            quad(out, poseStack, WHITE, x0, y0, x1, y1, LAYER, argb(1f, 0, 0, 0));
        } else {
            gradient(out, poseStack, x0, y0, x1, y1, LAYER, argb(1f, 0.03f * b, 0.045f * b, 0.08f * b), argb(1f, 0.07f * b, 0.10f * b, 0.17f * b));
            // a slow light band sweeping down: the idle "signal search" look
            float band = 1f - ((s.timeSeconds / 4f) % 1f);
            float by0 = y0 + (y1 - y0) * band;
            float bh = (y1 - y0) * 0.08f;
            quad(out, poseStack, WHITE, x0, Math.max(y0, by0 - bh), x1, Math.min(y1, by0), LAYER * 2, argb(0.05f, 0.6f * b, 0.75f * b, 1f * b));
        }

        // 2. video, letterboxed to its own aspect ratio
        if (showVideo) {
            boolean sideways = (s.rotation & 1) == 1;
            float aspect = sideways ? 1f / s.videoAspect : s.videoAspect;
            float vw = W, vh = H;
            if (aspect > W / H) vh = W / aspect;
            else vw = H * aspect;
            float vx0 = cx - vw * sx / 2, vx1 = cx + vw * sx / 2, vy0 = cy - vh * sy / 2, vy1 = cy + vh * sy / 2;
            texturedQuad(out, poseStack, s.videoTexture, vx0, vy0, vx1, vy1, LAYER * 3, argb(s.videoAlpha, b, b, b), s.rotation);
        }

        if (p >= 1f) {
            float u = Math.min(W, H);
            if (!showVideo) drawMessages(s, poseStack, out, W, H, u);
            if (s.status == PlayerManager.Status.OPENING || s.status == PlayerManager.Status.LOADING_NATIVES
                || s.status == PlayerManager.Status.SEARCHING) {
                drawSpinner(s, poseStack, out, cx, showVideo ? cy : cy - H * 0.12f, u);
            }
            drawOsd(s, poseStack, out, W, H, u);
            if (showVideo) drawGlow(s, poseStack, out, W, H);
        }

        if (flash > 0.01f) quad(out, poseStack, WHITE, x0, y0, x1, y1, LAYER * 6, argb(flash * 0.9f, 0.9f, 0.95f, 1f));
        poseStack.popPose();
    }

    private void drawPowerOff(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out, float W, float H) {
        float q = s.powerOffAnim;
        float sy = q < 0.55f ? Math.max(0.01f, 1f - ease(q / 0.55f)) : 0.01f;
        float sx = q < 0.55f ? 1f : Math.max(0.005f, 1f - ease((q - 0.55f) / 0.45f));
        float cx = W / 2, cy = H / 2;
        quad(out, poseStack, WHITE, cx - W * sx / 2, cy - H * sy / 2, cx + W * sx / 2, cy + H * sy / 2, LAYER * 2,
            argb(1f - q * 0.6f, 0.85f, 0.9f, 1f));
    }

    private void drawMessages(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out, float W, float H, float u) {
        float b = s.brightness;
        int titleColor = color(0.86f * b, 0.9f * b, 0.96f * b);
        int subColor = color(0.55f * b, 0.62f * b, 0.72f * b);
        float titleSize = Math.min(u * 0.16f, W * 0.9f / 9f);
        if (!s.title.isEmpty()) text(out, poseStack, s.title, W / 2, H / 2 + titleSize * 0.2f, titleSize, W * 0.9f, LAYER * 4, titleColor);
        if (!s.subtitle.isEmpty()) text(out, poseStack, s.subtitle, W / 2, H / 2 - titleSize * 0.9f, titleSize * 0.5f, W * 0.9f, LAYER * 4, subColor);
        if (!s.hint.isEmpty()) text(out, poseStack, s.hint, W / 2, H * 0.12f, titleSize * 0.38f, W * 0.9f, LAYER * 4, subColor);
        if (s.fetchProgress >= 0) {
            float bw = W * 0.6f, bh = Math.max(0.03f, H * 0.025f), bx = (W - bw) / 2, by = H * 0.24f;
            quad(out, poseStack, WHITE, bx, by, bx + bw, by + bh, LAYER * 4, argb(0.25f, 1, 1, 1));
            quad(out, poseStack, WHITE, bx, by, bx + bw * s.fetchProgress, by + bh, LAYER * 5, argb(0.95f, 0.94f, 0.35f, 0.29f));
        }
    }

    private static void drawSpinner(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out, float cx, float cy, float u) {
        float r = u * 0.07f, dot = r * 0.32f;
        float phase = s.timeSeconds * 1.6f;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            float alpha = 0.15f + 0.85f * (float) Math.pow(((i / 8f - phase) % 1f + 1f) % 1f, 3);
            quad(out, poseStack, DOT, x - dot / 2, y - dot / 2, x + dot / 2, y + dot / 2, LAYER * 5, argb(alpha, 0.85f, 0.92f, 1f));
        }
    }

    private void drawOsd(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out, float W, float H, float u) {
        float pad = u * 0.04f;
        // a small dot that gently pulses while playing
        if (s.playing && s.videoTexture != null) {
            float d = u * 0.022f;
            float a = 0.35f + 0.2f * (float) Math.sin(s.timeSeconds * 2.5f);
            quad(out, poseStack, DOT, W - pad - d, H - pad - d, W - pad, H - pad, LAYER * 5, argb(a, 1f, 0.25f, 0.2f));
        }
        if (s.osdAlpha <= 0.01f) return;
        int a = (int) (s.osdAlpha * 255);
        float size = Math.min(u * 0.07f, W * 0.5f / 9f);
        float boxH = size * 1.5f;
        quad(out, poseStack, WHITE, pad, H - pad - boxH, pad + size * 11f, H - pad, LAYER * 4, argb(0.55f * s.osdAlpha, 0, 0, 0));
        textLeft(out, poseStack, s.osdText, pad + size * 0.4f, H - pad - boxH * 0.2f, size, LAYER * 5, (a << 24) | 0xF0F4FF);
        if (s.progress >= 0) {
            float barH = Math.max(0.02f, u * 0.012f);
            quad(out, poseStack, WHITE, 0, 0, W, barH, LAYER * 4, argb(0.35f * s.osdAlpha, 1, 1, 1));
            quad(out, poseStack, WHITE, 0, 0, W * s.progress, barH, LAYER * 5, argb(0.9f * s.osdAlpha, 0.95f, 0.35f, 0.3f));
        }
    }

    /** Soft light spilling around the screen's edges in the picture's average colour. */
    private static void drawGlow(ScreenRenderState s, PoseStack poseStack, SubmitNodeCollector out, float W, float H) {
        float g = 0.18f + 0.04f * Math.max(W, H);
        float alpha = 0.32f * s.videoAlpha * s.brightness;
        int c = (s.glowColor & 0xFFFFFF) | ((int) (alpha * 255) << 24);
        float z = LAYER * 0.5f;
        RenderType edge = RenderTypes.textPolygonOffset(GLOW_EDGE);
        RenderType corner = RenderTypes.textPolygonOffset(GLOW_CORNER);
        // edges: u = 0 at the screen edge (bright) to 1 outside (clear)
        out.submitCustomGeometry(poseStack, edge, (pose, vc) -> {
            v(vc, pose, W, 0, z, 0, 0, c); v(vc, pose, W + g, 0, z, 1, 0, c); v(vc, pose, W + g, H, z, 1, 1, c); v(vc, pose, W, H, z, 0, 1, c);
            v(vc, pose, -g, 0, z, 1, 0, c); v(vc, pose, 0, 0, z, 0, 0, c); v(vc, pose, 0, H, z, 0, 1, c); v(vc, pose, -g, H, z, 1, 1, c);
            v(vc, pose, 0, H, z, 0, 0, c); v(vc, pose, W, H, z, 0, 1, c); v(vc, pose, W, H + g, z, 1, 1, c); v(vc, pose, 0, H + g, z, 1, 0, c);
            v(vc, pose, 0, -g, z, 1, 0, c); v(vc, pose, W, -g, z, 1, 1, c); v(vc, pose, W, 0, z, 0, 1, c); v(vc, pose, 0, 0, z, 0, 0, c);
        });
        // corners: (0,0) at the screen corner, fading radially
        out.submitCustomGeometry(poseStack, corner, (pose, vc) -> {
            v(vc, pose, W, H, z, 0, 0, c); v(vc, pose, W + g, H, z, 1, 0, c); v(vc, pose, W + g, H + g, z, 1, 1, c); v(vc, pose, W, H + g, z, 0, 1, c);
            v(vc, pose, -g, H, z, 1, 0, c); v(vc, pose, 0, H, z, 0, 0, c); v(vc, pose, 0, H + g, z, 0, 1, c); v(vc, pose, -g, H + g, z, 1, 1, c);
            v(vc, pose, -g, -g, z, 1, 1, c); v(vc, pose, 0, -g, z, 0, 1, c); v(vc, pose, 0, 0, z, 0, 0, c); v(vc, pose, -g, 0, z, 1, 0, c);
            v(vc, pose, W, -g, z, 0, 1, c); v(vc, pose, W + g, -g, z, 1, 1, c); v(vc, pose, W + g, 0, z, 1, 0, c); v(vc, pose, W, 0, z, 0, 0, c);
        });
    }

    // ------------------------------------------------------------------ primitives

    private static void v(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v, int argb) {
        vc.addVertex(pose, x, y, z).setUv(u, v).setLight(FULL_BRIGHT).setColor(argb);
    }

    private static void quad(SubmitNodeCollector out, PoseStack poseStack, Identifier tex, float x0, float y0, float x1, float y1, float z, int argb) {
        out.submitCustomGeometry(poseStack, RenderTypes.textPolygonOffset(tex), (pose, vc) -> {
            v(vc, pose, x0, y0, z, 0, 1, argb);
            v(vc, pose, x1, y0, z, 1, 1, argb);
            v(vc, pose, x1, y1, z, 1, 0, argb);
            v(vc, pose, x0, y1, z, 0, 0, argb);
        });
    }

    private static void gradient(SubmitNodeCollector out, PoseStack poseStack, float x0, float y0, float x1, float y1, float z, int bottom, int top) {
        out.submitCustomGeometry(poseStack, RenderTypes.textPolygonOffset(WHITE), (pose, vc) -> {
            v(vc, pose, x0, y0, z, 0, 1, bottom);
            v(vc, pose, x1, y0, z, 1, 1, bottom);
            v(vc, pose, x1, y1, z, 1, 0, top);
            v(vc, pose, x0, y1, z, 0, 0, top);
        });
    }

    /** Video quad; {@code rotation} turns the picture in 90 degree steps. */
    private static void texturedQuad(SubmitNodeCollector out, PoseStack poseStack, Identifier tex, float x0, float y0, float x1, float y1,
                                     float z, int argb, int rotation) {
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}}; // bottom-left, bottom-right, top-right, top-left
        int k = rotation & 3;
        out.submitCustomGeometry(poseStack, RenderTypes.text(tex), (pose, vc) -> {
            float[] a = uv[k % 4], bb = uv[(k + 1) % 4], c = uv[(k + 2) % 4], d = uv[(k + 3) % 4];
            v(vc, pose, x0, y0, z, a[0], a[1], argb);
            v(vc, pose, x1, y0, z, bb[0], bb[1], argb);
            v(vc, pose, x1, y1, z, c[0], c[1], argb);
            v(vc, pose, x0, y1, z, d[0], d[1], argb);
        });
    }

    /** Centred text whose cap height is {@code size} blocks, shrunk to fit {@code maxWidth}. Baseline-centre at (cx, cy). */
    private void text(SubmitNodeCollector out, PoseStack poseStack, String str, float cx, float cy, float size, float maxWidth, float z, int argb) {
        FormattedCharSequence seq = Component.literal(str).getVisualOrderText();
        int w = font.width(seq);
        if (w == 0) return;
        float scale = size / 9f;
        if (w * scale > maxWidth) scale = maxWidth / w;
        poseStack.pushPose();
        poseStack.translate(cx, cy, z);
        poseStack.scale(scale, -scale, scale);
        out.submitText(poseStack, -w / 2f, -4.5f, seq, false, Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, argb, 0, 0);
        poseStack.popPose();
    }

    private void textLeft(SubmitNodeCollector out, PoseStack poseStack, String str, float x, float topY, float size, float z, int argb) {
        FormattedCharSequence seq = Component.literal(str).getVisualOrderText();
        float scale = size / 9f;
        poseStack.pushPose();
        poseStack.translate(x, topY, z);
        poseStack.scale(scale, -scale, scale);
        out.submitText(poseStack, 0, 0, seq, false, Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, argb, 0, 0);
        poseStack.popPose();
    }

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static int argb(float a, float r, float g, float b) {
        return (clamp255(a) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int color(float r, float g, float b) {
        return argb(1f, r, g, b);
    }

    private static int clamp255(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255)));
    }
}
