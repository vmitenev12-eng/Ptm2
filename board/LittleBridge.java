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
 * - topAt: высота поверхности в точке (для миниблоков-дороги - по их коробкам, для обычных блоков - по форме коллизии);
 * - sampleAlong: SUB высот поверхности вдоль направления линии через ячейку (ямки и бугорки);
 * - onMarkingPlaced вызывается из RoadMarking.onPlace (хук Patcher2): если разметка поставлена на миниблоки, верх которых
 *   не на уровне верха ячейки (ямка, бугорок, низкая часть), она заменяется на SlopedMarkingBlock, либо
 *   с одной высотой, либо (если вдоль линии высота меняется) с высотой по кускам;
 * - connectable вызывается из RoadMarking.connectableTypeAt (хук Patcher2): линия разметки соединяется с наклонными плитками
 *   (на клиенте хранилище ptm2 недоступно, поэтому смотрим блок-сущность плитки), в том числе на ячейку выше
 *   или ниже (подъёмы).
 */
public final class LittleBridge {
    public static final int SUB = SlopedMarkingBlockEntity.SUB;

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

    private static final ThreadLocal<Boolean> SLOPED_RENDER = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IN_CONNECT = ThreadLocal.withInitial(() -> Boolean.FALSE);

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

    /** Верх поверхности в точке колонки: ищем сверху вниз от fromY на depth ячеек, первое найденное покрытие (см. topAt). */
    public static double columnTop(Level level, int cx, int cz, double px, double pz, int fromY, int depth) {
        for (int k = fromY; k > fromY - depth; k--) {
            double t = topAt(level, new BlockPos(cx, k, cz), px, pz);
            if (t == Double.POSITIVE_INFINITY || !Double.isNaN(t)) {
                return t;
            }
        }
        return Double.NaN;
    }

    /**
     * SUB высот поверхности вдоль направления rot (0-7, шаг 45 градусов) через центр ячейки (cx, cz).
     * Отсчёты остаются внутри ячейки (у диагонали - от угла до угла). Элементы: верх, NaN или +Infinity (см. topAt).
     */
    public static double[] sampleAlong(Level level, int cx, int cz, int rot, int fromY) {
        double yaw = Math.toRadians(rot * 45.0D);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double len = (rot % 2 == 0) ? 1.0D : Math.sqrt(2.0D);
        double[] r = new double[SUB];
        for (int i = 0; i < SUB; i++) {
            double u = ((i + 0.5D) / SUB - 0.5D) * len;
            double px = Math.min(cx + 0.999D, Math.max(cx + 0.001D, cx + 0.5D + fx * u));
            double pz = Math.min(cz + 0.999D, Math.max(cz + 0.001D, cz + 0.5D + fz * u));
            r[i] = columnTop(level, cx, cz, px, pz, fromY, 4);
        }
        return r;
    }

    /** Клиентский рендерер наклонной плитки включает флаг на время отрисовки родной линии ptm2. */
    public static void setSlopedRender(boolean value) {
        SLOPED_RENDER.set(value ? Boolean.TRUE : Boolean.FALSE);
    }

    private static boolean slopedConnectable(Level level, BlockPos p, Boolean matchTemporary) {
        BlockEntity be = level.getBlockEntity(p);
        if (be instanceof SlopedMarkingBlockEntity) {
            BlockState ms = ((SlopedMarkingBlockEntity) be).mimicState();
            if (ms != null && ms.getBlock() instanceof RoadMarking) {
                return RoadMarking.connectableTypeAt(level, p, ms, matchTemporary);
            }
        }
        return false;
    }

    /**
     * Вызывается из RoadMarking.connectableTypeAt (хук Patcher2) перед возвратом. orig - исходный результат.
     * Дополнительно считается подключаемой наклонная плитка в этой ячейке или в ячейке на блок выше/ниже
     * (подъёмы), а для плитки, которую рисует наш рендерер, - ещё и обычная разметка на блок выше/ниже.
     */
    public static boolean connectable(boolean orig, Level level, BlockPos pos, BlockState state, Boolean matchTemporary) {
        if (orig || level == null || pos == null || LaneBoardRegistry.SLOPED_BLOCK == null || IN_CONNECT.get()) {
            return orig;
        }
        IN_CONNECT.set(Boolean.TRUE);
        try {
            if (slopedConnectable(level, pos, matchTemporary)) {
                return true;
            }
            boolean fromSloped = SLOPED_RENDER.get();
            for (int d = -1; d <= 1; d += 2) {
                BlockPos q = pos.offset(0, d, 0);
                if (slopedConnectable(level, q, matchTemporary)) {
                    return true;
                }
                if (fromSloped) {
                    BlockState qs = level.getBlockState(q);
                    if (qs.getBlock() instanceof RoadMarking && RoadMarking.connectableTypeAt(level, q, qs, matchTemporary)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            // остаётся исходный результат
        } finally {
            IN_CONNECT.set(Boolean.FALSE);
        }
        return false;
    }

    /**
     * Вызывается из RoadMarking.onPlace (хук Patcher2) на сервере. Если под разметкой ячейка LittleTiles и верх поверхности
     * вдоль линии не ровно на уровне верха ячейки (ямка, бугорок), блок заменяется на наклонную плитку с тем же
     * типом и поворотом: либо с одной высотой, либо с высотами по кускам. Если в ячейке наклонные части
     * (pillar), ничего не меняется.
     */
    public static void onMarkingPlaced(Object self, BlockState state, Level level, BlockPos pos) {
        if (snapping) {
            return;
        }
        try {
            if (level == null || level.isClientSide || LaneBoardRegistry.SLOPED_BLOCK == null || !(self instanceof RoadMarking)) {
                return;
            }
            if (!isTilesEntity(level.getBlockEntity(pos.below()))) {
                return;
            }
            Block mb = state.getBlock();
            int rot = 0;
            Property<?> rp = mb.getStateDefinition().getProperty("rotation");
            if (rp instanceof IntegerProperty) {
                rot = state.getValue((IntegerProperty) rp);
            }
            double[] tops = sampleAlong(level, pos.getX(), pos.getZ(), rot, pos.getY() - 1);
            double max = Double.NEGATIVE_INFINITY;
            double min = Double.POSITIVE_INFINITY;
            for (double t : tops) {
                if (Double.isNaN(t) || t == Double.POSITIVE_INFINITY) {
                    return;
                }
                max = Math.max(max, t);
                min = Math.min(min, t);
            }
            double top = pos.getY();
            if (max > top - 0.03D && max - min < 0.02D) {
                return;
            }
            if (max - top > 0.5D || min - top < -3.0D) {
                return;
            }
            float dy = 0.0F;
            float[] hs = null;
            if (max - min < 0.02D) {
                dy = (float) ((max + min) / 2.0D - top);
            } else {
                hs = new float[SUB];
                for (int i = 0; i < SUB; i++) {
                    hs[i] = (float) (tops[i] - top);
                }
            }
            snapping = true;
            try {
                level.setBlock(pos, LaneBoardRegistry.SLOPED_BLOCK.get().defaultBlockState(), 3);
                BlockEntity nb = level.getBlockEntity(pos);
                if (nb instanceof SlopedMarkingBlockEntity) {
                    SlopedMarkingBlockEntity sm = (SlopedMarkingBlockEntity) nb;
                    sm.configure(BuiltInRegistries.BLOCK.getKey(mb).toString(), rot, 0.0F, dy, hs);
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
