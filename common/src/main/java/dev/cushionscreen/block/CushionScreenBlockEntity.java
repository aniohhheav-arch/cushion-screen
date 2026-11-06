package dev.cushionscreen.block;

import dev.cushionscreen.client.ClientHooks;
import dev.cushionscreen.registry.ModContent;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Holds only which logical screen this tile belongs to; the screen's data lives in {@code ScreenRegistry}. */
public class CushionScreenBlockEntity extends BlockEntity {
    private @Nullable UUID screenId;

    public CushionScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.SCREEN_BLOCK_ENTITY.get(), pos, state);
    }

    public @Nullable UUID screenId() {
        return screenId;
    }

    public void setScreenId(@Nullable UUID id) {
        if (Objects.equals(id, screenId)) return;
        screenId = id;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        UUID previous = screenId;
        screenId = input.read("screen", UUIDUtil.CODEC).orElse(null);
        if (level != null && level.isClientSide() && !Objects.equals(previous, screenId)) {
            ClientHooks.screenBlockChanged(worldPosition, true);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (screenId != null) output.store("screen", UUIDUtil.CODEC, screenId);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level.isClientSide()) ClientHooks.screenBlockChanged(worldPosition, true);
    }

    /**
     * Runs for every way a tile can disappear (mining, explosions, commands that skip side effects, world editors)
     * and also when its chunk unloads. Only a tile whose block is really gone splits or deletes its screen.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level == null) return;
        if (level.isClientSide()) {
            ClientHooks.screenBlockChanged(worldPosition, false);
        } else if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Look only at a chunk already in memory: asking the level would load the very chunk being unloaded.
            var chunk = serverLevel.getChunkSource().getChunkNow(
                net.minecraft.core.SectionPos.blockToSectionCoord(worldPosition.getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(worldPosition.getZ()));
            if (chunk != null && !(chunk.getBlockState(worldPosition).getBlock() instanceof CushionScreenBlock)) {
                dev.cushionscreen.server.ScreenRegistry.get(serverLevel)
                    .queueRemoved(worldPosition, getBlockState().getValue(CushionScreenBlock.FACING), screenId);
            }
        }
    }
}
