package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.objects.blocks.SignSquare;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Блок «Табло направлений по полосам»: форма, крепление и поворот как у знаков, рисуется целиком своим рендерером. */
public class LaneBoardBlock extends SignSquare implements EntityBlock {
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LaneBoardBlockEntity(pos, state);
    }

    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            LaneBoardClient.open(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
