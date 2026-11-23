package com.rinventor.ptm2.fixes.board;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

/**
 * Рисует обычную модель блока разметки ptm2, опущенную на dy и наклонённую на pitch вокруг горизонтальной оси,
 * перпендикулярной направлению разметки (направление - поворот 0-7 с шагом 45 градусов, как у блоков ptm2).
 * Пока в руке разметочный карандаш, поверх плитки рисуется контур: зелёный - данные дошли до клиента,
 * красный - данных нет (модель разметки не выбрана).
 */
public class SlopedMarkingRenderer implements BlockEntityRenderer<SlopedMarkingBlockEntity> {
    private static boolean holdingPencil() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof LineMarkerItem;
    }

    public void render(SlopedMarkingBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffer, int light, int overlay) {
        BlockState st = be.mimicState();
        boolean debug = holdingPencil();
        if (st == null && !debug) {
            return;
        }
        ps.pushPose();
        ps.translate(0.0D, be.getDy() + 0.02D, 0.0D);
        float pitch = be.getPitch();
        if (Math.abs(pitch) > 0.01F) {
            double yaw = Math.toRadians(be.getRot() * 45.0D);
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            // ось поворота: (направление) x (вверх) = (-fz, 0, fx); положительный угол поднимает конец по направлению
            ps.translate(0.5D, 0.0D, 0.5D);
            ps.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(pitch), (float) -fz, 0.0F, (float) fx));
            ps.translate(-0.5D, 0.0D, -0.5D);
        }
        if (debug) {
            float r = st != null ? 0.1F : 1.0F;
            float g = st != null ? 1.0F : 0.1F;
            LevelRenderer.renderLineBox(ps, buffer.getBuffer(RenderType.lines()), 0.0D, 0.0D, 0.0D, 1.0D, 0.06D, 1.0D, r, g, 0.1F, 1.0F);
        }
        if (st != null) {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(st, ps, buffer, light, overlay);
        }
        ps.popPose();
    }
}
