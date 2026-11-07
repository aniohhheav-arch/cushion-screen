package dev.cushionscreen.network;

import dev.cushionscreen.client.ClientHooks;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.server.MediaClock;
import dev.cushionscreen.server.MediaShare;
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

        p.registerServerbound(Payloads.RequestMedia.TYPE, Payloads.RequestMedia.CODEC, (msg, player) -> share(s -> s.handleRequest(player, msg)));
        p.registerServerbound(Payloads.HaveMedia.TYPE, Payloads.HaveMedia.CODEC, (msg, player) -> share(s -> s.handleHave(player, msg)));
        p.registerServerbound(Payloads.UploadPiece.TYPE, Payloads.UploadPiece.CODEC, (msg, player) -> share(s -> s.handleUploadPiece(player, msg)));
        p.registerServerbound(Payloads.DownloadAck.TYPE, Payloads.DownloadAck.CODEC, (msg, player) -> share(s -> s.handleDownloadAck(player, msg)));

        p.registerClientbound(Payloads.SyncScreens.TYPE, Payloads.SyncScreens.CODEC);
        p.registerClientbound(Payloads.UploadRequest.TYPE, Payloads.UploadRequest.CODEC);
        p.registerClientbound(Payloads.UploadAck.TYPE, Payloads.UploadAck.CODEC);
        p.registerClientbound(Payloads.DownloadPiece.TYPE, Payloads.DownloadPiece.CODEC);
        p.registerClientbound(Payloads.ShareStatus.TYPE, Payloads.ShareStatus.CODEC);
        p.registerClientbound(Payloads.ClockPong.TYPE, Payloads.ClockPong.CODEC);
        p.registerClientbound(Payloads.Notice.TYPE, Payloads.Notice.CODEC);
    }

    private static void share(java.util.function.Consumer<MediaShare> action) {
        MediaShare s = MediaShare.get();
        if (s != null) action.accept(s);
    }

    /** Called by the platform on the client thread for every server-to-client payload. */
    public static void dispatchClient(CustomPacketPayload payload) {
        ClientHooks.handlePayload(payload);
    }
}
