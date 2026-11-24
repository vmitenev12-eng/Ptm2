package com.rinventor.ptm2.fixes.board;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
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
 * рисуется его настоящая блок-сущность родным рендерером (с флагом LittleBridge.setSlopedRender, чтобы
 * линия соединялась с соседями на блок выше/ниже); иначе - обычная модель блока.
 * Если у плитки есть массив высот hs, линия ptm2 рисуется кусками (растянутая вдоль направления и сдвинутая),
 * каждый кусок на своей высоте, а между кусками разной высоты (и на границе со следующей ячейкой, если
 * известна её высота) рисуется вертикальная полоска линии по стенке ямки/бугорка.
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
        if (hs == null && Math.abs(be.getPitch()) < 0.01F && !Float.isNaN(be.getHNext())) {
            // плоская плитка с высотой следующей ячейки - рисуем как куски (для стенки на границе)
            hs = new float[SlopedMarkingBlockEntity.SUB];
            Arrays.fill(hs, be.getDy());
        }

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
                renderInner(inner, ps, buffer, light, overlay);
            }
        } else {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(st, ps, buffer, light, overlay);
        }
        ps.popPose();
    }

    /** Родной рендерер линии ptm2; на время вызова включён флаг «рисуется наклонная плитка» для соединений. */
    private static void renderInner(BlockEntity inner, PoseStack ps, MultiBufferSource buffer, int light, int overlay) {
        LittleBridge.setSlopedRender(true);
        try {
            Minecraft.getInstance().getBlockEntityRenderDispatcher().renderItem(inner, ps, buffer, light, overlay);
        } finally {
            LittleBridge.setSlopedRender(false);
        }
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
     * Рисует линию кусками: соседние отсчёты с одинаковой высотой объединяются в один кусок, между кусками
     * разной высоты рисуется вертикальная стенка. Если inner == null, вместо разметки рисуется контур
     * (отладка) цветом (r, g, 0.1).
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
                renderInner(inner, ps, buffer, light, overlay);
            } else {
                LevelRenderer.renderLineBox(ps, buffer.getBuffer(RenderType.lines()), 0.0D, 0.0D, 0.0D, 1.0D, 0.06D, 1.0D, r, g, 0.1F, 1.0F);
            }
            ps.popPose();
            if (inner != null && j < sub) {
                drawWall(ps, buffer, light, overlay, inner, be.getRot(), (j / (double) sub - 0.5D) * len, hs[i], hs[j]);
            }
            i = j;
        }
        if (inner != null && !Float.isNaN(be.getHNext())) {
            drawWall(ps, buffer, light, overlay, inner, be.getRot(), 0.5D * len, hs[sub - 1], be.getHNext());
        }
    }

    /**
     * Вертикальная полоска линии на стенке между высотами hA (по линии до границы) и hB (после). u - положение границы
     * вдоль направления от центра ячейки. Плитка разворачивается на 90 градусов и растягивается на перепад, смещается
     * на 2 см в сторону более низкого участка (в воздух ямки), чтобы не сливаться со стенкой.
     */
    private static void drawWall(PoseStack ps, MultiBufferSource buffer, int light, int overlay, BlockEntity inner, int rot, double u, float hA, float hB) {
        float d = hB - hA;
        if (Math.abs(d) < 0.03F) {
            return;
        }
        double yaw = Math.toRadians(rot * 45.0D);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double eps = d < 0.0F ? 0.02D : -0.02D;
        ps.pushPose();
        ps.translate(0.5D + fx * (u + eps), (hA + hB) / 2.0D, 0.5D + fz * (u + eps));
        double tilt = d < 0.0F ? -Math.PI / 2.0D : Math.PI / 2.0D;
        ps.mulPose(new Quaternionf().rotationAxis((float) tilt, (float) -fz, 0.0F, (float) fx));
        ps.mulPose(new Quaternionf().rotationY((float) -yaw));
        ps.scale(1.0F, 1.0F, Math.abs(d));
        ps.mulPose(new Quaternionf().rotationY((float) yaw));
        ps.translate(-0.5D, 0.0D, -0.5D);
        renderInner(inner, ps, buffer, light, overlay);
        ps.popPose();
    }
}
