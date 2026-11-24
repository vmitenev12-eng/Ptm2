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
 * Если у плитки есть массив высот hs, линия ptm2 рисуется кусками (растянутая вдоль направления и сдвинутая),
 * каждый кусок на своей высоте (ямки и бугорки).
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
        float[] hs = be.getHeights();
        boolean entityBlock = st != null && st.getBlock() instanceof EntityBlock;

        if (debug) {
            // контуры: один на кусок или один на всю плитку
            float r = st != null ? 0.1F : 1.0F;
            float g = st != null ? 1.0F : 0.1F;
            if (hs != null) {
                drawPieces(be, ps, buffer, light, overlay, hs, null, r, g);
            } else {
                ps.pushPose();
                applyPlate(be, ps);
                LevelRenderer.renderLineBox(ps, buffer.getBuffer(RenderType.lines()), 0.0D, 0.0D, 0.0D, 1.0D, 0.06D, 1.0D, r, g, 0.1F, 1.0F);
                ps.popPose();
            }
        }
        if (st == null) {
            return;
        }
        if (hs != null && entityBlock) {
            BlockEntity inner = be.mimicEntity();
            if (inner != null) {
                drawPieces(be, ps, buffer, light, overlay, hs, inner, 0.0F, 0.0F);
            }
            return;
        }
        ps.pushPose();
        if (hs != null) {
            // для блоков без блок-сущности (стрелки и т.п.) — целиком, на высоте середины
            ps.translate(0.0D, hs[hs.length / 2] + 0.02D, 0.0D);
        } else {
            applyPlate(be, ps);
        }
        if (entityBlock) {
            BlockEntity inner = be.mimicEntity();
            if (inner != null) {
                Minecraft.getInstance().getBlockEntityRenderDispatcher().renderItem(inner, ps, buffer, light, overlay);
            }
        } else {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(st, ps, buffer, light, overlay);
        }
        ps.popPose();
    }

    /** Смещение и наклон целой плитки (без разбиения на куски). */
    private static void applyPlate(SlopedMarkingBlockEntity be, PoseStack ps) {
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
    }

    /**
     * Рисует линию кусками: соседние отсчёты с одинаковой высотой объединяются в один кусок. Если inner == null,
     * вместо разметки рисуется контур (отладка) цветом (r, g, 0.1).
     */
    private static void drawPieces(SlopedMarkingBlockEntity be, PoseStack ps, MultiBufferSource buffer, int light, int overlay,
        float[] hs, BlockEntity inner, float r, float g) {
        int sub = hs.length;
        double yaw = Math.toRadians(be.getRot() * 45.0D);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double len = (be.getRot() % 2 == 0) ? 1.0D : Math.sqrt(2.0D);
        int i = 0;
        while (i < sub) {
            int j = i + 1;
            while (j < sub && Math.abs(hs[j] - hs[i]) < 0.02F) {
                j++;
            }
            double mid = ((i + j) / 2.0D / sub - 0.5D) * len;
            double frac = (double) (j - i) / sub;
            ps.pushPose();
            ps.translate(0.5D + fx * mid, hs[i] + 0.02D, 0.5D + fz * mid);
            ps.mulPose(new Quaternionf().rotationY((float) -yaw));
            ps.scale(1.0F, 1.0F, (float) frac);
            ps.mulPose(new Quaternionf().rotationY((float) yaw));
            ps.translate(-0.5D, 0.0D, -0.5D);
            if (inner != null) {
                Minecraft.getInstance().getBlockEntityRenderDispatcher().renderItem(inner, ps, buffer, light, overlay);
            } else {
                LevelRenderer.renderLineBox(ps, buffer.getBuffer(RenderType.lines()), 0.0D, 0.0D, 0.0D, 1.0D, 0.06D, 1.0D, r, g, 0.1F, 1.0F);
            }
            ps.popPose();
            i = j;
        }
    }
}
