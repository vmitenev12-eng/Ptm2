package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.platform.PlatformRenderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Клиентская часть: открытие окна настройки и регистрация рендереров. Вызывается из общего кода только на клиенте. */
public final class LaneBoardClient {
    private LaneBoardClient() {
    }

    public static void open(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        String lanes = "straight";
        if (mc.level != null) {
            BlockEntity be = mc.level.getBlockEntity(pos);
            if (be instanceof LaneBoardBlockEntity) {
                lanes = ((LaneBoardBlockEntity) be).getLanes();
            }
        }
        mc.setScreen(new LaneBoardScreen(pos, lanes));
    }

    public static void registerRenderer(PlatformRenderRegistry registry) {
        registry.registerBlockEntityRenderer(LaneBoardRegistry.ENTITY.get(), ctx -> new LaneBoardRenderer());
        registry.registerBlockEntityRenderer(LaneBoardRegistry.SLOPED_ENTITY.get(), ctx -> new SlopedMarkingRenderer());
    }
}
