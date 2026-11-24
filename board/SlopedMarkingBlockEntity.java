package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.core.properties.TrafficMarkerTypes;
import com.rinventor.ptm2.core.properties.VDMemoryIDs;
import com.rinventor.ptm2.dimension.virtual.storage.VDStorageHandler;
import com.rinventor.ptm2.dimension.virtual.types.VDRoadMarking;
import com.rinventor.ptm2.dimension.virtual.types.VDTrafficMarker;
import com.rinventor.ptm2.dimension.virtual.util.VDUtils;
import com.rinventor.ptm2.objects.blocks.RoadMarking;
import com.rinventor.ptm2.platform.PlatformBlockEntity;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

/**
 * Наклонная разметка: хранит, какой обычный блок разметки ptm2 изображать, поворот (0-7), угол наклона в градусах
 * (плюс - поднимается по направлению поворота) и смещение по высоте относительно низа ячейки (обычно отрицательное:
 * поверхность миниблока лежит ниже верха ячейки). Для ИИ регистрируется в хранилище как обычная разметка.
 * Необязательный массив hs (SUB высот вдоль направления линии, относительно низа ячейки): линия рисуется кусками,
 * каждый на своём уровне (ямки и бугорки из миниблоков).
 * На клиенте для разметок, которые ptm2 рисует блок-сущностью (линии), создаётся немировая блок-сущность того же типа
 * (mimicEntity), чтобы нарисовать её родным рендерером.
 */
public class SlopedMarkingBlockEntity extends PlatformBlockEntity {
    public static final int SUB = 8;

    private String blockId = "";
    private int rot;
    private float pitch;
    private float dy;
    private float[] hs;
    private BlockState cachedState;
    private String cachedFor = "";
    private BlockEntity inner;
    private String innerFor = "";

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

    /** Высоты по кускам линии (SUB штук) или null, если плитка целиком на одной высоте dy. */
    public float[] getHeights() {
        return this.hs;
    }

    /** Краткое описание данных плитки (для диагностики карандашом). */
    public String describe() {
        return this.blockId + " rot=" + this.rot + " pitch=" + String.format(Locale.ROOT, "%.1f", this.pitch)
            + " dy=" + String.format(Locale.ROOT, "%.2f", this.dy) + (this.hs != null ? " кусков=" + this.hs.length : "");
    }

    public void configure(String blockId, int rot, float pitch, float dy) {
        this.configure(blockId, rot, pitch, dy, null);
    }

    public void configure(String blockId, int rot, float pitch, float dy, float[] heights) {
        this.blockId = blockId == null ? "" : blockId;
        this.rot = ((rot % 8) + 8) % 8;
        this.pitch = pitch;
        this.dy = dy;
        this.hs = (heights != null && heights.length == SUB) ? heights : null;
        this.cachedState = null;
        this.inner = null;
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

    /**
     * Немировая блок-сущность исходной разметки (только для отрисовки на клиенте): такой же тип, тот же поворот, та же позиция.
     * null, если блок разметки не EntityBlock.
     */
    public BlockEntity mimicEntity() {
        BlockState st = this.mimicState();
        if (st == null || this.level == null || !(st.getBlock() instanceof EntityBlock)) {
            return null;
        }
        String key = this.blockId + ":" + this.rot;
        if (this.inner == null || !key.equals(this.innerFor)) {
            try {
                this.inner = ((EntityBlock) st.getBlock()).newBlockEntity(this.getBlockPos(), st);
                if (this.inner != null) {
                    this.inner.setLevel(this.level);
                }
            } catch (RuntimeException e) {
                this.inner = null;
            }
            this.innerFor = key;
        }
        return this.inner;
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
        if (this.hs != null) {
            int[] ints = new int[this.hs.length];
            for (int i = 0; i < ints.length; i++) {
                ints[i] = Math.round(this.hs[i] * 256.0F);
            }
            tag.putIntArray("hs", ints);
        }
    }

    public void m_142466_(CompoundTag tag) {
        super.m_142466_(tag);
        this.blockId = tag.getString("mark");
        this.rot = tag.getInt("rot");
        this.pitch = tag.getFloat("pitch");
        this.dy = tag.getFloat("dy");
        this.hs = null;
        if (tag.contains("hs")) {
            int[] ints = tag.getIntArray("hs");
            if (ints.length == SUB) {
                float[] f = new float[SUB];
                for (int i = 0; i < SUB; i++) {
                    f[i] = ints[i] / 256.0F;
                }
                this.hs = f;
            }
        }
        this.cachedState = null;
        this.inner = null;
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
        return new AABB(this.getBlockPos()).inflate(1.5D, 3.5D, 1.5D);
    }
}
