package dev.cushionscreen.fabric;

import dev.cushionscreen.network.ModNetwork;
import dev.cushionscreen.platform.Platform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;

public final class FabricPlatform implements Platform {
    /** Clientbound types, wired to receivers by {@link FabricClientMod}. */
    static final List<CustomPacketPayload.Type<?>> CLIENTBOUND = new ArrayList<>();

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R, T extends R> Supplier<T> register(ResourceKey<? extends Registry<R>> registry, Identifier id, Supplier<T> factory) {
        Registry<R> reg = (Registry<R>) BuiltInRegistries.REGISTRY.getValue(registry.identifier());
        if (reg == null) throw new IllegalArgumentException("Unknown registry " + registry);
        T value = Registry.register(reg, id, factory.get());
        return () -> value;
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerbound(
        CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload, context.player()));
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientbound(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        CLIENTBOUND.add(type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) FabricClientMod.send(payload);
    }

    static void dispatch(CustomPacketPayload payload) {
        ModNetwork.dispatchClient(payload);
    }
}
