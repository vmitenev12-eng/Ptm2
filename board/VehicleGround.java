package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.objects.blocks.RoadBlock;
import com.rinventor.ptm2.objects.blocks.RoadSlab;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Высота опоры колёс транспорта над ячейкой из миниблоков (LittleTiles).
 * Родной Vehicle.landingY берёт верх формы коллизии всей ячейки, то есть верх самой высокой части
 * (бордюр, тротуар, декор, угол диагональной плитки), и транспорт "летит". Хук Patcher2 вызывает landingFix
 * после landingY: если найденная высота лежит в ячейке LittleTiles, она заменяется верхом именно частей-дороги
 * под точкой колеса (если он ниже). Без LittleTiles класс ничего не делает.
 */
public final class VehicleGround {
    private static final int MAX_PARTS = 96;
    private static volatile Method allTilesMethod;
    private static volatile Method getStateMethod;
    private static volatile boolean broken;

    private VehicleGround() {
    }

    /** Вызывается из Vehicle.landingY(x, oldY, newY, z) перед возвратом. result - исходный результат (NaN - нет опоры). */
    public static double landingFix(double result, Object vehicle, double x, double oldY, double newY, double z) {
        if (broken || Double.isNaN(result) || !(vehicle instanceof Entity)) {
            return result;
        }
        try {
            Level level = ((Entity) vehicle).level();
            int cy = (int) Math.floor(result - 0.01D);
            BlockPos cell = new BlockPos((int) Math.floor(x), cy, (int) Math.floor(z));
            BlockEntity be = level.getBlockEntity(cell);
            if (be == null || !be.getClass().getName().endsWith(".BETiles")) {
                return result;
            }
            double top = roadTop(be, cell, x, z);
            if (Double.isNaN(top)) {
                return result;
            }
            double minY = Math.min(newY, oldY - 2.0D);
            if (top < result - 0.001D && top >= minY - 0.01D) {
                return top;
            }
            return result;
        } catch (Throwable t) {
            broken = true;
            return result;
        }
    }

    /** Верх частей-дороги (RoadBlock/RoadSlab) под точкой (px, pz); у диагональных плиток берутся их габариты. NaN - нет. */
    private static double roadTop(BlockEntity be, BlockPos cell, double px, double pz) throws ReflectiveOperationException {
        Object grid = be.getClass().getMethod("getGrid").invoke(be);
        int count = grid.getClass().getField("count").getInt(grid);
        double lx = (px - cell.getX()) * count;
        double lz = (pz - cell.getZ()) * count;
        double best = Double.NaN;
        int n = 0;
        for (Object pr : allTiles(be)) {
            if (++n > MAX_PARTS) {
                break;
            }
            Object tile = ((Map.Entry<?, ?>) pr).getValue();
            Block pb = partBlock(tile);
            if (!(pb instanceof RoadBlock) && !(pb instanceof RoadSlab)) {
                continue;
            }
            for (Object box : (Iterable<?>) tile) {
                Class<?> bc = box.getClass();
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
        return best;
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
}
