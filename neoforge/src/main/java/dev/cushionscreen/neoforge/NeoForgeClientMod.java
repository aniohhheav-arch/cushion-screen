package dev.cushionscreen.neoforge;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.client.CushionScreenClient;
import dev.cushionscreen.client.render.ScreenBlockRenderer;
import dev.cushionscreen.registry.ModContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = CushionScreen.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeClientMod {
    public NeoForgeClientMod(IEventBus modBus) {
        CushionScreenClient.init();
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) ->
            e.registerBlockEntityRenderer(ModContent.SCREEN_BLOCK_ENTITY.get(), ScreenBlockRenderer::new));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> CushionScreenClient.clientTick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> CushionScreenClient.renderFrame());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> CushionScreenClient.disconnected());
    }
}
