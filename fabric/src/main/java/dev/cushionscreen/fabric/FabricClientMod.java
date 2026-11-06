package dev.cushionscreen.fabric;

import dev.cushionscreen.client.CushionScreenClient;
import dev.cushionscreen.client.render.ScreenBlockRenderer;
import dev.cushionscreen.registry.ModContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class FabricClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CushionScreenClient.init();
        for (CustomPacketPayload.Type<?> type : FabricPlatform.CLIENTBOUND) receive(type);
        BlockEntityRendererRegistry.register(ModContent.SCREEN_BLOCK_ENTITY.get(), ScreenBlockRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> CushionScreenClient.clientTick());
        // after the frame is extracted and before any render pass opens: the only safe place to upload textures
        LevelExtractionEvents.END_EXTRACTION.register(context -> CushionScreenClient.renderFrame());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> CushionScreenClient.disconnected());
    }

    private static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> FabricPlatform.dispatch(payload));
    }

    static void send(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) ClientPlayNetworking.send(payload);
    }
}
