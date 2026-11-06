package dev.cushionscreen.network;

import dev.cushionscreen.client.ClientHooks;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.server.MediaClock;
import dev.cushionscreen.server.ScreenRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;

public final class ModNetwork {
    private ModNetwork() {}

    public static void init() {
        Platform p = Platform.INSTANCE;
        p.registerServerbound(Payloads.ScreenAction.TYPE, Payloads.ScreenAction.CODEC,
            (msg, player) -> ScreenRegistry.get((ServerLevel) player.level()).handleAction(player, msg));
        p.registerServerbound(Payloads.LoadMedia.TYPE, Payloads.LoadMedia.CODEC,
            (msg, player) -> ScreenRegistry.get((ServerLevel) player.level()).handleLoad(player, msg));
        p.registerServerbound(Payloads.Configure.TYPE, Payloads.Configure.CODEC,
            (msg, player) -> ScreenRegistry.get((ServerLevel) player.level()).handleConfigure(player, msg));
        p.registerServerbound(Payloads.ClockPing.TYPE, Payloads.ClockPing.CODEC,
            (msg, player) -> p.sendToPlayer(player, new Payloads.ClockPong(msg.clientTimeMs(), MediaClock.nowMs())));

        p.registerClientbound(Payloads.SyncScreens.TYPE, Payloads.SyncScreens.CODEC);
        p.registerClientbound(Payloads.ClockPong.TYPE, Payloads.ClockPong.CODEC);
        p.registerClientbound(Payloads.Notice.TYPE, Payloads.Notice.CODEC);
    }

    /** Called by the platform on the client thread for every server-to-client payload. */
    public static void dispatchClient(CustomPacketPayload payload) {
        ClientHooks.handlePayload(payload);
    }
}
