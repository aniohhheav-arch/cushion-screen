package dev.cushionscreen.client.render;

import dev.cushionscreen.client.media.PlayerManager;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/** Snapshot of everything needed to draw one screen this frame (extracted on the client thread). */
public final class ScreenRenderState extends BlockEntityRenderState {
    public enum Mode { SKIP, INCOMPLETE_TILE, SCREEN }

    public Mode mode = Mode.SKIP;
    public Direction facing = Direction.NORTH;
    public int width = 1;
    public int height = 1;

    public boolean powered;
    public float brightness = 1f;
    public int rotation;
    public PlayerManager.Status status = PlayerManager.Status.IDLE;
    public String title = "";
    public String subtitle = "";
    public String hint = "";

    public Identifier videoTexture;
    public float videoAspect = 16f / 9f;
    public float videoAlpha;
    public int glowColor;

    public float powerAnim = 1f;   // 0..1 progress of the power-on effect, 1 = done
    public float powerOffAnim = -1; // 0..1 progress of the power-off effect, -1 = none
    public float osdAlpha;
    public String osdText = "";
    public float progress = -1;    // 0..1 for the overlay progress bar
    public boolean playing;
    public float timeSeconds;      // for idle animations
}
