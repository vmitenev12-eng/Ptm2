package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.objects.blocks.RoadBlock;
import com.rinventor.ptm2.objects.blocks.RoadMarking;
import com.rinventor.ptm2.objects.blocks.RoadSlab;
import com.rinventor.ptm2.objects.blocks.SidewalkBlock;
import com.rinventor.ptm2.objects.blocks.SidewalkSlab;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Мягкая поддержка LittleTiles: ячейка из миниблоков (BETiles), в которой есть части-дорога или части-тротуар,
 * для ptm2 выглядит как соответствующий блок дороги или тротуара. Подмена делается в PTMBlock.getBlock
 * (хук ставит Patcher2), поэтому все проверки вида "под разметкой дорога", посадка транспорта, соединение точек
 * и пешеходы начинают считать такую ячейку дорогой. Без мода LittleTiles класс ничего не делает.
 * Дополнительно:
 * - topAt даёт высоту верха поверхности в точке (для миниблоков-дороги - по их коробкам, для обычных блоков - по форме коллизии);
 * - onMarkingPlaced вызывается из RoadMarking.onPlace (хук Patcher2): если разметка поставлена на миниблоки, верх которых
 *   ниже верха ячейки (ямка, низкая часть), она заменяется на SlopedMarkingBlock с нужным смещением.
 */
public final class LittleBridge {
    private static final long TTL_MS = 1500L;
    private static final int MAX_PARTS = 96;
    private static final int MAX_CACHE = 30000;

