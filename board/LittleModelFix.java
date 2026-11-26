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
 * Тротуары и другие блоки ptm2 описаны моделями с собственными элементами, у которых нет cullface. LittleTiles берёт грани части
 * через model.getQuads(state, сторона, ...), а грани без cullface возвращаются только для стороны null, так что часть не рисуется
 * (контур и хитбокс есть, картинки нет). Обёртка: если запрос идёт из потока рендера LittleTiles и для стороны граней нет,
 * отдаётся грани без cullface, смотрящие в эту сторону. Обычная отрисовка мира не меняется (иначе грани рисовались бы
 * дважды). Регистрация - по аннотации @Mod.EventBusSubscriber на шине мода (класс входит в итоговый jar).
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
            if ("inventory".equals(((ModelResourceLocation) key).getVariant())) {
                continue;
            }
            BakedModel m = e.getValue();
            if (m != null && !(m instanceof NoCullWrapper)) {
                e.setValue(new NoCullWrapper(m));
            }
        }
    }

    private static final class NoCullWrapper extends BakedModelWrapper<BakedModel> {
        NoCullWrapper(BakedModel original) {
            super(original);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
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
