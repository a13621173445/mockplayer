package com.mockplayer.gui;

import com.mockplayer.config.MockplayerConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 背包面板渲染（P4-6 拆组件）：46 槽假人背包 + 红色丢弃格 + 通用槽位绘制。
 *
 * 纯搬移自 BotControlScreen，行为零变化；布局缩放/渲染状态仍由 BotControlScreen 持有，
 * 本类只读 screen 的包级字段与方法。槽位命中（inventorySlotAt）随渲染逻辑搬入，
 * 供 BotControlScreen 点击处理与 BotContainerPanel 复用。
 */
public final class BotInventoryPanel {

    private BotInventoryPanel() {
    }

    /**
     * 假人背包（inventoryMenu 槽位：盔甲 5-8 / 主背包 9-35 / 快捷栏 36-44 / 副手 45）
     * + 红色丢弃格（副手右侧，SLOT_CLICKED_OUTSIDE 语义）。
     */
    public static void render(BotControlScreen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        net.minecraft.client.player.LocalPlayer player = screen.selected.getLocalPlayer();
        if (player == null) {
            return;
        }
        double mx = screen.localX(mouseX);
        double my = screen.localY(mouseY);
        int hovered = inventorySlotAt(mx, my);
        // 注意：显示与点击共用「菜单槽」语义（盔甲 5-8 / 主背包 9-35 / 快捷栏 36-44 / 副手 45），
        // 不能直接拿菜单槽号去 Inventory.getItem（那里 0-8 才是快捷栏，36-40 是装备/副手）
        // 盔甲列（槽 5-8）
        for (int i = 0; i < 4; i++) {
            drawSlot(screen, graphics, BotControlScreen.CONTENT_X, BotControlScreen.CONTENT_Y + i * BotControlScreen.CELL,
                    BotControlHud.inventoryItem(player, 5 + i), hovered == 5 + i, BotControlHud.slotIcon(player, 5 + i));
        }
        // 主背包 27 格（槽 9-35）
        for (int i = 0; i < 27; i++) {
            drawSlot(screen, graphics, BotControlScreen.CONTENT_X + 24 + (i % 9) * BotControlScreen.CELL,
                    BotControlScreen.CONTENT_Y + (i / 9) * BotControlScreen.CELL,
                    BotControlHud.inventoryItem(player, 9 + i), hovered == 9 + i, BotControlHud.slotIcon(player, 9 + i));
        }
        // 快捷栏 9 格（槽 36-44）
        for (int i = 0; i < 9; i++) {
            drawSlot(screen, graphics, BotControlScreen.CONTENT_X + 24 + i * BotControlScreen.CELL,
                    BotControlScreen.CONTENT_Y + 3 * BotControlScreen.CELL,
                    BotControlHud.inventoryItem(player, 36 + i), hovered == 36 + i, BotControlHud.slotIcon(player, 36 + i));
        }
        // 副手（槽 45）
        drawSlot(screen, graphics, BotControlScreen.CONTENT_X + 24 + 9 * BotControlScreen.CELL,
                BotControlScreen.CONTENT_Y + 3 * BotControlScreen.CELL,
                BotControlHud.inventoryItem(player, 45), hovered == 45, BotControlHud.slotIcon(player, 45));
        // 红色丢弃格子（副手右侧一格）：物品放进去 = 原版点击菜单外丢弃
        drawDiscardSlot(screen, graphics, BotControlScreen.CONTENT_X + 24 + 10 * BotControlScreen.CELL,
                BotControlScreen.CONTENT_Y + 3 * BotControlScreen.CELL,
                hovered == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE);
        // 选中槽高亮
        int sel = player.getInventory().getSelectedSlot();
        graphics.outline(screen.sx(BotControlScreen.CONTENT_X + 24 + sel * BotControlScreen.CELL),
                screen.sy(BotControlScreen.CONTENT_Y + 3 * BotControlScreen.CELL),
                screen.sw(BotControlScreen.SLOT + 2), screen.sw(BotControlScreen.SLOT + 2), 0xFFFFFF00);
        // 悬停物品信息（原版 tooltip + 数量行）
        if (hovered >= 0) {
            List<Component> lines = BotControlHud.slotTooltip(player, hovered);
            if (lines != null) {
                graphics.setTooltipForNextFrame(screen.font(),
                        lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
                BotGui.recordTooltip();
            }
        } else if (hovered == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
            graphics.setTooltipForNextFrame(screen.font(),
                    List.of(Component.translatable("gui.mockplayer.discard").getVisualOrderText()), mouseX, mouseY);
            BotGui.recordTooltip();
        }
    }

    /**
     * 假人背包格子（inventoryMenu 槽位布局：盔甲 5-8 / 主背包 9-35 / 快捷栏 36-44 / 副手 45）。
     * 供 BotControlScreen 点击处理与渲染共用，保证显示/点击同一套几何。
     */
    static int inventorySlotAt(double mx, double my) {
        double bx = mx - BotControlScreen.CONTENT_X;
        double by = my - BotControlScreen.CONTENT_Y;
        int gx = (int) (bx / BotControlScreen.CELL);
        int gy = (int) (by / BotControlScreen.CELL);
        // 盔甲列（x=0）
        if (gx == 0 && gy >= 0 && gy < 4) {
            return 5 + gy;
        }
        // 主背包 3 行（x=24 起，y=0..2）
        int gxMain = (int) ((bx - 24) / BotControlScreen.CELL);
        if (gxMain >= 0 && gxMain < 9 && gy >= 0 && gy < 3) {
            return 9 + gy * 9 + gxMain;
        }
        // 快捷栏 1 行（y=3）+ 副手（x=24+9*CELL）
        if (gy == 3) {
            if (gxMain >= 0 && gxMain < 9) {
                return 36 + gxMain;
            }
            int gxOff = (int) ((bx - 24 - 9 * BotControlScreen.CELL) / BotControlScreen.CELL);
            if (gxOff == 0) {
                return 45;
            }
            if (gxOff == 1) {
                return AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;
            }
        }
        return -1;
    }

    /** 红色丢弃格子（红色底 + 白色 ×；悬停变亮，hover 时提示「丢弃」）。 */
    private static void drawDiscardSlot(BotControlScreen screen, GuiGraphicsExtractor graphics,
                                        int lx, int ly, boolean hovered) {
        int x = screen.sx(lx);
        int y = screen.sy(ly);
        int cell = screen.sw(BotControlScreen.CELL);
        float opacity = MockplayerConfig.get().getGuiOpacity();
        graphics.fill(x, y, x + cell, y + cell,
                hovered ? BotControlHud.withAlpha(0x8FDF6060, opacity) : BotControlHud.withAlpha(0x8FC04040, opacity));
        graphics.outline(x, y, cell, cell,
                hovered ? BotControlHud.withAlpha(0xBFE07070, opacity) : BotControlHud.withAlpha(0xBFB03030, opacity));
        String mark = "×";
        int tw = screen.font().width(mark);
        graphics.text(screen.font(), Component.literal(mark),
                x + (cell - tw) / 2, y + (cell - screen.font().lineHeight) / 2,
                hovered ? 0xFFFFFFFF : 0xFFE8E8E8);
        BotGui.recordDiscardSlot();
    }

    /** 画一个槽位（逻辑坐标入参，内部换算屏幕坐标；边框 + 空槽图标 + 物品图标 + 数量 + 悬停高亮）。 */
    static void drawSlot(BotControlScreen screen, GuiGraphicsExtractor graphics, int lx, int ly, ItemStack stack,
                         boolean hovered, Identifier emptyIcon) {
        int x = screen.sx(lx);
        int y = screen.sy(ly);
        int cell = screen.sw(BotControlScreen.CELL);
        int slot = Math.max(1, cell - 2);
        float opacity = MockplayerConfig.get().getGuiOpacity();
        graphics.fill(x, y, x + cell, y + cell,
                hovered ? BotControlHud.withAlpha(BotControlScreen.SLOT_BG_HOVER, opacity)
                        : BotControlHud.withAlpha(BotControlScreen.SLOT_BG, opacity));
        graphics.outline(x, y, cell, cell,
                hovered ? BotControlHud.withAlpha(BotControlScreen.SLOT_BORDER_HOVER, opacity)
                        : BotControlHud.withAlpha(BotControlScreen.SLOT_BORDER, opacity));
        // 原版语义：槽位为空时画装备/副手背景图标（物品存在则不画）
        if (stack.isEmpty() && emptyIcon != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, emptyIcon, x + 1, y + 1,
                    screen.sw(16), screen.sw(16));
            BotGui.recordSlotIcon();
        }
        if (!stack.isEmpty()) {
            graphics.item(stack, x + 1, y + 1);
            // 数量/附魔角标走原版 itemDecorations（与物品渲染同一位置）
            graphics.itemDecorations(screen.font(), stack, x + 1, y + 1);
            BotGui.recordItemDecoration();
        }
    }
}
