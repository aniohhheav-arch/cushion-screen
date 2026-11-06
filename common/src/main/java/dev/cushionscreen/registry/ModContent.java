package dev.cushionscreen.registry;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.block.CushionScreenBlock;
import dev.cushionscreen.block.CushionScreenBlockEntity;
import dev.cushionscreen.block.ScreenFrameBlock;
import dev.cushionscreen.platform.Platform;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModContent {
    public static final Supplier<CushionScreenBlock> SCREEN_BLOCK = block("cushion_screen", CushionScreenBlock::new,
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLACK)
            .strength(0.8f)
            .sound(SoundType.WOOL)
            .lightLevel(s -> s.getValue(CushionScreenBlock.LIT) ? CushionScreenBlock.LIGHT_LEVEL : 0));

    public static final Supplier<ScreenFrameBlock> FRAME_BLOCK = block("screen_frame", ScreenFrameBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.8f).sound(SoundType.WOOL));

    public static final Supplier<BlockItem> SCREEN_ITEM = blockItem("cushion_screen", SCREEN_BLOCK);
    public static final Supplier<BlockItem> FRAME_ITEM = blockItem("screen_frame", FRAME_BLOCK);

    public static final Supplier<BlockEntityType<CushionScreenBlockEntity>> SCREEN_BLOCK_ENTITY = Platform.INSTANCE.register(
        Registries.BLOCK_ENTITY_TYPE, CushionScreen.id("cushion_screen"),
        () -> new BlockEntityType<>(CushionScreenBlockEntity::new, Set.of(SCREEN_BLOCK.get())));

    public static final Supplier<CreativeModeTab> TAB = Platform.INSTANCE.register(
        Registries.CREATIVE_MODE_TAB, CushionScreen.id("main"),
        () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.cushionscreen"))
            .icon(() -> new ItemStack(SCREEN_ITEM.get()))
            .displayItems((params, output) -> {
                output.accept(SCREEN_ITEM.get());
                output.accept(FRAME_ITEM.get());
            })
            .build());

    private ModContent() {}

    /** Touching the class runs the static registrations above. */
    public static void init() {}

    private static <B extends Block> Supplier<B> block(String name, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
        Identifier id = CushionScreen.id(name);
        return Platform.INSTANCE.register(Registries.BLOCK, id,
            () -> factory.apply(props.setId(ResourceKey.create(Registries.BLOCK, id))));
    }

    private static Supplier<BlockItem> blockItem(String name, Supplier<? extends Block> block) {
        Identifier id = CushionScreen.id(name);
        return Platform.INSTANCE.register(Registries.ITEM, id,
            () -> new BlockItem(block.get(), new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
    }
}