    private static final ClassValue<Boolean> IS_LT_BLOCK = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return c.getName().startsWith("team.creative.littletiles.");
        }
    };

    private static final ConcurrentHashMap<Long, Object[]> CACHE_SERVER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Object[]> CACHE_CLIENT = new ConcurrentHashMap<>();

    private static volatile Method allTilesMethod;
    private static volatile Method getStateMethod;
    private static volatile boolean broken;
    private static boolean snapping;

    private LittleBridge() {
    }

    /** Вызывается из PTMBlock.getBlock(level, x, y, z) перед возвратом. */
    public static Block mapD(Block block, LevelAccessor level, double x, double y, double z) {
        if (block == null || broken || !IS_LT_BLOCK.get(block.getClass())) {
            return block;
        }
        return resolve(block, level, new BlockPos((int) x, (int) y, (int) z));
    }

    /** Вызывается из PTMBlock.getBlock(level, pos) перед возвратом. */
    public static Block mapP(Block block, LevelAccessor level, BlockPos pos) {
        if (block == null || broken || !IS_LT_BLOCK.get(block.getClass())) {
            return block;
        }
        return resolve(block, level, pos);
    }

    private static Block resolve(Block original, LevelAccessor level, BlockPos pos) {
        try {
            ConcurrentHashMap<Long, Object[]> cache = level.isClientSide() ? CACHE_CLIENT : CACHE_SERVER;
            long key = pos.asLong();
            long now = System.currentTimeMillis();
            Object[] hit = cache.get(key);
            if (hit != null && now - (Long) hit[1] < TTL_MS) {
                return (Block) hit[0];
            }
            Block res = compute(original, level, pos);
            if (cache.size() > MAX_CACHE) {
                cache.clear();
            }
            cache.put(key, new Object[] {res, now});
            return res;
        } catch (Throwable t) {
            return original;
        }
    }

    private static boolean isTilesEntity(BlockEntity be) {
        return be != null && be.getClass().getName().endsWith(".BETiles");
    }

    private static Iterable<?> allTiles(BlockEntity be) throws ReflectiveOperationException {
        Method all = allTilesMethod;
        if (all == null) {
            all = be.getClass().getMethod("allTiles");
            allTilesMethod = all;
        }
        return (Iterable<?>) all.invoke(be);
    }

    private static Block partBlock(Object tile) throws ReflectiveOperationException {
        Method gs = getStateMethod;
        if (gs == null) {
            gs = tile.getClass().getMethod("getState");
            getStateMethod = gs;
        }
        return ((BlockState) gs.invoke(tile)).getBlock();
    }

    private static boolean roadLike(Block b) {
        return b instanceof RoadBlock || b instanceof RoadSlab || b instanceof SidewalkBlock || b instanceof SidewalkSlab;
    }

    private static Block compute(Block original, LevelAccessor level, BlockPos pos) throws ReflectiveOperationException {
        BlockEntity be = level.getBlockEntity(pos);
        if (!isTilesEntity(be)) {
            return original;
        }
        Block road = null;
        Block walk = null;
        int n = 0;
        for (Object pr : allTiles(be)) {
            if (++n > MAX_PARTS) {
                break;
            }
            Block pb = partBlock(((Map.Entry<?, ?>) pr).getValue());
            if (pb instanceof RoadBlock || pb instanceof RoadSlab) {
                if (road == null) {
                    road = pb;
                }
            } else if (pb instanceof SidewalkBlock || pb instanceof SidewalkSlab) {
                if (walk == null) {
                    walk = pb;
                }
            }
        }
        if (road != null) {
            return road;
        }
        return walk != null ? walk : original;
    }

    /**
     * Высота верха поверхности в ячейке cell в точке (px, pz) (мировые координаты).
     * Ячейка LittleTiles: верх частей-дороги/тротуара (коробки без наклона), обычная ячейка: верх формы коллизии.
     * NaN - нет покрытия; +Infinity - в ячейке LittleTiles есть наклонная (диагональная, pillar) часть-дорога,
     * высоту нужно брать из наклона.
     */
    public static double topAt(Level level, BlockPos cell, double px, double pz) {
        try {
            BlockEntity be = level.getBlockEntity(cell);
            if (!isTilesEntity(be)) {
                VoxelShape sh = level.getBlockState(cell).getCollisionShape(level, cell);
                return sh.isEmpty() ? Double.NaN : cell.getY() + sh.max(Direction.Axis.Y);
            }
            Object grid = be.getClass().getMethod("getGrid").invoke(be);
            int count = grid.getClass().getField("count").getInt(grid);
            double lx = (px - cell.getX()) * count;
            double lz = (pz - cell.getZ()) * count;
            double best = Double.NaN;
            boolean tilted = false;
            int n = 0;
            for (Object pr : allTiles(be)) {
                if (++n > MAX_PARTS) {
                    break;
                }
                Object tile = ((Map.Entry<?, ?>) pr).getValue();
                if (!roadLike(partBlock(tile))) {
                    continue;
                }
                for (Object box : (Iterable<?>) tile) {
                    Class<?> bc = box.getClass();
                    if (bc.getName().contains("Transformable")) {
                        tilted = true;
                        continue;
                    }
                    int minX = bc.getField("minX").getInt(box);
                    int minZ = bc.getField("minZ").getInt(box);
                    int maxX = bc.getField("maxX").getInt(box);
                    int maxY = bc.getField("maxY").getInt(box);
                    int maxZ = bc.getField("maxZ").getInt(box);
                    if (lx >= minX && lx < maxX && lz >= minZ && lz < maxZ) {
                        double top = cell.getY() + maxY / (double) count;
                        if (Double.isNaN(best) || top > best) {
                            best = top;
                        }
                    }
                }
            }
            if (tilted) {
                return Double.POSITIVE_INFINITY;
            }
            return best;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /**
     * Вызывается из RoadMarking.onPlace (хук Patcher2) на сервере. Если под разметкой ячейка LittleTiles, верх поверхности которой
     * в центре ниже верха ячейки (ямка, низкая часть), блок заменяется на наклонную плитку с тем же
     * типом и поворотом и смещением вниз. Если в ячейке наклонные части (pillar), ничего не меняется.
     */
    public static void onMarkingPlaced(Object self, BlockState state, Level level, BlockPos pos) {
        if (snapping) {
            return;
        }
        try {
            if (level == null || level.isClientSide || LaneBoardRegistry.SLOPED_BLOCK == null || !(self instanceof RoadMarking)) {
                return;
            }
            BlockPos below = pos.below();
            if (!isTilesEntity(level.getBlockEntity(below))) {
                return;
            }
            double px = pos.getX() + 0.5D;
            double pz = pos.getZ() + 0.5D;
            double top = topAt(level, below, px, pz);
            if (top == Double.POSITIVE_INFINITY) {
                return;
            }
            if (Double.isNaN(top)) {
                for (int k = 1; k <= 2; k++) {
                    double t = topAt(level, below.below(k), px, pz);
                    if (t == Double.POSITIVE_INFINITY) {
                        return;
                    }
                    if (!Double.isNaN(t)) {
                        top = t;
                        break;
                    }
                }
            }
            if (Double.isNaN(top)) {
                return;
            }
            double dy = top - pos.getY();
            if (dy > -0.03D || dy < -3.0D) {
                return;
            }
            Block mb = state.getBlock();
            int rot = 0;
            Property<?> rp = mb.getStateDefinition().getProperty("rotation");
            if (rp instanceof IntegerProperty) {
                rot = state.getValue((IntegerProperty) rp);
            }
            snapping = true;
            try {
                level.setBlock(pos, LaneBoardRegistry.SLOPED_BLOCK.get().defaultBlockState(), 3);
                BlockEntity nb = level.getBlockEntity(pos);
                if (nb instanceof SlopedMarkingBlockEntity) {
                    SlopedMarkingBlockEntity sm = (SlopedMarkingBlockEntity) nb;
                    sm.configure(BuiltInRegistries.BLOCK.getKey(mb).toString(), rot, 0.0F, (float) dy);
                    sm.register(level);
                } else {
                    level.setBlock(pos, state, 3);
                }
            } finally {
                snapping = false;
            }
        } catch (Throwable t) {
            snapping = false;
        }
    }
}
