package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.core.init.ModTabs;
import com.rinventor.ptm2.engine.base.PTMBlock;
import com.rinventor.ptm2.engine.computing.Rotation8;
import com.rinventor.ptm2.objects.blocks.RoadMarking;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * Разметочный карандаш: ПКМ в точке A, затем ПКМ в точке B - между ними кладётся линия выбранной разметки.
 * Пока точка A задана, на экране (в строке над панелью предметов) показывается длина линии до блока, на который смотрит игрок.
 * Тип разметки: блок разметки во второй руке, либо выбранный раньше (Shift+ПКМ по готовой разметке).
 * Направление - ближайшее из 8 (шаг 45 градусов). Высота поверхности: если в ячейке нет наклонных
 * частей (pillar, диагональ), высоты берутся по верху миниблоков в 8 точках вдоль линии (ямки, бугорки), иначе -
 * линейно между A и B. Ячейка для плитки выбирается над самой высокой точкой поверхности в ячейке, чтобы
 * на подъёмах ячейка не оказывалась занятой краем того же подъёма. Для плитки запоминается высота
 * первого куска следующей ячейки (hNext) — по ней рисуется стенка на границе ячеек.
 * Shift+ПКМ по готовой разметке выбирает её тип (для наклонной плитки ещё показывает её данные), Shift+ПКМ не по разметке сбрасывает точку A.
 */
public class LineMarkerItem extends Item {
    static final int MAX_CELLS = 160;

    /** Расчёт линии по двум точкам. */
    static final class Plan {
        final int rot;
        final int n;
        final double pitch;
        final double slope;
        final double fx;
        final double fz;

        Plan(int rot, int n, double pitch, double slope, double fx, double fz) {
            this.rot = rot;
            this.n = n;
            this.pitch = pitch;
            this.slope = slope;
            this.fx = fx;
            this.fz = fz;
        }
    }

    public LineMarkerItem(Item.Properties props) {
        super(props);
    }

    static Plan plan(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        if (Math.hypot(dx, dz) < 0.9D) {
            return null;
        }
        // Направление как у взгляда игрока в ptm2: yaw 0 = на юг (+Z), поворот 0-7 по 45 градусов
        int rot = Rotation8.get(Math.toDegrees(Math.atan2(-dx, dz)));
        double ryaw = Math.toRadians(rot * 45.0D);
        double fx = -Math.sin(ryaw);
        double fz = Math.cos(ryaw);
        double along = dx * fx + dz * fz;
        double slope = (b.y - a.y) / along;
        double step = (rot % 2 == 0) ? 1.0D : Math.sqrt(2.0D);
        int n = Math.max(1, (int) Math.round(along / step) + 1);
        return new Plan(rot, n, Math.toDegrees(Math.atan(slope)), slope, fx, fz);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Разметочный карандаш (A\u2192B)");
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide && selected && entity instanceof Player) {
            LineMarkerClient.preview(stack, (Player) entity);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        CompoundTag tag = ctx.getItemInHand().getOrCreateTag();
        BlockPos clicked = ctx.getClickedPos();
        BlockState cs = level.getBlockState(clicked);

        if (player.isShiftKeyDown()) {
            Block picked = null;
            String info = "";
            if (cs.getBlock() instanceof RoadMarking) {
                picked = cs.getBlock();
            } else if (cs.getBlock() instanceof SlopedMarkingBlock) {
                BlockEntity be = level.getBlockEntity(clicked);
                if (be instanceof SlopedMarkingBlockEntity) {
                    SlopedMarkingBlockEntity sm = (SlopedMarkingBlockEntity) be;
                    picked = sm.mimicBlock();
                    info = " [наклонная: " + sm.describe() + "]";
                } else {
                    info = " [наклонная плитка без данных]";
                }
            }
            if (picked != null) {
                tag.putString("mark", BuiltInRegistries.BLOCK.getKey(picked).toString());
                say(player, "Тип разметки выбран: " + picked.getName().getString() + info);
            } else if (!info.isEmpty()) {
                say(player, "Плитка" + info);
            } else {
                tag.remove("ax");
                tag.remove("ay");
                tag.remove("az");
                say(player, "Точка A сброшена");
            }
            return InteractionResult.SUCCESS;
        }

        Vec3 p = ctx.getClickLocation();
        if (!tag.contains("ax")) {
            if (markingType(player, tag) == null) {
                say(player, "Возьмите блок разметки во вторую руку или Shift+ПКМ по готовой разметке");
                return InteractionResult.SUCCESS;
            }
            tag.putDouble("ax", p.x);
            tag.putDouble("ay", p.y);
            tag.putDouble("az", p.z);
            say(player, "Точка A отмечена, выберите точку B");
            return InteractionResult.SUCCESS;
        }

        Vec3 a = new Vec3(tag.getDouble("ax"), tag.getDouble("ay"), tag.getDouble("az"));
        tag.remove("ax");
        tag.remove("ay");
        tag.remove("az");
        Block mb = markingType(player, tag);
        if (mb == null) {
            say(player, "Не выбран тип разметки");
            return InteractionResult.SUCCESS;
        }
        drawLine(level, player, mb, a, p);
        return InteractionResult.SUCCESS;
    }

