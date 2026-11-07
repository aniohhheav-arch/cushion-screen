package dev.cushionscreen.neoforge;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.server.ServerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(CushionScreen.MOD_ID)
public final class NeoForgeMod {
    public NeoForgeMod(IEventBus modBus) {
        CushionScreen.init();
        NeoForgePlatform.attach(modBus);
        modBus.addListener(NeoForgeMod::registerPayloads);

        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> ServerEvents.serverStarting(e.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> ServerEvents.serverStopping(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> ServerEvents.serverTickEnd(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) ServerEvents.playerLeft(player);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> entered(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> entered(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> entered(e));
    }

    private static void entered(PlayerEvent e) {
        if (e.getEntity() instanceof ServerPlayer player) ServerEvents.playerEnteredLevel(player);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional(): vanilla clients and servers without the mod can still connect
        PayloadRegistrar registrar = event.registrar("1").optional();
        NeoForgePlatform.PAYLOADS.forEach(p -> p.accept(registrar));
    }
}
