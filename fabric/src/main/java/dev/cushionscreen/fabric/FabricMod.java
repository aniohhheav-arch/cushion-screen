package dev.cushionscreen.fabric;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.server.ServerEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class FabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        CushionScreen.init();
        ServerLifecycleEvents.SERVER_STARTING.register(ServerEvents::serverStarting);
        ServerTickEvents.END_SERVER_TICK.register(ServerEvents::serverTickEnd);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerEvents.playerEnteredLevel(handler.getPlayer()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> ServerEvents.playerEnteredLevel(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> ServerEvents.playerEnteredLevel(player));
    }
}
