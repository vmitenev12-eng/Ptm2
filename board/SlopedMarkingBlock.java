package com.rinventor.ptm2.fixes.board;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Невидимый блок-носитель наклонной разметки. Саму плитку рисует SlopedMarkingRenderer по данным блока-сущности,
 * коллизии нет. Ставится инструментом «Разметочный карандаш» на поверхность миниблоков (например, pillar).
 *
 * Форма (getShape) для игрока (прицел, ломание) - тонкая плита 1/16 внизу ячейки. Но ptm2 ищет поверхность под колёсами
 * и ногами через state.getShape(level, pos) без контекста (CollisionContext.empty()), и такая плита поднимала бы машины
 * на блок над дорогой. Поэтому для запроса без контекста форма пустая.
 */
public class SlopedMarkingBlock extends Block implements EntityBlock {
    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D);

    public SlopedMarkingBlock() {
        super(BlockBehaviour.Properties.of().noCollission().noOcclusion().strength(0.1F).sound(SoundType.STONE));
    }

    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SlopedMarkingBlockEntity(pos, state);
    }

    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context == CollisionContext.empty()) {
            return Shapes.empty();
        }
        return SHAPE;
    }

    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())) {
            SlopedMarkingBlockEntity.unregister(level, pos);
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SlopedMarkingBlockEntity) {
            Block mb = ((SlopedMarkingBlockEntity) be).mimicBlock();
            if (mb != null) {
                return new ItemStack(mb);
            }
        }
        return ItemStack.EMPTY;
    }
}
