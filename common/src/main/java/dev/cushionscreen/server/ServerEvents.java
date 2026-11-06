package dev.cushionscreen.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Loader-independent server lifecycle hooks; each loader forwards its events here. */
public final class ServerEvents {
    private ServerEvents() {}

    public static void serverStarting(MinecraftServer server) {
        MediaClock.reset();
    }

    public static void serverTickEnd(MinecraftServer server) {
        MediaClock.tick();
        for (ServerLevel level : server.getAllLevels()) {
            ScreenRegistry.get(level).tick();
        }
    }

    /** Join, respawn and dimension change: the client gets the full screen list of its new dimension. */
    public static void playerEnteredLevel(ServerPlayer player) {
        ScreenRegistry.get(player.level()).sendFullSync(player);
    }
}
