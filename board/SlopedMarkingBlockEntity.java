package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.core.properties.TrafficMarkerTypes;
import com.rinventor.ptm2.core.properties.VDMemoryIDs;
import com.rinventor.ptm2.dimension.virtual.storage.VDStorageHandler;
import com.rinventor.ptm2.dimension.virtual.types.VDRoadMarking;
import com.rinventor.ptm2.dimension.virtual.types.VDTrafficMarker;
import com.rinventor.ptm2.dimension.virtual.util.VDUtils;
import com.rinventor.ptm2.objects.blocks.RoadMarking;
import com.rinventor.ptm2.platform.PlatformBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

/**
 * Наклонная разметка: хранит, какой обычный блок разметки ptm2 изображать, поворот (0-7), угол наклона в градусах
 * (плюс - поднимается по направлению поворота) и смещение по высоте относительно низа ячейки (обычно отрицательное:
 * поверхность миниблока лежит ниже верха ячейки). Для ИИ регистрируется в хранилище как обычная разметка.
 */
public class SlopedMarkingBlockEntity extends PlatformBlockEntity {
    private String blockId = "";
    private int rot;
    private float pitch;
    private float dy;
    private BlockState cachedState;
    private String cachedFor = "";

    public SlopedMarkingBlockEntity(BlockPos pos, BlockState state) {
        super(LaneBoardRegistry.SLOPED_ENTITY.get(), pos, state);
    }

    public int getRot() {
        return this.rot;
    }

    public float getPitch() {
        return this.pitch;
    }

    public float getDy() {
        return this.dy;
    }

    public void configure(String blockId, int rot, float pitch, float dy) {
        this.blockId = blockId == null ? "" : blockId;
        this.rot = ((rot % 8) + 8) % 8;
        this.pitch = pitch;
        this.dy = dy;
        this.cachedState = null;
        this.setChanged();
        if (this.level != null) {
            BlockState st = this.getBlockState();
            this.level.sendBlockUpdated(this.getBlockPos(), st, st, 3);
        }
    }

    public Block mimicBlock() {
        try {
            if (this.blockId.isEmpty()) {
                return null;
            }
            Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(this.blockId));
            return b == Blocks.AIR ? null : b;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Состояние исходного блока разметки с нужным поворотом (для рендера). */
    public BlockState mimicState() {
        String key = this.blockId + ":" + this.rot;
        if (this.cachedState != null && key.equals(this.cachedFor)) {
            return this.cachedState;
        }
        Block b = this.mimicBlock();
        if (b == null) {
            this.cachedState = null;
            return null;
        }
        BlockState st = b.defaultBlockState();
        Property<?> p = b.getStateDefinition().getProperty("rotation");
        if (p instanceof IntegerProperty) {
            st = st.setValue((IntegerProperty) p, this.rot);
        }
        this.cachedState = st;
        this.cachedFor = key;
        return st;
    }

    /** Регистрация в хранилище ptm2 так же, как при установке обычного блока разметки (только сервер). */
    public void register(Level lvl) {
        Block mb = this.mimicBlock();
        if (lvl == null || lvl.isClientSide || !(mb instanceof RoadMarking)) {
            return;
        }
        String data;
        if (VDUtils.getMarkerType(mb) != TrafficMarkerTypes.NONE) {
            data = new VDTrafficMarker(TrafficMarkerTypes.DIRECTION, this.rot, VDUtils.getMarkerDirection(mb), false).toString();
        } else {
            data = new VDRoadMarking(RoadMarking.type(mb), this.rot, false).toString();
        }
        VDStorageHandler.update(lvl, this.getBlockPos(), data);
    }

    public static void unregister(Level lvl, BlockPos pos) {
        if (lvl != null && !lvl.isClientSide) {
            VDStorageHandler.remove(lvl, pos, VDMemoryIDs.ROAD_MARKING);
            VDStorageHandler.remove(lvl, pos, VDMemoryIDs.TRAFFIC_MARKER);
        }
    }

    private void write(CompoundTag tag) {
        tag.putString("mark", this.blockId);
        tag.putInt("rot", this.rot);
        tag.putFloat("pitch", this.pitch);
        tag.putFloat("dy", this.dy);
    }

    public void m_142466_(CompoundTag tag) {
        super.m_142466_(tag);
        this.blockId = tag.getString("mark");
        this.rot = tag.getInt("rot");
        this.pitch = tag.getFloat("pitch");
        this.dy = tag.getFloat("dy");
        this.cachedState = null;
    }

    protected void m_183515_(CompoundTag tag) {
        super.m_183515_(tag);
        this.write(tag);
    }

    public CompoundTag m_5995_() {
        CompoundTag tag = super.m_5995_();
        this.write(tag);
        return tag;
    }

    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Плитка лежит ниже границы своей ячейки и наклонена: расширяем область рендера. */
    public AABB getRenderBoundingBox() {
        return new AABB(this.getBlockPos()).inflate(1.5D, 1.5D, 1.5D);
    }
}
