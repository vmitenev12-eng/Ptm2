package com.rinventor.ptm2.fixes.board;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;

/** Клиентская часть карандаша: счётчик длины линии от точки A до блока, на который смотрит игрок. Вызывается только на клиенте. */
public final class LineMarkerClient {
    private LineMarkerClient() {
    }

    public static void preview(ItemStack stack, Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("ax")) {
            return;
        }
        HitResult hr = mc.hitResult;
        if (!(hr instanceof BlockHitResult) || hr.getType() != HitResult.Type.BLOCK) {
            return;
        }
        Vec3 a = new Vec3(tag.getDouble("ax"), tag.getDouble("ay"), tag.getDouble("az"));
        LineMarkerItem.Plan pl = LineMarkerItem.plan(a, hr.getLocation());
        String text;
        if (pl == null) {
            text = "A\u2192B: точка B слишком близко к A";
        } else {
            int shown = Math.min(pl.n, LineMarkerItem.MAX_CELLS);
            text = "A\u2192B: " + shown + " бл." + (pl.n > LineMarkerItem.MAX_CELLS ? " (максимум)" : "")
                + ", уклон " + String.format(Locale.ROOT, "%.1f", pl.pitch) + "\u00b0";
        }
        player.displayClientMessage(Component.literal(text), true);
    }
}
