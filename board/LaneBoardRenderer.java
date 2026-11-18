package com.rinventor.ptm2.fixes.board;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.rinventor.ptm2.engine.base.PTMBlock;
import com.rinventor.ptm2.engine.graphics.Colors;
import com.rinventor.ptm2.engine.graphics.ScaledWorldTexture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Рисует табло: белая рамка, синее поле и значки полос из существующих знаков мода (sign_lane_*_blue).
 * Размер: одна полоса - блок 1x1, две и больше - по полблока на полосу и высота полблока. Табло центрируется на блоке.
 * Положение и поворот плиты повторяют модели знаков (поворот состояния блока и поворот элемента на 45 градусов).
 */
public class LaneBoardRenderer implements BlockEntityRenderer<LaneBoardBlockEntity> {
    private static final ResourceLocation PLAIN = new ResourceLocation("ptm2", "textures/block/sign_lane_straight_blue.png");
    private final Map<String, ScaledWorldTexture> icons = new HashMap<>();

    public void render(LaneBoardBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffer, int light, int overlay) {
        List<String> lanes = LaneBoardData.parse(be.getLanes());
        int n = lanes.size();
        BlockState st = be.getBlockState();
        int rot = PTMBlock.getIProperty("rotation", st);
        boolean wall = PTMBlock.getBProperty("wall", st);
        boolean odd = (rot % 2) != 0;
        double cx = odd ? 2.8D / 16.0D : 0.5D;
        double zFront = (odd ? (wall ? 21.325D : 29.625D) : (wall ? 15.525D : 21.525D)) / 16.0D;
        double thick = 0.475D / 16.0D;

        double cell = n == 1 ? 1.0D : 0.5D;
        double span = n == 1 ? 1.0D : n * 0.5D;
        double w = n == 1 ? 0.95D : span - 0.05D;
        double h = n == 1 ? 0.95D : 0.45D;
        double icon = n == 1 ? 0.85D : 0.40D;

        ps.pushPose();
        ps.translate(0.5D, 0.0D, 0.5D);
        ps.mulPose(Axis.YP.rotationDegrees(-(rot / 2) * 90.0F));
        ps.translate(-0.5D, 0.0D, -0.5D);
        if (odd) {
            double ox = 0.5D;
            double oz = 15.3625D / 16.0D;
            ps.translate(ox, 0.5D, oz);
            ps.mulPose(Axis.YP.rotationDegrees(-45.0F));
            ps.translate(-ox, -0.5D, -oz);
        }
        // начало координат плиты: центр передней грани; вид с -z, вправо для зрителя - в сторону -x
        ps.translate(cx, 0.5D, zFront);

        // задняя сторона (серая), смотрит в +z
        ScaledWorldTexture back = tex("back", w + 0.04D, h + 0.04D, 0, 0, 0, 0);
        ps.pushPose();
        ps.translate(-(w + 0.04D) / 2.0D, (h + 0.04D) / 2.0D, thick);
        back.render(ps, buffer, 0.0D, 0.0D, 0.0D, 180.0F, 0.0F, Colors.rgb(150), light);
        ps.popPose();

        quad(ps, buffer, tex("rim" + n, w + 0.04D, h + 0.04D, 0, 0, 0, 0), (w + 0.04D) / 2.0D, (h + 0.04D) / 2.0D, -0.0007D, light);
        quad(ps, buffer, tex("field" + n, w, h, 2, 2, 3, 3), w / 2.0D, h / 2.0D, -0.0014D, light);
        for (int i = 0; i < n; i++) {
            double left = span / 2.0D - i * cell - (cell - icon) / 2.0D;
            quad(ps, buffer, icon(lanes.get(i), icon), left, icon / 2.0D, -0.0021D, light);
        }
        ps.popPose();
    }

    private ScaledWorldTexture tex(String key, double w, double h, int u0, int v0, int u1, int v1) {
        String k = key + ":" + w + ":" + h;
        ScaledWorldTexture t = this.icons.get(k);
        if (t == null) {
            t = new ScaledWorldTexture(w, h, PLAIN, u0, v0, u1, v1);
            this.icons.put(k, t);
        }
        return t;
    }

    private ScaledWorldTexture icon(String token, double size) {
        String k = "icon:" + token + ":" + size;
        ScaledWorldTexture t = this.icons.get(k);
        if (t == null) {
            t = new ScaledWorldTexture(size, size, new ResourceLocation("ptm2", "textures/block/sign_lane_" + token + "_blue.png"), 1, 1, 30, 30);
            this.icons.put(k, t);
        }
        return t;
    }

    private void quad(PoseStack ps, MultiBufferSource buffer, ScaledWorldTexture t, double left, double top, double z, int light) {
        ps.pushPose();
        ps.translate(left, top, z);
        t.render(ps, buffer, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, Colors.WHITE, light);
        ps.popPose();
    }
}
