package dev.cushionscreen.client.media;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * A GPU texture sized to the decoded video, created once per player and updated in place with
 * {@code writeToTexture} for each new frame. Works on every render backend Minecraft offers.
 */
public final class VideoTexture extends AbstractTexture {
    private final Identifier id;
    private final int width;
    private final int height;

    public VideoTexture(Identifier id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
        GpuDevice device = RenderSystem.getDevice();
        this.texture = device.createTexture(() -> "Cushion Screen video " + id,
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        this.textureView = device.createTextureView(this.texture);
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        // start black, not undefined memory
        ByteBuffer black = ByteBuffer.allocateDirect(width * height * 4);
        for (int i = 3; i < black.capacity(); i += 4) black.put(i, (byte) 255);
        upload(black);
        Minecraft.getInstance().getTextureManager().register(id, this);
    }

    public Identifier id() {
        return id;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void upload(ByteBuffer rgba) {
        if (texture == null) return;
        rgba.position(0).limit(width * height * 4);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, rgba, 0, 0, 0, 0, width, height);
    }

    public void release() {
        Minecraft.getInstance().getTextureManager().release(id);
    }
}
