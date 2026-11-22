package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.core.init.ModTabs;
import com.rinventor.ptm2.engine.base.PTMBlock;
import com.rinventor.ptm2.engine.computing.Rotation8;
import com.rinventor.ptm2.objects.blocks.RoadMarking;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
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
 * Тип разметки: блок разметки во второй руке, либо выбранный раньше (Shift+ПКМ по готовой разметке).
 * Направление - ближайшее из 8 (шаг 45 градусов). Если поверхность между A и B наклонная (pillar) или лежит не на
 * верхе ячейки, ставится наклонная разметка, иначе - обычный блок. Shift+ПКМ не по разметке сбрасывает точку A.
 */
public class LineMarkerItem extends Item {
    private static final int MAX_CELLS = 160;

    public LineMarkerItem(Item.Properties props) {
        super(props);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Разметочный карандаш (A\u2192B)");
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
            if (cs.getBlock() instanceof RoadMarking) {
                picked = cs.getBlock();
            } else if (cs.getBlock() instanceof SlopedMarkingBlock) {
                BlockEntity be = level.getBlockEntity(clicked);
                if (be instanceof SlopedMarkingBlockEntity) {
                    picked = ((SlopedMarkingBlockEntity) be).mimicBlock();
                }
            }
            if (picked != null) {
                tag.putString("mark", BuiltInRegistries.BLOCK.getKey(picked).toString());
                say(player, "Тип разметки выбран: " + picked.getName().getString());
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
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        if (Math.hypot(dx, dz) < 0.9D) {
            say(player, "Точки A и B слишком близко");
            return;
        }
        // Направление как у взгляда игрока в ptm2: yaw 0 = на юг (+Z), поворот 0-7 по 45 градусов
        double yawDeg = Math.toDegrees(Math.atan2(-dx, dz));
        int rot = Rotation8.get(yawDeg);
        double ryaw = Math.toRadians(rot * 45.0D);
        double fx = -Math.sin(ryaw);
        double fz = Math.cos(ryaw);
        double along = dx * fx + dz * fz;
        double slope = (b.y - a.y) / along;
        double pitch = Math.toDegrees(Math.atan(slope));
        if (Math.abs(pitch) > 60.0D) {
            say(player, "Слишком крутой уклон");
            return;
        }
        double step = (rot % 2 == 0) ? 1.0D : Math.sqrt(2.0D);
        int n = Math.max(1, (int) Math.round(along / step) + 1);
        if (n > MAX_CELLS) {
            n = MAX_CELLS;
        }
        int sx = (int) Math.round(fx);
        int sz = (int) Math.round(fz);
        int cx0 = (int) Math.floor(a.x);
        int cz0 = (int) Math.floor(a.z);
        String id = BuiltInRegistries.BLOCK.getKey(mb).toString();
        int flat = 0;
        int sloped = 0;
        int skipped = 0;
        for (int i = 0; i < n; i++) {
            int cx = cx0 + sx * i;
            int cz = cz0 + sz * i;
            double sc = (cx + 0.5D - a.x) * fx + (cz + 0.5D - a.z) * fz;
            double yc = a.y + slope * sc;
            int cy = (int) Math.floor(yc - 0.001D) + 1;
            BlockPos pos = new BlockPos(cx, cy, cz);
            if (!canPlace(level, pos)) {
                skipped++;
                continue;
            }
            double off = yc - cy;
            if (Math.abs(pitch) < 0.3D && Math.abs(off) < 0.03D) {
                level.setBlock(pos, flatState(mb, level, pos, rot), 3);
                flat++;
            } else {
                level.setBlock(pos, LaneBoardRegistry.SLOPED_BLOCK.get().defaultBlockState(), 3);
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof SlopedMarkingBlockEntity) {
                    SlopedMarkingBlockEntity sm = (SlopedMarkingBlockEntity) be;
                    sm.configure(id, rot, (float) pitch, (float) off);
                    sm.register(level);
                    sloped++;
                } else {
                    skipped++;
                }
            }
        }
        say(player, "Линия: обычных " + flat + ", наклонных " + sloped + (skipped > 0 ? ", пропущено " + skipped : "")
            + ", уклон " + String.format("%.1f", pitch) + "\u00b0");
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