    private static Block markingType(Player player, CompoundTag tag) {
        Item off = player.getOffhandItem().getItem();
        if (off instanceof BlockItem && ((BlockItem) off).getBlock() instanceof RoadMarking) {
            return ((BlockItem) off).getBlock();
        }
        if (tag.contains("mark")) {
            try {
                Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(tag.getString("mark")));
                if (b instanceof RoadMarking) {
                    return b;
                }
            } catch (RuntimeException e) {
                // неверный идентификатор - тип не выбран
            }
        }
        return null;
    }

    private static void drawLine(Level level, Player player, Block mb, Vec3 a, Vec3 b) {
        Plan pl = plan(a, b);
        if (pl == null) {
            say(player, "Точки A и B слишком близко");
            return;
        }
        if (Math.abs(pl.pitch) > 60.0D) {
            say(player, "Слишком крутой уклон");
            return;
        }
        int n = Math.min(pl.n, MAX_CELLS);
        int sx = (int) Math.round(pl.fx);
        int sz = (int) Math.round(pl.fz);
        int cx0 = (int) Math.floor(a.x);
        int cz0 = (int) Math.floor(a.z);
        String id = BuiltInRegistries.BLOCK.getKey(mb).toString();
        double ext = (Math.abs(pl.fx) + Math.abs(pl.fz)) / 2.0D;
        int flat = 0;
        int sloped = 0;
        int skipped = 0;
        int geo = 0;
        int pieces = 0;
        for (int i = 0; i < n; i++) {
            int cx = cx0 + sx * i;
            int cz = cz0 + sz * i;
            double sc = (cx + 0.5D - a.x) * pl.fx + (cz + 0.5D - a.z) * pl.fz;
            double yc = a.y + pl.slope * sc;
            int base = (int) Math.floor(yc - 0.001D);

            // высоты поверхности вдоль линии через ячейку (ямки и бугорки миниблоков); если в ячейке есть
            // наклонная часть (pillar) или поверхность не найдена - берём линейный уклон от A до B
            double[] tops = LittleBridge.sampleAlong(level, cx, cz, pl.rot, base + 2);
            boolean tilted = false;
            boolean none = false;
            double maxT = Double.NEGATIVE_INFINITY;
            double minT = Double.POSITIVE_INFINITY;
            for (double t : tops) {
                if (t == Double.POSITIVE_INFINITY) {
                    tilted = true;
                } else if (Double.isNaN(t) || Math.abs(t - yc) > 1.6D) {
                    none = true;
                } else {
                    maxT = Math.max(maxT, t);
                    minT = Math.min(minT, t);
                }
            }

            int cy;
            double off;
            double pitchUse;
            float[] hs = null;
            float hNext = Float.NaN;
            if (!tilted && !none) {
                cy = (int) Math.floor(maxT - 0.001D) + 1;
                pitchUse = 0.0D;
                geo++;
                if (maxT - minT < 0.02D) {
                    off = (maxT + minT) / 2.0D - cy;
                } else {
                    off = 0.0D;
                    hs = new float[LittleBridge.SUB];
                    for (int j = 0; j < hs.length; j++) {
                        hs[j] = (float) (tops[j] - cy);
                    }
                }
                if (i + 1 < n) {
                    // высота первого куска следующей ячейки - для стенки на границе ячеек
                    double[] nt = LittleBridge.sampleAlong(level, cx + sx, cz + sz, pl.rot, base + 3);
                    double t0 = nt[0];
                    if (!Double.isNaN(t0) && t0 != Double.POSITIVE_INFINITY && Math.abs(t0 - yc) <= 1.6D) {
                        hNext = (float) (t0 - cy);
                    }
                }
            } else {
                // ячейка над самой высокой точкой поверхности в ячейке (край подъёма может заходить выше центра)
                cy = (int) Math.floor(yc + Math.abs(pl.slope) * ext - 0.001D) + 1;
                off = yc - cy;
                pitchUse = pl.pitch;
            }

            BlockPos pos = new BlockPos(cx, cy, cz);
            if (!canPlace(level, pos)) {
                // занято (например краем подъёма) - пробуем ячейку выше
                pos = pos.above();
                off -= 1.0D;
                if (hs != null) {
                    for (int j = 0; j < hs.length; j++) {
                        hs[j] -= 1.0F;
                    }
                }
                if (!Float.isNaN(hNext)) {
                    hNext -= 1.0F;
                }
                if (!canPlace(level, pos)) {
                    skipped++;
                    continue;
                }
            }
            boolean wallAhead = !Float.isNaN(hNext) && Math.abs(hNext - (float) off) > 0.03F && hs == null;
            if (hs == null && !wallAhead && Math.abs(pitchUse) < 0.3D && Math.abs(off) < 0.03D) {
                level.setBlock(pos, flatState(mb, level, pos, pl.rot), 3);
                flat++;
            } else {
                level.setBlock(pos, LaneBoardRegistry.SLOPED_BLOCK.get().defaultBlockState(), 3);
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof SlopedMarkingBlockEntity) {
                    SlopedMarkingBlockEntity sm = (SlopedMarkingBlockEntity) be;
                    sm.configure(id, pl.rot, (float) pitchUse, (float) off, hs, hNext);
                    sm.register(level);
                    sloped++;
                    if (hs != null) {
                        pieces++;
                    }
                } else {
                    skipped++;
                }
            }
        }
        say(player, "Линия: обычных " + flat + ", наклонных " + sloped + (skipped > 0 ? ", пропущено " + skipped : "")
            + (geo > 0 ? ", по миниблокам " + geo : "") + (pieces > 0 ? " (кусками " + pieces + ")" : "")
            + ", уклон " + String.format(Locale.ROOT, "%.1f", pl.pitch) + "\u00b0");
    }

    private static boolean canPlace(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        Block b = s.getBlock();
        return s.isAir() || b instanceof RoadMarking || b instanceof SlopedMarkingBlock || s.canBeReplaced();
    }

    private static BlockState flatState(Block mb, Level level, BlockPos pos, int rot) {
        BlockState st = mb.defaultBlockState();
        Property<?> rp = mb.getStateDefinition().getProperty("rotation");
        if (rp instanceof IntegerProperty) {
            st = st.setValue((IntegerProperty) rp, rot);
        }
        Property<?> sp = mb.getStateDefinition().getProperty("slab");
        if (sp instanceof BooleanProperty) {
            st = st.setValue((BooleanProperty) sp, PTMBlock.onSlab(level, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ()));
        }
        return st;
    }

    private static void say(Player player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }

    /** Добавляет карандаш во вкладку знаков (вызывается патчем из ModTabs.addCreative). */
    @SuppressWarnings("unchecked")
    public static void creativeExtra(Object tab, Object accept) {
        try {
            if (tab != ModTabs.SIGNS_TAB.get() || LaneBoardRegistry.LINE_TOOL == null) {
                return;
            }
            ((Consumer<Object>) accept).accept(LaneBoardRegistry.LINE_TOOL.get());
        } catch (Throwable t) {
            // вкладка останется без карандаша, игра работает
        }
    }
}
