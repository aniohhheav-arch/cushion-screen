package dev.cushionscreen.neoforge;

import dev.cushionscreen.network.ModNetwork;
import dev.cushionscreen.platform.Platform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class NeoForgePlatform implements Platform {
    private static final Map<ResourceKey<?>, DeferredRegister<?>> REGISTERS = new LinkedHashMap<>();
    /** Payload registrations wait for RegisterPayloadHandlersEvent. */
    static final List<Consumer<PayloadRegistrar>> PAYLOADS = new ArrayList<>();

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R, T extends R> Supplier<T> register(ResourceKey<? extends Registry<R>> registry, Identifier id, Supplier<T> factory) {
        DeferredRegister<R> reg = (DeferredRegister<R>) REGISTERS.computeIfAbsent(registry,
            k -> DeferredRegister.create((ResourceKey<? extends Registry<R>>) k, id.getNamespace()));
        return (Supplier<T>) (Supplier<?>) reg.register(id.getPath(), factory);
    }

    static void attach(IEventBus modBus) {
        REGISTERS.values().forEach(r -> r.register(modBus));
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerbound(
        CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
        PAYLOADS.add(r -> r.playToServer(type, codec, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) handler.accept(payload, player);
        }));
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientbound(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        PAYLOADS.add(r -> r.playToClient(type, codec, (payload, context) -> ModNetwork.dispatchClient(payload)));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }
}
