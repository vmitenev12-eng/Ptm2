package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.objects.blocks.RoadBlock;
import com.rinventor.ptm2.objects.blocks.RoadSlab;
import com.rinventor.ptm2.objects.blocks.SidewalkBlock;
import com.rinventor.ptm2.objects.blocks.SidewalkSlab;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Мягкая поддержка LittleTiles: ячейка из миниблоков (BETiles), в которой есть части-дорога или части-тротуар,
 * для ptm2 выглядит как соответствующий блок дороги или тротуара. Подмена делается в PTMBlock.getBlock
 * (хук ставит Patcher2), поэтому все проверки вида "под разметкой дорога", посадка транспорта, соединение точек
 * и пешеходы начинают считать такую ячейку дорогой. Без мода LittleTiles класс ничего не делает.
 * Дополнительно topAt даёт высоту верха миниблоков-дороги в точке (для ямок и бугорков).
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
     * Высота верха частей-дороги/тротуара миниблоков в ячейке cell в точке (px, pz) (мировые координаты).
     * NaN - в ячейке нет миниблоков или их части не покрывают точку;
     * +Infinity - в ячейке есть наклонная (диагональная, pillar) часть-дорога, высоту нужно брать из наклона.
     */
    public static double topAt(Level level, BlockPos cell, double px, double pz) {
        try {
            BlockEntity be = level.getBlockEntity(cell);
            if (!isTilesEntity(be)) {
                return Double.NaN;
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
}
