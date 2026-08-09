package com.mockplayer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * YACL 缺席时的配置界面兜底（纯原版 Screen，零 YACL 依赖）。
 *
 * 输入：父界面（ModMenu 传入的上一级）
 * 输出：提示玩家装 YACL 才有图形界面 + 「完成」按钮返回
 *
 * 配置文件本身始终可手改，此界面只是说明性兜底，不影响游戏功能。
 */
public class MissingYaclScreen extends Screen {

    private final Screen parent;

    public MissingYaclScreen(Screen parent) {
        super(Component.translatable("config.mockplayer.missing_yacl.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(this.width / 2 - 75, this.height / 2 + 30, 150, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font,
                Component.translatable("config.mockplayer.missing_yacl.message"),
                this.width / 2, this.height / 2 - 20, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(this.parent);
    }
}
