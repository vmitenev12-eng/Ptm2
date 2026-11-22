package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.platform.DeferredRegister;
import com.rinventor.ptm2.platform.RegistryObject;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Регистрация блока табло, его предмета и типа блока-сущности, а также наклонной разметки и разметочного карандаша.
 * Вызывается патчами из ModBlocks, ModItems, ModBlockEntities.
 */
public final class LaneBoardRegistry {
    public static RegistryObject<LaneBoardBlock> BLOCK;
    public static RegistryObject<Item> ITEM;
    public static RegistryObject<BlockEntityType<LaneBoardBlockEntity>> ENTITY;

    public static RegistryObject<SlopedMarkingBlock> SLOPED_BLOCK;
    public static RegistryObject<BlockEntityType<SlopedMarkingBlockEntity>> SLOPED_ENTITY;
    public static RegistryObject<Item> LINE_TOOL;

    private LaneBoardRegistry() {
    }

    public static void registerBlock(DeferredRegister<Block> blocks) {
        BLOCK = blocks.register("lane_board", LaneBoardBlock::new);
        SLOPED_BLOCK = blocks.register("sloped_marking", SlopedMarkingBlock::new);
    }

    public static void registerItem(DeferredRegister<Item> items) {
        ITEM = items.register("lane_board", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
        LINE_TOOL = items.register("line_marker", () -> new LineMarkerItem(new Item.Properties().stacksTo(1)));
    }

    public static void registerEntity(DeferredRegister<BlockEntityType<?>> entities) {
        ENTITY = entities.register("lane_board_entity", () -> BlockEntityType.Builder.of(LaneBoardBlockEntity::new, BLOCK.get()).build(null));
        SLOPED_ENTITY = entities.register("sloped_marking_entity", () -> BlockEntityType.Builder.of(SlopedMarkingBlockEntity::new, SLOPED_BLOCK.get()).build(null));
    }
}
