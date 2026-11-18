package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.engine.network.ServerSideHandler;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Клиент сохраняет настройку табло: новая строка полос для блока в указанной позиции. */
public class LaneBoardPacket implements ServerSideHandler {
    private final BlockPos pos;
    private final String lanes;

    public LaneBoardPacket(BlockPos pos, String lanes) {
        this.pos = pos;
        this.lanes = lanes;
    }

    public void handle(ServerPlayer player) {
        Level level = player.level();
        if (player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D) > 100.0D) {
            return;
        }
        BlockEntity be = level.getBlockEntity(this.pos);
        if (!(be instanceof LaneBoardBlockEntity)) {
            return;
        }
        List<String> parsed = LaneBoardData.parse(this.lanes);
        ((LaneBoardBlockEntity) be).setLanes(LaneBoardData.join(parsed));
        BlockState state = level.getBlockState(this.pos);
        level.sendBlockUpdated(this.pos, state, state, 3);
    }
}
