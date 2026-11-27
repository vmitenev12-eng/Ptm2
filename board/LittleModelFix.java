package com.rinventor.ptm2.fixes.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Обёртка моделей ptm2 для LittleTiles:
 * 1) грани без cullface: LittleTiles вызывает model.getQuads(state, сторона, ...) и ожидает, что у грани есть cullface,
 *    иначе грань теряется (дырки в тротуарах из миниблоков);
 * 2) плитки тротуаров (модели с shape не 0) внутри LittleTiles рисуются плоскими (модель shape=0): замороженный shape
 *    давал бордюр на всей плитке, настоящий бордюр по краям рисует CurbOverlay. Обычные блоки не затрагиваются.
 * Работает через @Mod.EventBusSubscriber (подхватывается и из отдельного jar).
 */
@Mod.EventBusSubscriber(modid = "ptm2", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LittleModelFix {
    private static final ClassValue<Boolean> LITTLE_THREAD = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return c.getName().startsWith("team.creative.littletiles.");
        }
    };

    private LittleModelFix() {
    }

    @SubscribeEvent
    public static void onModifyBaking(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        for (Map.Entry<ResourceLocation, BakedModel> e : models.entrySet()) {
            ResourceLocation key = e.getKey();
            if (!"ptm2".equals(key.getNamespace()) || !(key instanceof ModelResourceLocation)) {
                continue;
            }
            String variant = ((ModelResourceLocation) key).getVariant();
            if ("inventory".equals(variant)) {
                continue;
            }
            BakedModel m = e.getValue();
            if (m != null && !(m instanceof NoCullWrapper)) {
                BakedModel flat = null;
                if (CurbOverlay.ENABLED && key.getPath().contains("sidewalk") && variant.contains("shape=") && !variant.contains("shape=0")) {
                    String alt = variant.replaceAll("shape=\\d+", "shape=0");
                    flat = models.get(new ModelResourceLocation(key.getNamespace(), key.getPath(), alt));
                }
                e.setValue(new NoCullWrapper(m, flat));
            }
        }
    }

    private static final class NoCullWrapper extends BakedModelWrapper<BakedModel> {
        private final BakedModel flat;

        NoCullWrapper(BakedModel original, BakedModel flat) {
            super(original);
            this.flat = flat;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
            if (flat != null && LITTLE_THREAD.get(Thread.currentThread().getClass())) {
                return flat.getQuads(state, side, rand, data, renderType);
            }
            List<BakedQuad> list = super.getQuads(state, side, rand, data, renderType);
            if (side != null && list.isEmpty() && LITTLE_THREAD.get(Thread.currentThread().getClass())) {
                List<BakedQuad> out = null;
                for (BakedQuad q : super.getQuads(state, null, rand, data, renderType)) {
                    if (q.getDirection() == side) {
                        if (out == null) {
                            out = new ArrayList<>();
                        }
                        out.add(q);
                    }
                }
                if (out != null) {
                    return out;
                }
            }
            return list;
        }
    }
}
