package dev.cushionscreen.client;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Bridge from shared code (blocks, block entities, networking) to the client. It holds no client types itself, so
 * a dedicated server can load the classes that call it; the client installs the real implementation at startup.
 */
public final class ClientHooks {
    public interface Impl {
        void openControls(BlockPos pos);

        void screenBlockChanged(BlockPos pos, boolean present);

        void handlePayload(CustomPacketPayload payload);
    }

    private static volatile Impl impl;

    private ClientHooks() {}

    public static void install(Impl implementation) {
        impl = implementation;
    }

    public static void openControls(BlockPos pos) {
        Impl i = impl;
        if (i != null) i.openControls(pos);
    }

    public static void screenBlockChanged(BlockPos pos, boolean present) {
        Impl i = impl;
        if (i != null) i.screenBlockChanged(pos, present);
    }

    public static void handlePayload(CustomPacketPayload payload) {
        Impl i = impl;
        if (i != null) i.handlePayload(payload);
    }
}
