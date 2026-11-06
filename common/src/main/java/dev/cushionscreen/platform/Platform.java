package dev.cushionscreen.platform;

import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;

/**
 * The small surface that differs between mod loaders. Each loader module ships one implementation,
 * found through {@link ServiceLoader} (META-INF/services/dev.cushionscreen.platform.Platform).
 */
public interface Platform {
    Platform INSTANCE = ServiceLoader.load(Platform.class, Platform.class.getClassLoader())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No Cushion Screen platform implementation found"));

    String loaderName();

    Path gameDirectory();

    /** Registers an object; the returned supplier is valid once the loader has run its registration phase. */
    <R, T extends R> Supplier<T> register(ResourceKey<? extends Registry<R>> registry, Identifier id, Supplier<T> factory);

    /** Client to server packet; the handler runs on the server thread. */
    <T extends CustomPacketPayload> void registerServerbound(
        CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler);

    /** Server to client packet; received payloads go to {@code ModNetwork.dispatchClient} on the client thread. */
    <T extends CustomPacketPayload> void registerClientbound(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    void sendToServer(CustomPacketPayload payload);
}
