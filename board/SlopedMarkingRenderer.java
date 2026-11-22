package com.rinventor.ptm2.fixes.board;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

/**
 * Рисует обычную модель блока разметки ptm2, опущенную на dy и наклонённую на pitch вокруг горизонтальной оси,
 * перпендикулярной направлению разметки (направление - поворот 0-7 с шагом 45 градусов, как у блоков ptm2).
 */
public class SlopedMarkingRenderer implements BlockEntityRenderer<SlopedMarkingBlockEntity> {
    public void render(SlopedMarkingBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffer, int light, int overlay) {
        BlockState st = be.mimicState();
        if (st == null) {
            return;
        }
        ps.pushPose();
        ps.translate(0.0D, be.getDy() + 0.005D, 0.0D);
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
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(st, ps, buffer, light, overlay);
        ps.popPose();
    }
}
