package dev.cushionscreen.client;

import dev.cushionscreen.client.gui.ScreenControlScreen;
import dev.cushionscreen.client.media.ClientShare;
import dev.cushionscreen.client.media.FFmpeg;
import dev.cushionscreen.client.media.PlayerManager;
import dev.cushionscreen.client.media.VideoLibrary;
import dev.cushionscreen.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client entry point shared by all loaders. */
public final class CushionScreenClient {
    private static Object lastLevel;

    private CushionScreenClient() {}

    public static void init() {
        ClientHooks.install(new ClientHooks.Impl() {
            @Override
            public void openControls(BlockPos pos) {
                Minecraft.getInstance().gui.setScreen(new ScreenControlScreen(pos));
            }

            @Override
            public void screenBlockChanged(BlockPos pos, boolean present) {
                ClientScreens.blockChanged(pos, present);
            }

            @Override
            public void handlePayload(CustomPacketPayload payload) {
                CushionScreenClient.handle(payload);
            }
        });
        VideoLibrary.ioExecutor().execute(VideoLibrary::load);
        FFmpeg.load(); // warm up natives in the background
    }

    private static void handle(CustomPacketPayload payload) {
        if (payload instanceof Payloads.SyncScreens sync) {
            ClientScreens.apply(sync);
        } else if (payload instanceof Payloads.ClockPong pong) {
            ClientClock.onPong(pong);
        } else if (payload instanceof Payloads.DownloadPiece piece) {
            ClientShare.handle(piece);
        } else if (payload instanceof Payloads.ShareStatus status) {
            ClientShare.handle(status);
        } else if (payload instanceof Payloads.UploadRequest request) {
            ClientShare.handle(request);
        } else if (payload instanceof Payloads.UploadAck ack) {
            ClientShare.handle(ack);
        } else if (payload instanceof Payloads.Notice notice) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable(notice.translationKey()));
        }
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            // new world or dimension: the server follows up with a full sync
            lastLevel = mc.level;
            PlayerManager.closeAll();
            ClientScreens.invalidate();
            ClientClock.reset();
        }
        if (mc.level != null) ClientClock.tick(mc.isPaused());
    }

    public static void renderFrame() {
        PlayerManager.renderFrame();
    }

    /** May be called from the network thread; GPU and OpenAL resources must be freed on the client thread. */
    public static void disconnected() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(CushionScreenClient::disconnected);
            return;
        }
        PlayerManager.closeAll();
        ClientShare.reset();
        ClientScreens.clear();
        ClientClock.reset();
        lastLevel = null;
    }
}
