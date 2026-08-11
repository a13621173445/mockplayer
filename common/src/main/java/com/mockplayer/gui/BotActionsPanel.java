package com.mockplayer.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 动作面板渲染（P4-6 拆组件）：四个分区标题（按钮本身由控件渲染）。
 *
 * 纯搬移自 BotControlScreen，行为零变化；分区标题逻辑 y 由 BotControlScreen
 * 在 init 时计算并持有，本类只读 screen 的包级字段。
 */
public final class BotActionsPanel {

    private BotActionsPanel() {
    }

    /** 动作 Tab：分区标题（按钮本身由控件渲染）。 */
    public static void render(BotControlScreen screen, GuiGraphicsExtractor graphics) {
        graphics.text(screen.font(), Component.translatable("gui.mockplayer.section.look"),
                screen.sx(BotControlScreen.CONTENT_X), screen.sy(screen.lookTitleY), 0xFFA8C8FF);
        graphics.text(screen.font(), Component.translatable("gui.mockplayer.section.move"),
                screen.sx(BotControlScreen.CONTENT_X), screen.sy(screen.moveTitleY), 0xFFA8C8FF);
        graphics.text(screen.font(), Component.translatable("gui.mockplayer.section.interact"),
                screen.sx(BotControlScreen.CONTENT_X), screen.sy(screen.interactTitleY), 0xFFA8C8FF);
        graphics.text(screen.font(), Component.translatable("gui.mockplayer.section.system"),
                screen.sx(BotControlScreen.CONTENT_X), screen.sy(screen.systemTitleY), 0xFFA8C8FF);
    }
}
