package dev.cushionscreen.client.gui;

import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.screen.ScreenData;
import net.minecraft.core.BlockPos;

/** Requests to the server about one screen. The server validates every one of them. */
final class ScreenActions {
    private ScreenActions() {}

    static void act(BlockPos pos, Payloads.Action action, long value) {
        Platform.INSTANCE.sendToServer(new Payloads.ScreenAction(pos, action, value));
    }

    static void configure(BlockPos pos, ScreenData d) {
        Platform.INSTANCE.sendToServer(new Payloads.Configure(pos, d.name(), d.permission(), d.powered(), d.brightness(),
            d.audioEnabled(), d.volume(), d.rotation()));
    }
}
