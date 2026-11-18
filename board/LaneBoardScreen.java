package com.rinventor.ptm2.fixes.board;

import com.rinventor.ptm2.engine.network.ModNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Окно настройки табло: полосы слева направо, добавить и убрать полосу, переставить, выбрать направление из существующих знаков.
 * Сохраняется кнопкой «Готово»; Esc закрывает окно без сохранения.
 */
public class LaneBoardScreen extends Screen {
    private static final int PANEL_W = 330;
    private static final int PANEL_H = 250;
    private static final int CELL = 30;
    private static final int PAL_CELL = 38;
    private static final int PAL_PER_ROW = 7;

    private final BlockPos pos;
    private final List<String> lanes;
    private final Map<String, ResourceLocation> textures = new HashMap<>();
    private int sel;
    private int left;
    private int top;

    public LaneBoardScreen(BlockPos pos, String lanesValue) {
        super(Component.literal("Табло направлений по полосам"));
        this.pos = pos;
        this.lanes = new ArrayList<>(LaneBoardData.parse(lanesValue));
    }

    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
        int by = this.top + 78;
        this.addRenderableWidget(Button.builder(Component.literal("+ Полоса"), b -> this.addLane()).bounds(this.left + 10, by, 76, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("- Полоса"), b -> this.removeLane()).bounds(this.left + 90, by, 76, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("< Влево"), b -> this.moveLane(-1)).bounds(this.left + 170, by, 72, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Вправо >"), b -> this.moveLane(1)).bounds(this.left + 246, by, 74, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> this.done()).bounds(this.left + PANEL_W - 170, this.top + PANEL_H - 30, 80, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> this.onClose()).bounds(this.left + PANEL_W - 85, this.top + PANEL_H - 30, 75, 20).build());
    }

    public boolean isPauseScreen() {
        return false;
    }

    private void addLane() {
        if (this.lanes.size() >= LaneBoardData.MAX_LANES) {
            return;
        }
        this.lanes.add(this.sel + 1, this.lanes.get(this.sel));
        this.sel++;
    }

    private void removeLane() {
        if (this.lanes.size() <= 1) {
            return;
        }
        this.lanes.remove(this.sel);
        if (this.sel >= this.lanes.size()) {
            this.sel = this.lanes.size() - 1;
        }
    }

    private void moveLane(int d) {
        int j = this.sel + d;
        if (j < 0 || j >= this.lanes.size()) {
            return;
        }
        String t = this.lanes.get(this.sel);
        this.lanes.set(this.sel, this.lanes.get(j));
        this.lanes.set(j, t);
        this.sel = j;
    }

    private void done() {
        ModNetwork.sendToServer(new LaneBoardPacket(this.pos, LaneBoardData.join(this.lanes)));
        this.onClose();
    }

    private ResourceLocation tex(String token) {
        ResourceLocation r = this.textures.get(token);
        if (r == null) {
            r = new ResourceLocation("ptm2", "textures/block/sign_lane_" + token + "_blue.png");
            this.textures.put(token, r);
        }
        return r;
    }

    private int laneX(int i) {
        return this.left + (PANEL_W - this.lanes.size() * CELL) / 2 + i * CELL;
    }

    private int laneY() {
        return this.top + 28;
    }

    private int palX(int i) {
        int inRow = Math.min(PAL_PER_ROW, LaneBoardData.TOKENS.length - (i / PAL_PER_ROW) * PAL_PER_ROW);
        return this.left + (PANEL_W - inRow * PAL_CELL) / 2 + (i % PAL_PER_ROW) * PAL_CELL;
    }

    private int palY(int i) {
        return this.top + 128 + (i / PAL_PER_ROW) * (PAL_CELL + 4);
    }

    private void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        g.fill(this.left, this.top, this.left + PANEL_W, this.top + PANEL_H, 0xE0101820);
        this.frame(g, this.left, this.top, PANEL_W, PANEL_H, 0xFF6FA8DC);
        g.drawCenteredString(this.font, this.title, this.left + PANEL_W / 2, this.top + 8, 0xFFFFFF);
        // полосы слева направо, как на табло
        for (int i = 0; i < this.lanes.size(); i++) {
            int x = this.laneX(i);
            int y = this.laneY();
            g.fill(x, y, x + CELL - 2, y + CELL - 2, 0xFF0062BA);
            g.blit(this.tex(this.lanes.get(i)), x + 1, y + 1, CELL - 4, CELL - 4, 0.0F, 0.0F, 32, 32, 32, 32);
            this.frame(g, x, y, CELL - 2, CELL - 2, i == this.sel ? 0xFFFFD84A : 0xFF3A4A5A);
        }
        g.drawCenteredString(this.font, Component.literal("Полоса " + (this.sel + 1) + " из " + this.lanes.size()
            + " (нажмите на полосу, чтобы выбрать)"), this.left + PANEL_W / 2, this.top + 64, 0xCCCCCC);
        g.drawCenteredString(this.font, Component.literal("Направление выбранной полосы:"), this.left + PANEL_W / 2, this.top + 112, 0xCCCCCC);
        for (int i = 0; i < LaneBoardData.TOKENS.length; i++) {
            int x = this.palX(i);
            int y = this.palY(i);
            boolean cur = LaneBoardData.TOKENS[i].equals(this.lanes.get(this.sel));
            g.fill(x, y, x + PAL_CELL - 4, y + PAL_CELL - 4, 0xFF0062BA);
            g.blit(this.tex(LaneBoardData.TOKENS[i]), x + 1, y + 1, PAL_CELL - 6, PAL_CELL - 6, 0.0F, 0.0F, 32, 32, 32, 32);
            this.frame(g, x, y, PAL_CELL - 4, PAL_CELL - 4, cur ? 0xFFFFD84A : 0xFF3A4A5A);
        }
        super.render(g, mx, my, pt);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        for (int i = 0; i < this.lanes.size(); i++) {
            int x = this.laneX(i);
            int y = this.laneY();
            if (mx >= x && mx < x + CELL - 2 && my >= y && my < y + CELL - 2) {
                this.sel = i;
                return true;
            }
        }
        for (int i = 0; i < LaneBoardData.TOKENS.length; i++) {
            int x = this.palX(i);
            int y = this.palY(i);
            if (mx >= x && mx < x + PAL_CELL - 4 && my >= y && my < y + PAL_CELL - 4) {
                this.lanes.set(this.sel, LaneBoardData.TOKENS[i]);
                return true;
            }
        }
        return false;
    }
}
