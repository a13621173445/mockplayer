package com.mockplayer.gui;

import com.mockplayer.api.Bot;
import com.mockplayer.config.MockplayerConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * 左栏假人列表滑条渲染（P4-6 拆组件）：原版风格轨道 + 滑块，仅多假人时显示。
 *
 * 纯搬移自 BotControlScreen，行为零变化；列表来源（coreBots）与滚动偏移仍由
 * BotControlScreen 持有，本类只读 screen 的包级字段与静态方法。
 */
public final class BotListPanel {

    private BotListPanel() {
    }

    /** 左栏假人列表滑条（原版风格轨道 + 滑块；仅多假人时显示，可拖动/滚轮）。 */
    public static void render(BotControlScreen screen, GuiGraphicsExtractor graphics) {
        List<Bot> bots = BotControlScreen.coreBots();
        if (!BotControlScreen.shouldShowScrollbar(bots.size(), BotControlScreen.VISIBLE_BOT_SLOTS)) {
            return;
        }
        int trackX = screen.sx(BotControlScreen.LIST_X + BotControlScreen.LIST_W - 3);
        int trackTop = screen.sy(BotControlScreen.LIST_TOP);
        int trackH = screen.sh(BotControlScreen.LIST_BOTTOM - BotControlScreen.LIST_TOP);
        int thumbH = Math.max(18, Math.round(trackH * BotControlScreen.VISIBLE_BOT_SLOTS / (float) bots.size()));
        float ratio = (float) screen.botScrollOffset / (bots.size() - BotControlScreen.VISIBLE_BOT_SLOTS);
        int thumbY = trackTop + Math.round((trackH - thumbH) * ratio);
        float opacity = MockplayerConfig.get().getGuiOpacity();
        graphics.fill(trackX, trackTop, trackX + screen.sw(2), trackTop + trackH,
                BotControlHud.withAlpha(0x8F0E1420, opacity));
        graphics.fill(trackX, thumbY, trackX + screen.sw(2), thumbY + thumbH,
                BotControlHud.withAlpha(0xBF7FB2FF, opacity));
    }
}
