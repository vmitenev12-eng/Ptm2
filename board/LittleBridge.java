package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.objects.blocks.RoadBlock;
import com.rinventor.ptm2.objects.blocks.RoadSlab;
import com.rinventor.ptm2.objects.blocks.SidewalkBlock;
import com.rinventor.ptm2.objects.blocks.SidewalkSlab;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Мягкая поддержка LittleTiles: ячейка из миниблоков (BETiles), в которой есть части-дорога или части-тротуар,
 * для ptm2 выглядит как соответствующий блок дороги или тротуара. Подмена делается в PTMBlock.getBlock
 * (хук ставит Patcher2), поэтому все проверки вида "под разметкой дорога", посадка транспорта, соединение точек
 * и пешеходы начинают считать такую ячейку дорогой. Без мода LittleTiles класс ничего не делает.
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

    private static Block compute(Block original, LevelAccessor level, BlockPos pos) throws ReflectiveOperationException {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !be.getClass().getName().endsWith(".BETiles")) {
            return original;
        }
        Method all = allTilesMethod;
        if (all == null) {
            all = be.getClass().getMethod("allTiles");
            allTilesMethod = all;
        }
        Block road = null;
        Block walk = null;
        int n = 0;
        for (Object pr : (Iterable<?>) all.invoke(be)) {
            if (++n > MAX_PARTS) {
                break;
            }
            Object tile = ((Map.Entry<?, ?>) pr).getValue();
            Method gs = getStateMethod;
            if (gs == null) {
                gs = tile.getClass().getMethod("getState");
                getStateMethod = gs;
            }
            Block pb = ((BlockState) gs.invoke(tile)).getBlock();
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
}
