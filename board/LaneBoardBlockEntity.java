package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.platform.PlatformBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Хранит список полос табло (строка значков через запятую). Сохраняется в мире и синхронизируется с клиентами. */
public class LaneBoardBlockEntity extends PlatformBlockEntity {
    private String lanes = "straight";

    public LaneBoardBlockEntity(BlockPos pos, BlockState state) {
        super(LaneBoardRegistry.ENTITY.get(), pos, state);
    }

    public String getLanes() {
        return this.lanes;
    }

    public void setLanes(String value) {
        this.lanes = value == null ? "straight" : value;
        this.setChanged();
    }

    public void m_142466_(CompoundTag tag) {
        super.m_142466_(tag);
        if (tag.contains("lanes")) {
            this.lanes = tag.getString("lanes");
        }
    }

    protected void m_183515_(CompoundTag tag) {
        super.m_183515_(tag);
        tag.putString("lanes", this.lanes);
    }

    public CompoundTag m_5995_() {
        CompoundTag tag = super.m_5995_();
        tag.putString("lanes", this.lanes);
        return tag;
    }

    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Табло шире блока: без этого оно пропадало бы, когда сам блок выходит из кадра. */
    public AABB getRenderBoundingBox() {
        return new AABB(this.getBlockPos()).inflate(6.0D, 2.0D, 6.0D);
    }
}
