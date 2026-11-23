package com.rinventor.ptm2.fixes.board;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

/**
 * Рисует блок разметки ptm2, опущенный на dy и наклонённый на pitch вокруг горизонтальной оси, перпендикулярной
 * направлению разметки (направление - поворот 0-7 с шагом 45 градусов, как у блоков ptm2). Вдоль направления
 * плитка растягивается в 1/cos(pitch), чтобы горизонтальная проекция оставалась ровно в ячейку (без зазоров).
 *
 * Если исходный блок разметки - EntityBlock (линии разметки ptm2 рисуются своим рендерером блок-сущности, а не моделью),
 * рисуется его настоящая блок-сущность родным рендерером; иначе - обычная модель блока.
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
            double pr = Math.toRadians(pitch);
            ps.translate(0.5D, 0.0D, 0.5D);
            // ось наклона: (направление) x (вверх) = (-fz, 0, fx); положительный угол поднимает конец по направлению
            ps.mulPose(new Quaternionf().rotationAxis((float) pr, (float) -fz, 0.0F, (float) fx));
            // растяжение вдоль направления: повернуть ось z на направление, растянуть, вернуть
            ps.mulPose(new Quaternionf().rotationY((float) -yaw));
            ps.scale(1.0F, 1.0F, (float) (1.0D / Math.cos(pr)));
            ps.mulPose(new Quaternionf().rotationY((float) yaw));
            ps.translate(-0.5D, 0.0D, -0.5D);
        }
        if (debug) {
            float r = st != null ? 0.1F : 1.0F;
            float g = st != null ? 1.0F : 0.1F;
            LevelRenderer.renderLineBox(ps, buffer.getBuffer(RenderType.lines()), 0.0D, 0.0D, 0.0D, 1.0D, 0.06D, 1.0D, r, g, 0.1F, 1.0F);
        }
        if (st != null) {
            Block mb = st.getBlock();
            if (mb instanceof EntityBlock) {
                BlockEntity inner = be.mimicEntity();
                if (inner != null) {
                    Minecraft.getInstance().getBlockEntityRenderDispatcher().renderItem(inner, ps, buffer, light, overlay);
                }
            } else {
                Minecraft.getInstance().getBlockRenderer().renderSingleBlock(st, ps, buffer, light, overlay);
            }
        }
        ps.popPose();
    }
}
